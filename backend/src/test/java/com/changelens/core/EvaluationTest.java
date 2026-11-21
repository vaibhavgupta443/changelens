package com.changelens.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.changelens.core.diff.ChangeModel.ChangeKind;
import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.ChangeModel.MethodChange;
import com.changelens.core.diff.ChangeModel.TypeChange;
import com.changelens.core.diff.SnapshotDiffer;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.parse.JavaSnapshotBuilder;
import com.changelens.core.parse.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A labelled evaluation set: each case is a before/after pair with the label a careful human reviewer would give.
 * The test classifies every case with the engine, builds a confusion matrix and reports precision and recall per label.
 *
 * <p>Honest scope: the cases are written by hand to cover the kinds of change the engine claims to tell apart. They measure
 * whether the logic is right, not how well it generalises to every real-world codebase. The "harder" set was added after the
 * first set scored 100%, because a set written by the engine's author is too easy to be convincing on its own.
 */
class EvaluationTest {

    private record Case(String name, String label, List<String> base, List<String> head) {
    }

    private static final String CORE = "        int x = a + 1;\n        String t = s.trim();\n        return x + t.length();";

    private static String cls(String body) {
        return "package p;\npublic class C {\n    private int count;\n    private final Dep dep = new Dep();\n    public int f(int a, String s) {\n" + body + "\n    }\n}\n";
    }

    private static Case body(String name, String label, String baseBody, String headBody) {
        return new Case(name, label, List.of(cls(baseBody)), List.of(cls(headBody)));
    }

    // ------------------------------------------------------------------ core set

