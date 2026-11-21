package com.changelens.core.parse;

import com.changelens.core.model.CodeModel.Branch;
import com.changelens.core.model.CodeModel.BehaviorFacts;
import com.changelens.core.model.CodeModel.CallSite;
import com.changelens.core.model.CodeModel.FieldModel;
import com.changelens.core.model.CodeModel.Layer;
import com.changelens.core.model.CodeModel.MethodModel;
import com.changelens.core.model.CodeModel.ResourceFile;
import com.changelens.core.model.CodeModel.Resolution;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.model.CodeModel.TypeKind;
import com.changelens.core.model.CodeModel.TypeModel;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns Java source files into a {@link Snapshot}: types, members and per-method behaviour facts.
 *
 * <p>No compiler or classpath is needed. Names are resolved with imports, packages and nesting inside the
 * repository; anything that cannot be resolved honestly is marked {@link Resolution#UNRESOLVED} rather than guessed.
 */
public final class JavaSnapshotBuilder {

    private static final Set<String> JAVA_LANG = Set.of("String", "Object", "Integer", "Long", "Double", "Float", "Boolean", "Character",
            "Byte", "Short", "Math", "System", "Thread", "Runnable", "Exception", "RuntimeException", "IllegalArgumentException",
            "IllegalStateException", "Throwable", "Error", "Iterable", "Comparable", "Number", "Enum", "Record", "StringBuilder", "Class");
    private static final Pattern GENERICS = Pattern.compile("<[^<>]*>");
    private static final Pattern WS = Pattern.compile("\\s+");

    private final ParserConfiguration config = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    private record Parsed(String path, String source, CompilationUnit cu, String pkg,
                          Map<String, String> imports, List<String> wildcards, Map<String, String> staticMethods) {
    }

    private final Map<String, Parsed> parsedByPath = new LinkedHashMap<>();
    private final TypeIndex index = new TypeIndex();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> failedFiles = new ArrayList<>();

    private final ParseCache cache;

    private JavaSnapshotBuilder(ParseCache cache) {
        this.cache = cache;
    }

    public static Snapshot build(String repo, String ref, List<SourceFile> javaFiles, List<ResourceFile> resources) {
        return build(repo, ref, javaFiles, resources, new ParseCache());
    }

    /** Same as above, but files that are identical in another snapshot built with the same cache are parsed only once. */
    public static Snapshot build(String repo, String ref, List<SourceFile> javaFiles, List<ResourceFile> resources, ParseCache cache) {
        return new JavaSnapshotBuilder(cache).run(repo, ref, javaFiles, resources);
    }

    private Snapshot run(String repo, String ref, List<SourceFile> files, List<ResourceFile> resources) {
        long tStart = System.nanoTime();
        // Parsing is independent per file, so it runs in parallel (one JavaParser per thread: the parser is not thread-safe).
        // The results are consumed in the original file order, so the output does not depend on thread timing.
        ThreadLocal<JavaParser> parsers = ThreadLocal.withInitial(() -> new JavaParser(config));
        List<SourceFile> javaOnly = files.stream().filter(f -> f.path().endsWith(".java")).toList();
        List<ParseResult<CompilationUnit>> results = javaOnly.parallelStream().map(f -> cache.get(f.content(), text -> parsers.get().parse(text))).toList();
        for (int i = 0; i < javaOnly.size(); i++) {
            SourceFile f = javaOnly.get(i);
            ParseResult<CompilationUnit> r = results.get(i);
            if (r.getResult().isEmpty()) {
                warnings.add("could not parse " + f.path() + ": " + firstProblem(r));
                failedFiles.add(f.path());
                continue;
            }
            if (!r.isSuccessful()) {
                warnings.add("partial parse of " + f.path() + ": " + firstProblem(r));
                failedFiles.add(f.path()); // incomplete tree: absence of a class here proves nothing
            }
            CompilationUnit cu = r.getResult().get();
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            Map<String, String> imports = new HashMap<>();
            List<String> wild = new ArrayList<>();
            Map<String, String> statics = new HashMap<>();
            for (ImportDeclaration im : cu.getImports()) {
                String name = im.getNameAsString();
                if (im.isStatic()) {
                    String cls = im.isAsterisk() ? name : name.substring(0, Math.max(0, name.lastIndexOf('.')));
                    String member = im.isAsterisk() ? "*" : name.substring(name.lastIndexOf('.') + 1);
                    statics.put(member, cls);
                } else if (im.isAsterisk()) {
                    wild.add(name);
                } else {
                    imports.put(name.substring(name.lastIndexOf('.') + 1), name);
                }
            }
            parsedByPath.put(f.path(), new Parsed(f.path(), f.content(), cu, pkg, imports, wild, statics));
        }
        long tPass1 = System.nanoTime();
        // pass 1: declare every type
        for (Parsed p : parsedByPath.values()) {
            for (TypeDeclaration<?> td : topAndNestedTypes(p.cu())) {
                declare(p, td);
            }
        }
        long tPass2 = System.nanoTime();
        // pass 2: build models with resolved behaviour
        // The type index is read-only from here on and every method is normalised on a clone of its AST, so the types can be
        // built in parallel. Results are put into the map in source order, so the output does not depend on thread timing.
        record Work(Parsed p, TypeDeclaration<?> td) {
        }
        List<Work> work = new ArrayList<>();
        for (Parsed p : parsedByPath.values()) {
            for (TypeDeclaration<?> td : topAndNestedTypes(p.cu())) {
                work.add(new Work(p, td));
            }
        }
        List<TypeModel> built = work.parallelStream().map(w -> buildType(w.p(), w.td())).toList();
        Map<String, TypeModel> types = new LinkedHashMap<>();
        for (TypeModel tm : built) {
            types.put(tm.fqn(), tm);
        }
        if (System.getProperty("changelens.timing") != null) {
            System.err.printf("[changelens] build %s: %d files, parse+index %d ms, declare %d ms, models %d ms%n", ref, files.size(),
                    (tPass1 - tStart) / 1_000_000, (tPass2 - tPass1) / 1_000_000, (System.nanoTime() - tPass2) / 1_000_000);
        }
        return new Snapshot(repo, ref, types, resources == null ? List.of() : List.copyOf(resources), List.copyOf(warnings), List.copyOf(failedFiles));
    }

    private static String firstProblem(ParseResult<?> r) {
        return r.getProblems().isEmpty() ? "unknown" : r.getProblems().get(0).getMessage();
    }

    // ------------------------------------------------------------------ declarations

    private static List<TypeDeclaration<?>> topAndNestedTypes(CompilationUnit cu) {
        List<TypeDeclaration<?>> out = new ArrayList<>();
        for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
            if (td.findAncestor(BlockStmt.class).isPresent()) {
                continue; // local classes are implementation detail of a method
            }
            out.add(td);
        }
        return out;
    }

    private static String fqnOf(Parsed p, TypeDeclaration<?> td) {
        Deque<String> names = new ArrayDeque<>();
        Node n = td;
        while (n != null) {
            if (n instanceof TypeDeclaration<?> t) {
                names.addFirst(t.getNameAsString());
            }
            n = n.getParentNode().orElse(null);
        }
        String nested = String.join(".", names);
        return p.pkg().isEmpty() ? nested : p.pkg() + "." + nested;
    }

    private void declare(Parsed p, TypeDeclaration<?> td) {
        String fqn = fqnOf(p, td);
        TypeIndex.Info info = new TypeIndex.Info(fqn, p.pkg());
        info.filePath = p.path();
        info.isInterface = td instanceof ClassOrInterfaceDeclaration c && c.isInterface();
        if (td instanceof ClassOrInterfaceDeclaration c) {
            c.getExtendedTypes().forEach(t -> info.superTypeNames.add(t.getNameWithScope()));
            c.getImplementedTypes().forEach(t -> info.superTypeNames.add(t.getNameWithScope()));
        } else if (td instanceof EnumDeclaration e) {
            e.getImplementedTypes().forEach(t -> info.superTypeNames.add(t.getNameWithScope()));
        } else if (td instanceof RecordDeclaration r) {
            r.getImplementedTypes().forEach(t -> info.superTypeNames.add(t.getNameWithScope()));
            r.getParameters().forEach(pa -> info.fieldTypes.put(pa.getNameAsString(), pa.getType().asString()));
        }
        for (BodyDeclaration<?> m : td.getMembers()) {
            if (m instanceof FieldDeclaration fd) {
                fd.getVariables().forEach(v -> info.fieldTypes.put(v.getNameAsString(), v.getType().asString()));
            } else if (m instanceof MethodDeclaration md) {
                info.methods.computeIfAbsent(md.getNameAsString(), k -> new ArrayList<>())
                        .add(new TypeIndex.MethodSig(md.getNameAsString(), md.getParameters().size(), md.getType().asString()));
            }
        }
        index.add(info);
    }

    // ------------------------------------------------------------------ type resolution

    /** Result of resolving a type name: the best-known qualified name and whether it lives in this repository. */
    private record Resolved(String name, boolean inRepo) {
    }

    static String erase(String t) {
        String s = t == null ? "" : t;
        String prev;
        do {
            prev = s;
            s = GENERICS.matcher(s).replaceAll("");
        } while (!s.equals(prev));
        return s.replace("...", "[]").replace("@", "").trim();
    }

    private Resolved resolveType(String raw, Parsed file, String enclosingFqn) {
        String t = erase(raw).replace("[]", "").trim();
        if (t.isEmpty() || t.equals("var")) {
            return null;
        }
        if (t.contains(".")) {
            if (index.contains(t)) {
                return new Resolved(t, true);
            }
            String first = t.substring(0, t.indexOf('.'));
            Resolved head = resolveType(first, file, enclosingFqn);
            if (head != null) {
                String candidate = head.name() + t.substring(first.length());
                return new Resolved(candidate, index.contains(candidate));
            }
            return new Resolved(t, false);
        }
        // nested types of the enclosing type chain
        String scope = enclosingFqn;
        while (scope != null && !scope.isEmpty()) {
            String cand = scope + "." + t;
            if (index.contains(cand)) {
                return new Resolved(cand, true);
            }
            int dot = scope.lastIndexOf('.');
            scope = dot < 0 ? null : scope.substring(0, dot);
        }
        String imp = file.imports().get(t);
        if (imp != null) {
            return new Resolved(imp, index.contains(imp));
        }
        if (!file.pkg().isEmpty() && index.contains(file.pkg() + "." + t)) {
            return new Resolved(file.pkg() + "." + t, true);
        }
        if (file.pkg().isEmpty() && index.contains(t)) {
            return new Resolved(t, true);
        }
        for (String w : file.wildcards()) {
            if (index.contains(w + "." + t)) {
                return new Resolved(w + "." + t, true);
            }
        }
        Set<String> sameSimple = index.bySimpleName(t);
        if (sameSimple.size() == 1) {
            return new Resolved(sameSimple.iterator().next(), true);
        }
        if (JAVA_LANG.contains(t)) {
            return new Resolved("java.lang." + t, false);
        }
        return null;
    }

    private List<String> superFqns(TypeIndex.Info info) {
        Parsed file = parsedByPath.get(info.filePath);
        List<String> out = new ArrayList<>();
        for (String s : info.superTypeNames) {
            Resolved r = file == null ? null : resolveType(s, file, info.fqn);
            if (r != null && r.inRepo()) {
                out.add(r.name());
            }
        }
        return out;
    }

    /** Field type searched through this type and its in-repo supertypes. */
    private String fieldType(String typeFqn, String field, Set<String> seen) {
        TypeIndex.Info info = index.get(typeFqn);
        if (info == null || !seen.add(typeFqn)) {
            return null;
        }
        String t = info.fieldTypes.get(field);
        if (t != null) {
            return t;
        }
        for (String s : superFqns(info)) {
            String r = fieldType(s, field, seen);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** The in-repo type (this one or a supertype) that declares {@code method} with {@code argc} arguments, or null. */
    private String declaringType(String typeFqn, String method, int argc, Set<String> seen) {
        TypeIndex.Info info = index.get(typeFqn);
        if (info == null || !seen.add(typeFqn)) {
            return null;
        }
        List<TypeIndex.MethodSig> sigs = info.methods.get(method);
        if (sigs != null && sigs.stream().anyMatch(s -> s.paramCount() == argc || s.paramCount() < 0)) {
            return typeFqn;
        }
        for (String s : superFqns(info)) {
            String r = declaringType(s, method, argc, seen);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private String returnTypeOf(String typeFqn, String method, int argc) {
        String owner = declaringType(typeFqn, method, argc, new LinkedHashSet<>());
        if (owner == null) {
            return null;
        }
        TypeIndex.Info info = index.get(owner);
        return info.methods.get(method).stream().filter(s -> s.paramCount() == argc).map(TypeIndex.MethodSig::returnType).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ models

    private TypeModel buildType(Parsed p, TypeDeclaration<?> td) {
        String fqn = fqnOf(p, td);
        TypeKind kind = td instanceof EnumDeclaration ? TypeKind.ENUM
                : td instanceof RecordDeclaration ? TypeKind.RECORD
                : td instanceof AnnotationDeclaration ? TypeKind.ANNOTATION
                : (td instanceof ClassOrInterfaceDeclaration c && c.isInterface()) ? TypeKind.INTERFACE : TypeKind.CLASS;
        List<String> ext = new ArrayList<>();
        List<String> impl = new ArrayList<>();
        if (td instanceof ClassOrInterfaceDeclaration c) {
            c.getExtendedTypes().forEach(t -> ext.add(t.asString()));
            c.getImplementedTypes().forEach(t -> impl.add(t.asString()));
        } else if (td instanceof EnumDeclaration e) {
            e.getImplementedTypes().forEach(t -> impl.add(t.asString()));
        } else if (td instanceof RecordDeclaration r) {
            r.getImplementedTypes().forEach(t -> impl.add(t.asString()));
        }
        List<String> annotations = annotationTexts(td.getAnnotations());
        List<FieldModel> fields = new ArrayList<>();
        List<MethodModel> methods = new ArrayList<>();
        if (td instanceof RecordDeclaration r) {
            r.getParameters().forEach(pa -> fields.add(new FieldModel(pa.getNameAsString(), erase(pa.getType().asString()),
                    Set.of("private", "final"), annotationTexts(pa.getAnnotations()), line(pa), "")));
        }
        if (td instanceof EnumDeclaration en) {
            en.getEntries().forEach(c -> fields.add(new FieldModel(c.getNameAsString(), "enum-constant", Set.of(), annotationTexts(c.getAnnotations()), line(c), c.getArguments().toString())));
        }
        for (BodyDeclaration<?> m : td.getMembers()) {
            if (m instanceof FieldDeclaration fd) {
                for (VariableDeclarator v : fd.getVariables()) {
                    fields.add(new FieldModel(v.getNameAsString(), erase(v.getType().asString()), modifiers(fd.getModifiers()),
                            annotationTexts(fd.getAnnotations()), line(fd), v.getInitializer().map(i -> normExpr(i)).orElse("")));
                }
            } else if (m instanceof CallableDeclaration<?> cd) {
                methods.add(buildMethod(p, td, fqn, cd));
            }
        }
        Layer layer = classify(p.path(), td, kind, ext, impl, annotations, methods);
        return new TypeModel(fqn, td.getNameAsString(), p.pkg(), kind, ext, impl, modifiers(td.getModifiers()), annotations, layer,
                p.path(), line(td), endLine(td), fields, methods);
    }

    private MethodModel buildMethod(Parsed p, TypeDeclaration<?> td, String typeFqn, CallableDeclaration<?> cd) {
        boolean ctor = cd instanceof ConstructorDeclaration;
        String name = ctor ? "<init>" : cd.getNameAsString();
        List<String> paramTypes = cd.getParameters().stream().map(pa -> erase(pa.getType().asString()) + (pa.isVarArgs() ? "[]" : "")).toList();
        List<String> paramNames = cd.getParameters().stream().map(Parameter::getNameAsString).toList();
        String ret = ctor ? "" : erase(((MethodDeclaration) cd).getType().asString());
        String id = typeFqn + "#" + name + "(" + String.join(",", paramTypes) + ")";
        String signature = (ret.isEmpty() ? "" : ret + " ") + (ctor ? td.getNameAsString() : name) + "(" + String.join(", ", paramTypes) + ")";
        Optional<BlockStmt> body = cd instanceof MethodDeclaration md ? md.getBody() : Optional.of(((ConstructorDeclaration) cd).getBody());
        int start = line(cd);
        int end = endLine(cd);
        String raw = slice(p.source(), start, end);
        String norm;
        String alpha;
        int stmts = 0;
        BehaviorFacts facts = BehaviorFacts.empty();
        if (body.isPresent()) {
            CallableDeclaration<?> normalised = (CallableDeclaration<?>) cd.clone();
            stripComments(normalised);
            norm = WS.matcher(normalised.toString()).replaceAll(" ");
            CallableDeclaration<?> renamed = (CallableDeclaration<?>) normalised.clone();
            alphaRename(renamed);
            alpha = bodyOf(renamed).map(bd -> WS.matcher(bd.toString()).replaceAll(" ")).orElse(""); // body only, locals renamed
            stmts = (int) body.get().findAll(Statement.class).stream().filter(s -> !(s instanceof BlockStmt)).count();
            facts = new BehaviorExtractor(p, typeFqn, cd, body.get()).extract();
        } else {
            CallableDeclaration<?> normalised = (CallableDeclaration<?>) cd.clone();
            stripComments(normalised);
            norm = WS.matcher(normalised.toString()).replaceAll(" ");
            alpha = "";
        }
        return new MethodModel(id, typeFqn, name, signature, ret, paramTypes, paramNames, modifiers(cd.getModifiers()),
                annotationTexts(cd.getAnnotations()), start, end, ctor, body.isPresent(), Hashing.sha(raw), Hashing.sha(norm),
                Hashing.sha(alpha), stmts, facts);
    }

    // ------------------------------------------------------------------ normalisation

    private static Optional<BlockStmt> bodyOf(CallableDeclaration<?> cd) {
        if (cd instanceof MethodDeclaration md) {
            return md.getBody();
        }
        return Optional.of(((ConstructorDeclaration) cd).getBody());
    }

    private static String normExpr(Expression e) {
        Expression c = e.clone();
        c.getAllContainedComments().forEach(Comment::remove);
        return WS.matcher(c.toString()).replaceAll(" ");
    }

    private static void stripComments(Node n) {
        n.getAllContainedComments().forEach(Comment::remove);
        n.removeComment();
    }

    /** Rename every parameter and local variable to v0, v1, ... so that a pure rename is invisible to the hash. */
    private static void alphaRename(CallableDeclaration<?> cd) {
        // method parameters are numbered p0, p1... and every other local (variables, lambda and catch parameters) v0, v1...
        // separately, so adding a parameter does not shift the names inside an otherwise identical body
        Map<String, String> map = new LinkedHashMap<>();
        int[] counter = {0, 0};
        for (Parameter pa : cd.getParameters()) {
            map.computeIfAbsent(pa.getNameAsString(), k -> "p" + counter[0]++);
        }
        for (Parameter pa : cd.findAll(Parameter.class)) {
            map.computeIfAbsent(pa.getNameAsString(), k -> "v" + counter[1]++);
        }
        for (VariableDeclarator vd : cd.findAll(VariableDeclarator.class)) {
            map.computeIfAbsent(vd.getNameAsString(), k -> "v" + counter[1]++);
        }
        if (map.isEmpty()) {
            return;
        }
        cd.findAll(Parameter.class).forEach(pa -> pa.setName(map.get(pa.getNameAsString())));
        cd.findAll(VariableDeclarator.class).forEach(vd -> vd.setName(map.get(vd.getNameAsString())));
        cd.findAll(NameExpr.class).forEach(ne -> {
            String to = map.get(ne.getNameAsString());
            if (to != null) {
                ne.setName(to);
            }
        });
    }

    private static String slice(String source, int startLine, int endLine) {
        String[] lines = source.split("\n", -1);
        int s = Math.max(1, startLine);
        int e = Math.min(lines.length, endLine);
        StringBuilder sb = new StringBuilder();
        for (int i = s; i <= e; i++) {
            sb.append(lines[i - 1]).append('\n');
        }
        return sb.toString();
    }

    private static int line(Node n) {
        return n.getRange().map(r -> r.begin.line).orElse(0);
    }

    private static int endLine(Node n) {
        return n.getRange().map(r -> r.end.line).orElse(0);
    }

    private static Set<String> modifiers(List<Modifier> mods) {
        return mods.stream().map(m -> m.getKeyword().asString()).collect(Collectors.toCollection(TreeSet::new));
    }

    private static List<String> annotationTexts(List<? extends AnnotationExpr> anns) {
        return anns.stream().map(a -> WS.matcher(a.toString()).replaceAll(" ")).toList();
    }

    // ------------------------------------------------------------------ layers

    private static Layer classify(String path, TypeDeclaration<?> td, TypeKind kind, List<String> ext, List<String> impl,
                                  List<String> annotations, List<MethodModel> methods) {
        String all = String.join(" ", annotations);
        String name = td.getNameAsString();
        if (path.contains("src/test/") || name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("IT")
                || methods.stream().anyMatch(m -> m.annotations().stream().anyMatch(a -> a.startsWith("@Test") || a.startsWith("@ParameterizedTest")))) {
            return Layer.TEST;
        }
        if (all.contains("@RestController") || all.contains("@Controller") || all.contains("@RequestMapping")) {
            return Layer.CONTROLLER;
        }
        if (all.contains("@FeignClient") || (name.endsWith("Client") && (all.contains("@Component") || all.contains("@Service")))) {
            return Layer.CLIENT;
        }
        if (all.contains("@Entity") || all.contains("@Embeddable") || all.contains("@MappedSuperclass") || all.contains("@Document")) {
            return Layer.ENTITY;
        }
        boolean repoIface = kind == TypeKind.INTERFACE && (ext.stream().anyMatch(e -> e.contains("Repository") || e.contains("CrudRepository"))
                || name.endsWith("Repository") || name.endsWith("Dao"));
        if (all.contains("@Repository") || repoIface) {
            return Layer.REPOSITORY;
        }
        if (all.contains("@Configuration") || all.contains("@ConfigurationProperties") || all.contains("@SpringBootApplication")) {
            return Layer.CONFIG;
        }
        if (methods.stream().anyMatch(m -> m.annotations().stream().anyMatch(a -> a.startsWith("@KafkaListener") || a.startsWith("@RabbitListener")
                || a.startsWith("@EventListener") || a.startsWith("@JmsListener") || a.startsWith("@Scheduled")))) {
            return Layer.LISTENER;
        }
        if (all.contains("@Service") || name.endsWith("Service")) {
            return Layer.SERVICE;
        }
        return Layer.OTHER;
    }

    // ------------------------------------------------------------------ behaviour

    /** Extracts the behaviour facts of one method body. */
    private final class BehaviorExtractor {
        private final Parsed file;
        private final String typeFqn;
        private final CallableDeclaration<?> decl;
        private final BlockStmt body;
        private final Map<String, String> varTypes = new HashMap<>();
        private final Set<String> fieldNames = new HashSet<>();

        BehaviorExtractor(Parsed file, String typeFqn, CallableDeclaration<?> decl, BlockStmt body) {
            this.file = file;
            this.typeFqn = typeFqn;
            this.decl = decl;
            this.body = body;
        }

        BehaviorFacts extract() {
            TypeIndex.Info self = index.get(typeFqn);
            if (self != null) {
                fieldNames.addAll(self.fieldTypes.keySet());
            }
            decl.getParameters().forEach(pa -> varTypes.put(pa.getNameAsString(), pa.getType().asString()));
            // local variables (var is inferred from `new X()` or a resolvable in-repo call)
            for (VariableDeclarator vd : body.findAll(VariableDeclarator.class)) {
                String t = vd.getType().asString();
                if (vd.getType().isVarType()) {
                    t = vd.getInitializer().map(this::inferType).orElse(null);
                }
                if (t != null) {
                    varTypes.put(vd.getNameAsString(), t);
                }
            }
            for (CatchClause c : body.findAll(CatchClause.class)) {
                varTypes.put(c.getParameter().getNameAsString(), c.getParameter().getType().asString());
            }

            List<CallSite> calls = new ArrayList<>();
            for (MethodCallExpr mc : body.findAll(MethodCallExpr.class)) {
                calls.add(resolveCall(mc));
            }
            for (ObjectCreationExpr oc : body.findAll(ObjectCreationExpr.class)) {
                Resolved r = resolveType(oc.getType().asString(), file, typeFqn);
                Resolution res = r == null ? Resolution.UNRESOLVED : r.inRepo() ? Resolution.REPO : Resolution.EXTERNAL;
                calls.add(new CallSite(r == null ? erase(oc.getType().asString()) : r.name(), "<init>", oc.getArguments().size(), line(oc), res));
            }
            calls.sort(Comparator.comparingInt(CallSite::line).thenComparing(CallSite::key));

            List<Branch> branches = new ArrayList<>();
            for (IfStmt s : body.findAll(IfStmt.class)) {
                branches.add(new Branch("if", cond(s.getCondition()), line(s)));
            }
            for (ConditionalExpr s : body.findAll(ConditionalExpr.class)) {
                branches.add(new Branch("ternary", cond(s.getCondition()), line(s)));
            }
            for (SwitchStmt s : body.findAll(SwitchStmt.class)) {
                branches.add(new Branch("switch", cond(s.getSelector()), line(s)));
            }
            for (ForStmt s : body.findAll(ForStmt.class)) {
                branches.add(new Branch("for", s.getCompare().map(this::cond).orElse(""), line(s)));
            }
            for (ForEachStmt s : body.findAll(ForEachStmt.class)) {
                branches.add(new Branch("foreach", cond(s.getIterable()), line(s)));
            }
            for (WhileStmt s : body.findAll(WhileStmt.class)) {
                branches.add(new Branch("while", cond(s.getCondition()), line(s)));
            }
            for (DoStmt s : body.findAll(DoStmt.class)) {
                branches.add(new Branch("do", cond(s.getCondition()), line(s)));
            }
            for (TryStmt s : body.findAll(TryStmt.class)) {
                branches.add(new Branch("try", "", line(s)));
            }
            branches.sort(Comparator.comparingInt(Branch::line).thenComparing(Branch::key));

            List<String> thrown = new ArrayList<>();
            for (ThrowStmt t : body.findAll(ThrowStmt.class)) {
                Expression e = t.getExpression();
                thrown.add(e instanceof ObjectCreationExpr oc ? erase(oc.getType().asString()) : cond(e));
            }
            Collections.sort(thrown);
            List<String> caught = new ArrayList<>();
            for (CatchClause c : body.findAll(CatchClause.class)) {
                caught.add(erase(c.getParameter().getType().asString()));
            }
            Collections.sort(caught);
            int returns = body.findAll(ReturnStmt.class).size();
            Set<String> writes = new TreeSet<>();
            Set<String> locals = new HashSet<>(varTypes.keySet());
            for (AssignExpr a : body.findAll(AssignExpr.class)) {
                Expression t = a.getTarget();
                if (t instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
                    writes.add(fa.getNameAsString());
                } else if (t instanceof NameExpr ne && fieldNames.contains(ne.getNameAsString()) && !locals.contains(ne.getNameAsString())) {
                    writes.add(ne.getNameAsString());
                }
            }
            Set<String> literals = new TreeSet<>();
            for (StringLiteralExpr s : body.findAll(StringLiteralExpr.class)) {
                String v = s.getValue();
                if (!v.isBlank() && v.length() <= 200) {
                    literals.add(v);
                }
            }
            return new BehaviorFacts(List.copyOf(calls), List.copyOf(branches), List.copyOf(thrown), List.copyOf(caught), returns,
                    List.copyOf(writes), List.copyOf(literals));
        }

        private String cond(Expression e) {
            Expression c = e.clone();
            c.getAllContainedComments().forEach(Comment::remove);
            return WS.matcher(c.toString()).replaceAll(" ");
        }

        /** Best-effort type of an initialiser for `var x = ...`. Returns null when it cannot be known honestly. */
        private String inferType(Expression init) {
            if (init instanceof ObjectCreationExpr oc) {
                return oc.getType().asString();
            }
            if (init instanceof MethodCallExpr mc) {
                CallSite cs = resolveCall(mc);
                if (cs.resolution() == Resolution.REPO) {
                    return returnTypeOf(cs.targetType(), cs.targetMethod(), cs.argCount());
                }
            }
            return null;
        }

        private CallSite resolveCall(MethodCallExpr mc) {
            String m = mc.getNameAsString();
            int argc = mc.getArguments().size();
            int ln = line(mc);
            Optional<Expression> scope = mc.getScope();
            if (scope.isEmpty() || scope.get() instanceof ThisExpr) {
                String owner = declaringType(typeFqn, m, argc, new LinkedHashSet<>());
                if (owner != null) {
                    return new CallSite(owner, m, argc, ln, Resolution.REPO);
                }
                String stat = file.staticMethods().get(m);
                if (stat != null) {
                    return new CallSite(stat, m, argc, ln, Resolution.EXTERNAL);
                }
                return new CallSite(typeFqn, m, argc, ln, Resolution.UNRESOLVED);
            }
            Expression sc = scope.get();
            if (sc instanceof SuperExpr) {
                TypeIndex.Info info = index.get(typeFqn);
                if (info != null) {
                    for (String sup : superFqns(info)) {
                        String owner = declaringType(sup, m, argc, new LinkedHashSet<>());
                        if (owner != null) {
                            return new CallSite(owner, m, argc, ln, Resolution.REPO);
                        }
                    }
                }
                return new CallSite("super", m, argc, ln, Resolution.UNRESOLVED);
            }
            String typeText = null;
            if (sc instanceof NameExpr ne) {
                String n = ne.getNameAsString();
                typeText = varTypes.get(n);
                if (typeText == null) {
                    typeText = fieldType(typeFqn, n, new LinkedHashSet<>());
                }
                if (typeText == null) {
                    Resolved asType = resolveType(n, file, typeFqn); // ClassName.staticMethod()
                    if (asType != null) {
                        return toCall(asType, m, argc, ln);
                    }
                }
            } else if (sc instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
                typeText = fieldType(typeFqn, fa.getNameAsString(), new LinkedHashSet<>());
            } else if (sc instanceof ObjectCreationExpr oc) {
                typeText = oc.getType().asString();
            } else if (sc instanceof MethodCallExpr inner) {
                CallSite icp = resolveCall(inner);
                if (icp.resolution() == Resolution.REPO) {
                    typeText = returnTypeOf(icp.targetType(), icp.targetMethod(), icp.argCount());
                }
            }
            if (typeText == null) {
                return new CallSite("?", m, argc, ln, Resolution.UNRESOLVED);
            }
            Resolved r = resolveType(typeText, file, typeFqn);
            if (r == null) {
                return new CallSite(erase(typeText), m, argc, ln, Resolution.UNRESOLVED);
            }
            return toCall(r, m, argc, ln);
        }

        private CallSite toCall(Resolved r, String m, int argc, int ln) {
            if (r.inRepo()) {
                String owner = declaringType(r.name(), m, argc, new LinkedHashSet<>());
                return new CallSite(owner != null ? owner : r.name(), m, argc, ln, Resolution.REPO);
            }
            return new CallSite(r.name(), m, argc, ln, Resolution.EXTERNAL);
        }
    }

}