    private static List<Case> coreCases() {
        List<Case> c = new ArrayList<>();
        String core = CORE;
        c.add(body("identical", "UNCHANGED", core, core));
        c.add(body("reformatted braces and spacing", "COSMETIC", core, "        int x=a+1;\n        String t   =   s.trim( );\n        return x+t.length( );"));
        c.add(body("comment added", "COSMETIC", core, "        // compute\n        int x = a + 1;\n        String t = s.trim(); // trim\n        return x + t.length();"));
        c.add(body("comment removed", "COSMETIC", "        // compute\n" + core, core));
        c.add(body("blank lines added", "COSMETIC", core, "\n        int x = a + 1;\n\n        String t = s.trim();\n\n        return x + t.length();"));
        c.add(body("block comment added", "COSMETIC", core, "        /* explain */\n" + core));

        c.add(body("local variable renamed", "LOCAL_RENAME", core, "        int total = a + 1;\n        String t = s.trim();\n        return total + t.length();"));
        c.add(body("two locals renamed", "LOCAL_RENAME", core, "        int y = a + 1;\n        String u = s.trim();\n        return y + u.length();"));
        c.add(body("loop variable renamed", "LOCAL_RENAME",
                "        int sum = 0;\n        for (int i = 0; i < a; i++) { sum += i; }\n        return sum;",
                "        int sum = 0;\n        for (int k = 0; k < a; k++) { sum += k; }\n        return sum;"));

        c.add(body("branch added", "BEHAVIOR", core, "        if (a < 0) { return 0; }\n" + core));
        c.add(body("branch removed", "BEHAVIOR", "        if (a < 0) { return 0; }\n" + core, core));
        c.add(body("condition changed", "BEHAVIOR", "        if (a < 0) { return 0; }\n" + core, "        if (a <= 0) { return 0; }\n" + core));
        c.add(body("new method call", "BEHAVIOR", core, "        dep.audit(a);\n" + core));
        c.add(body("call removed", "BEHAVIOR", "        dep.audit(a);\n" + core, core));
        c.add(body("call replaced", "BEHAVIOR", "        dep.save(a);\n" + core, "        dep.saveAndFlush(a);\n" + core));
        c.add(body("exception thrown added", "BEHAVIOR", core, "        if (s == null) { throw new IllegalArgumentException(\"s\"); }\n" + core));
        c.add(body("try catch added", "BEHAVIOR", core,
                "        try {\n            dep.audit(a);\n        } catch (RuntimeException e) {\n            return -1;\n        }\n" + core));
        c.add(body("field write added", "BEHAVIOR", core, "        this.count = a;\n" + core));
        c.add(body("arithmetic changed", "BEHAVIOR", core, "        int x = a + 2;\n        String t = s.trim();\n        return x + t.length();"));
        c.add(body("string constant changed", "BEHAVIOR", "        dep.log(\"started\");\n" + core, "        dep.log(\"begin\");\n" + core));
        c.add(body("loop added", "BEHAVIOR", core, "        for (int i = 0; i < a; i++) { dep.audit(i); }\n" + core));
        c.add(body("return value changed", "BEHAVIOR", core, "        int x = a + 1;\n        String t = s.trim();\n        return x - t.length();"));
        c.add(body("switch added", "BEHAVIOR", core, "        switch (a) { case 1: return 1; default: break; }\n" + core));

        String withAnn = cls(core).replace("    public int f", "    @Transactional\n    public int f");
        c.add(new Case("annotation added", "SIGNATURE", List.of(cls(core)), List.of(withAnn)));
        c.add(new Case("annotation removed", "SIGNATURE", List.of(withAnn), List.of(cls(core))));
        c.add(new Case("annotation attribute changed", "SIGNATURE",
                List.of(cls(core).replace("    public int f", "    @Transactional(readOnly = true)\n    public int f")),
                List.of(cls(core).replace("    public int f", "    @Transactional(readOnly = false)\n    public int f"))));
        c.add(new Case("visibility changed", "SIGNATURE", List.of(cls(core)), List.of(cls(core).replace("public int f", "protected int f"))));
        c.add(new Case("static added", "SIGNATURE", List.of(cls(core)), List.of(cls(core).replace("public int f", "public static int f"))));

        String extra = "\n    public int extra(int q) {\n        int r = q * 2;\n        return r + 1;\n    }\n}\n";
        c.add(new Case("method renamed", "METHOD_RENAMED", List.of(cls(core)), List.of(cls(core).replace("public int f(", "public int g("))));
        c.add(new Case("method added", "METHOD_ADDED", List.of(cls(core)), List.of(cls(core).replace("\n}\n", extra))));
        c.add(new Case("method removed", "METHOD_REMOVED", List.of(cls(core).replace("\n}\n", extra)), List.of(cls(core))));
        String dst = "package p;\npublic class D {\n}\n";
        String dstWith = "package p;\npublic class D {\n    public int f(int a, String s) {\n" + core + "\n    }\n}\n";
        String srcOnly = "package p;\npublic class C {\n    public int keep() { return 1; }\n}\n";
        c.add(new Case("method moved to another class", "METHOD_MOVED", List.of(cls(core).replace("private final Dep dep = new Dep();", ""), dst),
                List.of(srcOnly, dstWith)));

        String moved = cls(core).replace("package p;", "package q.r;");
        c.add(new Case("class moved to another package", "TYPE_MOVED", List.of(cls(core)), List.of(moved)));
        String twoMethods = "package p;\npublic class C {\n    public int a1(int v) { int w = v + 1; return w * 2; }\n    public int b1(int v) { int w = v - 1; return w * 3; }\n}\n";
        c.add(new Case("class renamed", "TYPE_MOVED", List.of(twoMethods), List.of(twoMethods.replace("class C", "class Renamed"))));
        c.add(new Case("class added", "TYPE_ADDED", List.of(srcOnly), List.of(srcOnly, "package p;\npublic class Fresh { public int go() { return 7; } }\n")));
        c.add(new Case("class deleted", "TYPE_REMOVED", List.of(srcOnly, "package p;\npublic class Gone { public int go() { return 7; } }\n"), List.of(srcOnly)));
        return c;
    }

    // ------------------------------------------------------------------ harder set

    private static List<Case> hardCases() {
        List<Case> c = new ArrayList<>();
        String core = CORE;
        String base = cls(core);
        c.add(new Case("parameter type changed, same body", "SIGNATURE", List.of(base), List.of(base.replace("int a, String s", "long a, String s"))));
        c.add(new Case("parameter added, same body", "SIGNATURE", List.of(base), List.of(base.replace("int a, String s", "int a, String s, int extra"))));
        c.add(new Case("return type changed, same body", "SIGNATURE", List.of(base), List.of(base.replace("public int f", "public long f"))));
        String dBefore = "package p;\npublic class D {\n    public int f(int a, String s) {\n" + core + "\n    }\n    public int other() { return 2; }\n}\n";
        String dAfter = "package p;\npublic class D {\n    public int other() { return 2; }\n}\n";
        String cBefore = "package p;\npublic class C {\n    public int keep() { return 1; }\n}\n";
        String cAfter = "package p;\npublic class C {\n    public int keep() { return 1; }\n    public int f(int a, String s) {\n        dep.audit(a);\n" + core + "\n    }\n}\n";
        c.add(new Case("method moved and edited at the same time", "METHOD_MOVED", List.of(cBefore, dBefore), List.of(cAfter, dAfter)));
        c.add(new Case("enum constant added", "FIELD_CHANGED", List.of("package p;\npublic enum E { A, B }\n"), List.of("package p;\npublic enum E { A, B, C }\n")));
        c.add(new Case("field added", "FIELD_CHANGED", List.of(base), List.of(base.replace("    private int count;", "    private int count;\n    private int other;"))));
        c.add(new Case("field type changed", "FIELD_CHANGED", List.of(base), List.of(base.replace("private int count;", "private long count;"))));
        c.add(new Case("field initialiser changed", "FIELD_CHANGED",
                List.of(base.replace("private int count;", "private int count = 0;")), List.of(base.replace("private int count;", "private int count = 1;"))));
        String ctor = "package p;\npublic class K {\n    private int count;\n    public K(int v) {\n        this.count = v;\n    }\n}\n";
        c.add(new Case("constructor body changed", "BEHAVIOR", List.of(ctor), List.of(ctor.replace("this.count = v;", "this.count = v + 1;"))));
        c.add(new Case("overload added", "METHOD_ADDED", List.of(base),
                List.of(base.replace("\n}\n", "\n    public int f(int a) {\n        int y = a * 2;\n        return y + 1;\n    }\n}\n"))));
        c.add(new Case("import added only", "UNCHANGED", List.of(base), List.of(base.replace("package p;\n", "package p;\nimport java.util.List;\n"))));
        c.add(new Case("file the parser cannot read is not reported as deleted", "UNCHANGED", List.of(base), List.of("package p; public class C { this is not java")));
        String chain = "        return java.util.List.of(a).stream().map(v -> v + 1).findFirst().orElse(0);";
        String chainSplit = "        return java.util.List.of(a)\n                .stream()\n                .map(v -> v + 1)\n                .findFirst()\n                .orElse(0);";
        c.add(body("long call chain reformatted", "COSMETIC", chain, chainSplit));
        c.add(body("lambda parameter renamed", "LOCAL_RENAME", chain, chain.replace("v -> v + 1", "n -> n + 1")));
        c.add(body("catch variable renamed", "LOCAL_RENAME",
                "        try { dep.audit(a); } catch (RuntimeException e) { return -1; }\n        return a;",
                "        try { dep.audit(a); } catch (RuntimeException ex) { return -1; }\n        return a;"));
        c.add(body("else branch added", "BEHAVIOR", "        if (a > 0) { dep.audit(a); }\n        return a;",
                "        if (a > 0) { dep.audit(a); } else { dep.reject(a); }\n        return a;"));
        return c;
    }

    // ------------------------------------------------------------------ evaluation

    /** What the engine says about a case, in the same vocabulary as the expected labels. */
    private static String classify(Case c) {
        ChangeSet cs = SnapshotDiffer.diff(snap(c.base()), snap(c.head()));
        if (cs.types().isEmpty()) {
            return "UNCHANGED";
        }
        for (TypeChange t : cs.types()) {
            if (t.kind() == ChangeKind.ADDED) {
                return "TYPE_ADDED";
            }
            if (t.kind() == ChangeKind.REMOVED) {
                return "TYPE_REMOVED";
            }
        }
        for (TypeChange t : cs.types()) {
            if (t.kind() == ChangeKind.MOVED || t.kind() == ChangeKind.RENAMED || t.kind() == ChangeKind.MOVED_RENAMED) {
                return "TYPE_MOVED";
            }
        }
        for (TypeChange t : cs.types()) {
            for (MethodChange m : t.methods()) {
                if (m.kind() == ChangeKind.MOVED || m.kind() == ChangeKind.MOVED_RENAMED) {
                    return "METHOD_MOVED";
                }
            }
        }
        for (TypeChange t : cs.types()) {
            for (MethodChange m : t.methods()) {
                return switch (m.kind()) {
                    case RENAMED -> "METHOD_RENAMED";
                    case ADDED -> "METHOD_ADDED";
                    case REMOVED -> "METHOD_REMOVED";
                    default -> m.nature().name();
                };
            }
        }
        for (TypeChange t : cs.types()) {
            if (!t.fields().isEmpty()) {
                return "FIELD_CHANGED";
            }
        }
        return "OTHER";
    }

    private static Snapshot snap(List<String> sources) {
        List<SourceFile> files = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            files.add(new SourceFile("F" + i + ".java", sources.get(i)));
        }
        return JavaSnapshotBuilder.build("eval", "x", files, List.of());
    }

    private record Result(String table, List<String> wrong, int correct, int total) {
    }

    private static Result evaluate(List<Case> cases) {
        Map<String, int[]> perLabel = new TreeMap<>(); // [true positives, false positives, false negatives]
        List<String> wrong = new ArrayList<>();
        int correct = 0;
        for (Case c : cases) {
            String got = classify(c);
            perLabel.computeIfAbsent(c.label(), k -> new int[3]);
            perLabel.computeIfAbsent(got, k -> new int[3]);
            if (got.equals(c.label())) {
                correct++;
                perLabel.get(c.label())[0]++;
            } else {
                perLabel.get(c.label())[2]++;
                perLabel.get(got)[1]++;
                wrong.add(c.name() + ": expected " + c.label() + ", got " + got);
            }
        }
        StringBuilder md = new StringBuilder("| Label | Cases | Precision | Recall |\n|---|---|---|---|\n");
        for (String label : new TreeSet<>(perLabel.keySet())) {
            int[] m = perLabel.get(label);
            int n = m[0] + m[2];
            if (n == 0) {
                continue;
            }
            double p = (m[0] + m[1]) == 0 ? 0 : (double) m[0] / (m[0] + m[1]);
            md.append(String.format("| %s | %d | %.0f%% | %.0f%% |%n", label, n, p * 100, (double) m[0] / n * 100));
        }
        md.append(String.format("%n**%d of %d correct (%.1f%%).**%n", correct, cases.size(), 100.0 * correct / cases.size()));
        return new Result(md.toString(), wrong, correct, cases.size());
    }

    @Test
    void classificationMatchesTheLabelledSets() throws Exception {
        Result core = evaluate(coreCases());
        Result hard = evaluate(hardCases());
        StringBuilder md = new StringBuilder("### Core set\n\n").append(core.table());
        md.append("\n### Harder set (added after the core set scored 100%)\n\n").append(hard.table());
        if (!hard.wrong().isEmpty()) {
            md.append("\nMisclassified in the harder set:\n");
            hard.wrong().forEach(w -> md.append("- ").append(w).append('\n'));
        }
        Path out = Path.of("target", "evaluation.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println(md);
        assertThat(core.wrong()).as("core set misclassified").isEmpty();
    }
}
