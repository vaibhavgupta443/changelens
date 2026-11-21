package com.changelens.core.diff;

import com.changelens.core.diff.ChangeModel.BehaviorDelta;
import com.changelens.core.diff.ChangeModel.ChangeKind;
import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.ChangeModel.DeclDelta;
import com.changelens.core.diff.ChangeModel.FieldChange;
import com.changelens.core.diff.ChangeModel.MethodChange;
import com.changelens.core.diff.ChangeModel.Nature;
import com.changelens.core.diff.ChangeModel.PackageChange;
import com.changelens.core.diff.ChangeModel.Summary;
import com.changelens.core.diff.ChangeModel.TypeChange;
import com.changelens.core.model.CodeModel.BehaviorFacts;
import com.changelens.core.model.CodeModel.Branch;
import com.changelens.core.model.CodeModel.CallSite;
import com.changelens.core.model.CodeModel.FieldModel;
import com.changelens.core.model.CodeModel.MethodModel;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.model.CodeModel.TypeModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Compares two snapshots and says, for every type and method, what REALLY changed.
 *
 * <p>The central idea: a reviewer should not have to read a moved class, a reformatted file or a variable rename as if it
 * were new logic. Every method change is classified by comparing three hashes (raw text, normalised text without comments
 * and formatting, and the body with local names erased) before any behaviour is described.
 */
public final class SnapshotDiffer {

    private SnapshotDiffer() {
    }

    /** Mutable working copy of a type change, finalised once cross-type moves are known. */
    private static final class Work {
        TypeModel base;
        TypeModel head;
        ChangeKind kind;
        final List<MethodChange> methods = new ArrayList<>();
        final List<FieldChange> fields = new ArrayList<>();
        int unchanged;
    }

    public static ChangeSet diff(Snapshot base, Snapshot head) {
        Map<String, TypeModel> bt = base.types();
        Map<String, TypeModel> ht = head.types();
        List<Work> works = new ArrayList<>();

        Set<String> removed = new LinkedHashSet<>(bt.keySet());
        removed.removeAll(ht.keySet());
        Set<String> added = new LinkedHashSet<>(ht.keySet());
        added.removeAll(bt.keySet());
        // a file the parser could not read on one side says nothing about its classes: never report them as added or removed
        removed.removeIf(fqn -> head.failedFiles().contains(bt.get(fqn).filePath()));
        added.removeIf(fqn -> base.failedFiles().contains(ht.get(fqn).filePath()));

        // 1) types that exist on both sides
        for (String fqn : ht.keySet()) {
            if (bt.containsKey(fqn)) {
                Work w = pair(bt.get(fqn), ht.get(fqn), ChangeKind.MODIFIED);
                if (hasChange(w)) {
                    works.add(w);
                }
            }
        }
        // 2) types that moved or were renamed
        Map<String, String> relocated = matchRelocatedTypes(removed, added, bt, ht);
        for (var e : relocated.entrySet()) {
            TypeModel b = bt.get(e.getKey());
            TypeModel h = ht.get(e.getValue());
            ChangeKind k = b.simpleName().equals(h.simpleName()) ? ChangeKind.MOVED
                    : b.packageName().equals(h.packageName()) ? ChangeKind.RENAMED : ChangeKind.MOVED_RENAMED;
            works.add(pair(b, h, k));
            removed.remove(e.getKey());
            added.remove(e.getValue());
        }
        // 3) genuinely new and genuinely deleted types
        for (String fqn : added) {
            Work w = new Work();
            w.head = ht.get(fqn);
            w.kind = ChangeKind.ADDED;
            for (MethodModel m : w.head.methods()) {
                w.methods.add(methodAdded(m));
            }
            for (FieldModel f : w.head.fields()) {
                w.fields.add(new FieldChange(f.name(), ChangeKind.ADDED, null, f.type(), f.annotations(), List.of(), false));
            }
            works.add(w);
        }
        for (String fqn : removed) {
            Work w = new Work();
            w.base = bt.get(fqn);
            w.kind = ChangeKind.REMOVED;
            for (MethodModel m : w.base.methods()) {
                w.methods.add(methodRemoved(m));
            }
            for (FieldModel f : w.base.fields()) {
                w.fields.add(new FieldChange(f.name(), ChangeKind.REMOVED, f.type(), null, List.of(), f.annotations(), false));
            }
            works.add(w);
        }
        // 4) methods that moved between types (extract class, move method)
        detectCrossTypeMoves(works);

        List<TypeChange> types = works.stream().map(SnapshotDiffer::finish).collect(Collectors.toList());
        types.sort((a, b) -> a.fqn().compareTo(b.fqn()));
        return new ChangeSet(head.repo(), base.ref(), head.ref(), List.copyOf(types), packages(types), summary(types),
                mergeWarnings(base, head));
    }

    private static List<String> mergeWarnings(Snapshot base, Snapshot head) {
        List<String> w = new ArrayList<>();
        base.parseWarnings().forEach(x -> w.add("base: " + x));
        head.parseWarnings().forEach(x -> w.add("head: " + x));
        return w;
    }

    // ------------------------------------------------------------------ type pairing

    private static boolean hasChange(Work w) {
        return !w.methods.isEmpty() || !w.fields.isEmpty() || !annDelta(w).isEmpty() || !superDelta(w).isEmpty()
                || w.kind != ChangeKind.MODIFIED || (w.base != null && w.head != null && w.base.kind() != w.head.kind());
    }

    private static List<String> annDelta(Work w) {
        if (w.base == null || w.head == null) {
            return List.of();
        }
        return diffLists(w.base.annotations(), w.head.annotations(), true);
    }

    private static List<String> superDelta(Work w) {
        if (w.base == null || w.head == null) {
            return List.of();
        }
        return diffLists(superOf(w.base), superOf(w.head), true);
    }

    private static List<String> superOf(TypeModel t) {
        List<String> l = new ArrayList<>(t.extendsTypes());
        l.addAll(t.implementsTypes());
        return l;
    }

    private static Work pair(TypeModel b, TypeModel h, ChangeKind kind) {
        Work w = new Work();
        w.base = b;
        w.head = h;
        w.kind = kind;
        // fields
        Map<String, FieldModel> bf = b.fields().stream().collect(Collectors.toMap(FieldModel::name, f -> f, (x, y) -> x, LinkedHashMap::new));
        Map<String, FieldModel> hf = h.fields().stream().collect(Collectors.toMap(FieldModel::name, f -> f, (x, y) -> x, LinkedHashMap::new));
        for (var e : hf.entrySet()) {
            FieldModel o = bf.get(e.getKey());
            FieldModel n = e.getValue();
            if (o == null) {
                w.fields.add(new FieldChange(n.name(), ChangeKind.ADDED, null, n.type(), n.annotations(), List.of(), false));
            } else if (!o.type().equals(n.type()) || !o.annotations().equals(n.annotations()) || !o.initializer().equals(n.initializer())) {
                w.fields.add(new FieldChange(n.name(), ChangeKind.MODIFIED, o.type(), n.type(),
                        diffLists(o.annotations(), n.annotations(), true), diffLists(n.annotations(), o.annotations(), true),
                        !o.initializer().equals(n.initializer())));
            }
        }
        for (var e : bf.entrySet()) {
            if (!hf.containsKey(e.getKey())) {
                FieldModel o = e.getValue();
                w.fields.add(new FieldChange(o.name(), ChangeKind.REMOVED, o.type(), null, List.of(), o.annotations(), false));
            }
        }
        // methods
        Map<String, MethodModel> bm = new LinkedHashMap<>();
        Map<String, MethodModel> hm = new LinkedHashMap<>();
        b.methods().forEach(m -> bm.put(memberKey(m), m));
        h.methods().forEach(m -> hm.put(memberKey(m), m));
        List<MethodModel> remRes = new ArrayList<>();
        List<MethodModel> addRes = new ArrayList<>();
        for (var e : hm.entrySet()) {
            MethodModel o = bm.get(e.getKey());
            if (o == null) {
                addRes.add(e.getValue());
                continue;
            }
            MethodChange mc = compare(o, e.getValue(), ChangeKind.MODIFIED);
            if (mc == null) {
                w.unchanged++;
            } else {
                w.methods.add(mc);
            }
        }
        for (var e : bm.entrySet()) {
            if (!hm.containsKey(e.getKey())) {
                remRes.add(e.getValue());
            }
        }
        pairResidualMethods(w, remRes, addRes);
        return w;
    }

    /** name(paramTypes): the identity of a method inside a type, independent of which type it lives in. */
    private static String memberKey(MethodModel m) {
        return m.name() + "(" + String.join(",", m.paramTypes()) + ")";
    }

    private static Map<String, String> matchRelocatedTypes(Set<String> removed, Set<String> added, Map<String, TypeModel> bt,
                                                         Map<String, TypeModel> ht) {
        record Cand(String r, String a, double score) {
        }
        List<Cand> cands = new ArrayList<>();
        for (String r : removed) {
            TypeModel rb = bt.get(r);
            Set<String> rk = memberKeys(rb);
            for (String a : added) {
                TypeModel ah = ht.get(a);
                if (rb.kind() != ah.kind()) {
                    continue;
                }
                Set<String> ak = memberKeys(ah);
                boolean sameName = rb.simpleName().equals(ah.simpleName());
                double sim = jaccard(rk, ak);
                int common = (int) rk.stream().filter(ak::contains).count();
                boolean tiny = Math.max(rk.size(), ak.size()) <= 1;
                if ((sameName && (sim >= 0.5 || tiny || (rk.isEmpty() && ak.isEmpty())))
                        || (!sameName && !tiny && common >= 2 && sim >= 0.75)) {
                    cands.add(new Cand(r, a, sim + (sameName ? 0.5 : 0)));
                }
            }
        }
        cands.sort((x, y) -> Double.compare(y.score(), x.score()));
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> usedA = new HashSet<>();
        for (Cand c : cands) {
            if (!out.containsKey(c.r()) && usedA.add(c.a())) {
                out.put(c.r(), c.a());
            }
        }
        return out;
    }

    private static Set<String> memberKeys(TypeModel t) {
        Set<String> s = new HashSet<>();
        t.methods().forEach(m -> s.add(memberKey(m)));
        t.fields().forEach(f -> s.add("field:" + f.name()));
        return s;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    // ------------------------------------------------------------------ methods

    private static MethodChange methodAdded(MethodModel m) {
        return new MethodChange(m.id(), null, m.typeFqn(), m.name(), m.signature(), null, ChangeKind.ADDED, Nature.NEW,
                null, BehaviorDelta.none(), null, m.startLine(), m.endLine(), 0, 0);
    }

    private static MethodChange methodRemoved(MethodModel m) {
        return new MethodChange(m.id(), m.id(), m.typeFqn(), m.name(), null, m.signature(), ChangeKind.REMOVED, Nature.DELETED,
                null, BehaviorDelta.none(), null, 0, 0, m.startLine(), m.endLine());
    }

    /**
     * Compares two versions of the "same" method. Returns null when nothing changed at all.
     * Order matters: the cheapest, most reassuring explanations are tested first.
     */
    private static MethodChange compare(MethodModel o, MethodModel n, ChangeKind kind) {
        if (o.rawHash().equals(n.rawHash()) && o.typeFqn().equals(n.typeFqn()) && kind == ChangeKind.MODIFIED) {
            return null;
        }
        DeclDelta decl = declDelta(o, n);
        boolean sameBody = o.alphaHash().equals(n.alphaHash());
        Nature nature;
        BehaviorDelta beh = BehaviorDelta.none();
        if (o.normHash().equals(n.normHash())) {
            nature = Nature.COSMETIC;
        } else if (sameBody && decl.isEmpty()) {
            nature = Nature.LOCAL_RENAME;
        } else if (sameBody) {
            nature = Nature.SIGNATURE;
        } else {
            nature = Nature.BEHAVIOR;
            beh = behaviorDelta(o.facts(), n.facts());
        }
        return new MethodChange(n.id(), o.id(), n.typeFqn(), n.name(), n.signature(), o.signature(), kind, nature, decl, beh, null,
                n.startLine(), n.endLine(), o.startLine(), o.endLine());
    }

    private static DeclDelta declDelta(MethodModel o, MethodModel n) {
        return new DeclDelta(
                minus(n.modifiers(), o.modifiers()),
                minus(o.modifiers(), n.modifiers()),
                diffLists(o.annotations(), n.annotations(), true),
                diffLists(n.annotations(), o.annotations(), true),
                o.returnType(), n.returnType(), o.paramTypes(), n.paramTypes());
    }

    private static List<String> minus(Collection<String> a, Collection<String> b) {
        List<String> out = new ArrayList<>(a);
        out.removeAll(b);
        return out;
    }

    /** Multiset-aware list difference: elements of {@code to} that are not (or are fewer times) in {@code from}. */
    private static List<String> diffLists(List<String> from, List<String> to, boolean keepOrder) {
        Map<String, Integer> have = new HashMap<>();
        from.forEach(s -> have.merge(s, 1, Integer::sum));
        List<String> out = new ArrayList<>();
        for (String s : to) {
            int c = have.getOrDefault(s, 0);
            if (c > 0) {
                have.put(s, c - 1);
            } else {
                out.add(s);
            }
        }
        if (!keepOrder) {
            out.sort(String::compareTo);
        }
        return out;
    }

    private static BehaviorDelta behaviorDelta(BehaviorFacts o, BehaviorFacts n) {
        return new BehaviorDelta(
                multisetDiff(n.calls(), o.calls(), CallSite::key),
                multisetDiff(o.calls(), n.calls(), CallSite::key),
                multisetDiff(n.branches(), o.branches(), Branch::key),
                multisetDiff(o.branches(), n.branches(), Branch::key),
                diffLists(o.thrown(), n.thrown(), false), diffLists(n.thrown(), o.thrown(), false),
                diffLists(o.caught(), n.caught(), false), diffLists(n.caught(), o.caught(), false),
                diffLists(o.fieldWrites(), n.fieldWrites(), false), diffLists(n.fieldWrites(), o.fieldWrites(), false),
                diffLists(o.stringLiterals(), n.stringLiterals(), false), diffLists(n.stringLiterals(), o.stringLiterals(), false),
                n.returns() - o.returns());
    }

    private static <T> List<T> multisetDiff(List<T> want, List<T> have, java.util.function.Function<T, String> key) {
        Map<String, Integer> h = new HashMap<>();
        have.forEach(x -> h.merge(key.apply(x), 1, Integer::sum));
        List<T> out = new ArrayList<>();
        for (T x : want) {
            String k = key.apply(x);
            int c = h.getOrDefault(k, 0);
            if (c > 0) {
                h.put(k, c - 1);
            } else {
                out.add(x);
            }
        }
        return out;
    }

    /** Pairs methods that disappeared and appeared inside one type: renames, overload changes, signature changes. */
    private static void pairResidualMethods(Work w, List<MethodModel> rem, List<MethodModel> add) {
        List<MethodModel> r = new ArrayList<>(rem);
        List<MethodModel> a = new ArrayList<>(add);
        // A) identical body (locals erased) and unique on both sides: a rename or a signature change
        Map<String, List<MethodModel>> rByBody = group(r);
        Map<String, List<MethodModel>> aByBody = group(a);
        for (var e : rByBody.entrySet()) {
            List<MethodModel> rl = e.getValue();
            List<MethodModel> al = aByBody.getOrDefault(e.getKey(), List.of());
            if (rl.size() == 1 && al.size() == 1 && meaningful(rl.get(0))) {
                MethodModel o = rl.get(0);
                MethodModel n = al.get(0);
                ChangeKind k = o.name().equals(n.name()) ? ChangeKind.MODIFIED : ChangeKind.RENAMED;
                MethodChange mc = compare(o, n, k);
                w.methods.add(mc != null ? mc : renamedOnly(o, n, k));
                r.remove(o);
                a.remove(n);
            }
        }
        // B) same name, different parameters: an overload whose signature and body changed
        for (MethodModel o : new ArrayList<>(r)) {
            MethodModel best = null;
            double bestScore = 0;
            for (MethodModel n : a) {
                if (n.name().equals(o.name())) {
                    double s = similarity(o, n);
                    if (s > bestScore) {
                        bestScore = s;
                        best = n;
                    }
                }
            }
            if (best != null && bestScore >= 0.5) {
                MethodChange mc = compare(o, best, ChangeKind.MODIFIED);
                if (mc != null) {
                    w.methods.add(mc);
                }
                r.remove(o);
                a.remove(best);
            }
        }
        // C) different name but very similar substantial body: renamed and edited
        for (MethodModel o : new ArrayList<>(r)) {
            if (o.statementCount() < 3) {
                continue;
            }
            MethodModel best = null;
            double bestScore = 0;
            for (MethodModel n : a) {
                double s = similarity(o, n);
                if (s > bestScore && n.statementCount() >= 3) {
                    bestScore = s;
                    best = n;
                }
            }
            if (best != null && bestScore >= 0.8) {
                MethodChange mc = compare(o, best, ChangeKind.RENAMED);
                if (mc != null) {
                    w.methods.add(mc);
                }
                r.remove(o);
                a.remove(best);
            }
        }
        r.forEach(m -> w.methods.add(methodRemoved(m)));
        a.forEach(m -> w.methods.add(methodAdded(m)));
    }

    private static MethodChange renamedOnly(MethodModel o, MethodModel n, ChangeKind k) {
        return new MethodChange(n.id(), o.id(), n.typeFqn(), n.name(), n.signature(), o.signature(), k, Nature.SIGNATURE,
                declDelta(o, n), BehaviorDelta.none(), null, n.startLine(), n.endLine(), o.startLine(), o.endLine());
    }

    private static Map<String, List<MethodModel>> group(List<MethodModel> l) {
        Map<String, List<MethodModel>> m = new LinkedHashMap<>();
        for (MethodModel x : l) {
            if (x.hasBody()) {
                m.computeIfAbsent(x.alphaHash(), k -> new ArrayList<>()).add(x);
            }
        }
        return m;
    }

    /** Trivial bodies (one statement) are too common to prove two methods are the same method. */
    private static boolean meaningful(MethodModel m) {
        return m.hasBody() && m.statementCount() >= 2;
    }

    /** Jaccard over the behaviour facts of two methods. Used only when hashes cannot decide. */
    private static double similarity(MethodModel o, MethodModel n) {
        return jaccard(factSet(o), factSet(n));
    }

    private static Set<String> factSet(MethodModel m) {
        Set<String> s = new HashSet<>();
        BehaviorFacts f = m.facts();
        f.calls().forEach(c -> s.add("c:" + c.key()));
        f.branches().forEach(b -> s.add("b:" + b.key()));
        f.thrown().forEach(t -> s.add("t:" + t));
        f.stringLiterals().forEach(l -> s.add("s:" + l));
        f.fieldWrites().forEach(x -> s.add("w:" + x));
        return s;
    }

    // ------------------------------------------------------------------ cross-type moves

    private static void detectCrossTypeMoves(List<Work> works) {
        record Ref(Work w, MethodChange mc) {
        }
        List<Ref> removed = new ArrayList<>();
        List<Ref> added = new ArrayList<>();
        Map<String, MethodModel> baseModels = new HashMap<>();
        Map<String, MethodModel> headModels = new HashMap<>();
        for (Work w : works) {
            if (w.base != null) {
                w.base.methods().forEach(m -> baseModels.put(m.id(), m));
            }
            if (w.head != null) {
                w.head.methods().forEach(m -> headModels.put(m.id(), m));
            }
            for (MethodChange mc : w.methods) {
                if (mc.kind() == ChangeKind.REMOVED) {
                    removed.add(new Ref(w, mc));
                } else if (mc.kind() == ChangeKind.ADDED) {
                    added.add(new Ref(w, mc));
                }
            }
        }
        Map<String, List<Ref>> remBy = new HashMap<>();
        Map<String, List<Ref>> addBy = new HashMap<>();
        for (Ref r : removed) {
            MethodModel m = baseModels.get(r.mc().id());
            if (m != null && meaningful(m)) {
                remBy.computeIfAbsent(m.alphaHash(), k -> new ArrayList<>()).add(r);
            }
        }
        for (Ref a : added) {
            MethodModel m = headModels.get(a.mc().id());
            if (m != null && meaningful(m)) {
                addBy.computeIfAbsent(m.alphaHash(), k -> new ArrayList<>()).add(a);
            }
        }
        // second chance for a method that moved and was edited too: same name and parameters in another class, similar behaviour
        Map<String, List<Ref>> remByKey = new HashMap<>();
        Map<String, List<Ref>> addByKey = new HashMap<>();
        for (Ref r : removed) {
            MethodModel m = baseModels.get(r.mc().id());
            if (m != null && m.hasBody() && m.statementCount() >= 3) {
                remByKey.computeIfAbsent(memberKey(m), k -> new ArrayList<>()).add(r);
            }
        }
        for (Ref a : added) {
            MethodModel m = headModels.get(a.mc().id());
            if (m != null && m.hasBody() && m.statementCount() >= 3) {
                addByKey.computeIfAbsent(memberKey(m), k -> new ArrayList<>()).add(a);
            }
        }
        for (var e : remByKey.entrySet()) {
            List<Ref> rl = e.getValue();
            List<Ref> al = addByKey.getOrDefault(e.getKey(), List.of());
            if (rl.size() == 1 && al.size() == 1 && rl.get(0).w() != al.get(0).w()
                    && rl.get(0).w().methods.contains(rl.get(0).mc()) && al.get(0).w().methods.contains(al.get(0).mc())) {
                MethodModel o = baseModels.get(rl.get(0).mc().id());
                MethodModel n = headModels.get(al.get(0).mc().id());
                if (!o.alphaHash().equals(n.alphaHash()) && similarity(o, n) >= 0.6) { // identical bodies are handled as exact moves below
                    MethodChange moved = compare(o, n, ChangeKind.MOVED);
                    if (moved != null) {
                        rl.get(0).w().methods.remove(rl.get(0).mc());
                        al.get(0).w().methods.remove(al.get(0).mc());
                        al.get(0).w().methods.add(moved);
                    }
                }
            }
        }
        for (var e : remBy.entrySet()) {
            List<Ref> rl = e.getValue();
            List<Ref> al = addBy.getOrDefault(e.getKey(), List.of());
            if (rl.size() == 1 && al.size() == 1 && rl.get(0).w() != al.get(0).w()) {
                Ref r = rl.get(0);
                Ref a = al.get(0);
                MethodModel o = baseModels.get(r.mc().id());
                MethodModel n = headModels.get(a.mc().id());
                MethodChange moved = new MethodChange(n.id(), o.id(), n.typeFqn(), n.name(), n.signature(), o.signature(),
                        o.name().equals(n.name()) ? ChangeKind.MOVED : ChangeKind.MOVED_RENAMED, Nature.RELOCATED, declDelta(o, n),
                        BehaviorDelta.none(), null, n.startLine(), n.endLine(), o.startLine(), o.endLine());
                r.w().methods.remove(r.mc());
                a.w().methods.remove(a.mc());
                a.w().methods.add(moved);
            }
        }
    }

    // ------------------------------------------------------------------ results

    private static TypeChange finish(Work w) {
        TypeModel ref = w.head != null ? w.head : w.base;
        List<MethodChange> methods = new ArrayList<>();
        for (MethodChange mc : w.methods) {
            methods.add(new MethodChange(mc.id(), mc.oldId(), mc.typeFqn(), mc.name(), mc.signature(), mc.oldSignature(), mc.kind(),
                    mc.nature(), mc.declaration(), mc.behavior(), ref.filePath(), mc.startLine(), mc.endLine(), mc.oldStartLine(),
                    mc.oldEndLine()));
        }
        methods.sort((a, b) -> Integer.compare(a.startLine() == 0 ? a.oldStartLine() : a.startLine(),
                b.startLine() == 0 ? b.oldStartLine() : b.startLine()));
        Nature nature = typeNature(w, methods);
        return new TypeChange(ref.fqn(), w.base != null ? w.base.fqn() : null, ref.simpleName(), ref.packageName(), w.kind, nature,
                ref.layer(), ref.filePath(), diffLists(w.base == null ? List.of() : w.base.annotations(), w.head == null ? List.of() : w.head.annotations(), true),
                diffLists(w.head == null ? List.of() : w.head.annotations(), w.base == null ? List.of() : w.base.annotations(), true),
                diffLists(w.base == null ? List.of() : superOf(w.base), w.head == null ? List.of() : superOf(w.head), true),
                diffLists(w.head == null ? List.of() : superOf(w.head), w.base == null ? List.of() : superOf(w.base), true),
                List.copyOf(w.fields), List.copyOf(methods), w.unchanged);
    }

    private static Nature typeNature(Work w, List<MethodChange> methods) {
        if (w.kind == ChangeKind.ADDED) {
            return Nature.NEW;
        }
        if (w.kind == ChangeKind.REMOVED) {
            return Nature.DELETED;
        }
        boolean behavior = methods.stream().anyMatch(m -> m.nature() == Nature.BEHAVIOR || m.nature() == Nature.NEW
                || m.nature() == Nature.DELETED);
        if (behavior || !w.fields.isEmpty()) {
            return Nature.BEHAVIOR;
        }
        if (methods.stream().anyMatch(m -> m.nature() == Nature.SIGNATURE) || !annDelta(w).isEmpty() || !superDelta(w).isEmpty()) {
            return Nature.SIGNATURE;
        }
        if (w.kind != ChangeKind.MODIFIED) {
            return Nature.RELOCATED;
        }
        if (methods.stream().anyMatch(m -> m.nature() == Nature.LOCAL_RENAME)) {
            return Nature.LOCAL_RENAME;
        }
        return Nature.COSMETIC;
    }

    private static List<PackageChange> packages(List<TypeChange> types) {
        Map<String, int[]> acc = new TreeMap<>();
        for (TypeChange t : types) {
            int[] a = acc.computeIfAbsent(t.packageName(), k -> new int[7]);
            switch (t.kind()) {
                case ADDED -> a[0]++;
                case REMOVED -> a[1]++;
                case MODIFIED -> a[2]++;
                default -> a[3]++;
            }
            a[4] += (int) t.methods().stream().filter(m -> m.nature() == Nature.BEHAVIOR || m.nature() == Nature.NEW
                    || m.nature() == Nature.DELETED).count();
            a[5] += t.methods().size();
        }
        List<PackageChange> out = new ArrayList<>();
        acc.forEach((k, a) -> out.add(new PackageChange(k, a[0], a[1], a[2], a[3], a[4], a[5])));
        return out;
    }

    private static Summary summary(List<TypeChange> types) {
        int ta = 0, tr = 0, tm = 0, tl = 0, ma = 0, mr = 0, mb = 0, ms = 0, ml = 0, mlr = 0, mc = 0;
        Set<String> files = new HashSet<>();
        for (TypeChange t : types) {
            files.add(t.filePath());
            switch (t.kind()) {
                case ADDED -> ta++;
                case REMOVED -> tr++;
                case MODIFIED -> tm++;
                default -> tl++;
            }
            for (MethodChange m : t.methods()) {
                switch (m.nature()) {
                    case NEW -> ma++;
                    case DELETED -> mr++;
                    case BEHAVIOR -> mb++;
                    case SIGNATURE -> ms++;
                    case RELOCATED -> ml++;
                    case LOCAL_RENAME -> mlr++;
                    case COSMETIC -> mc++;
                }
            }
        }
        return new Summary(files.size(), ta, tr, tm, tl, ma, mr, mb, ms, ml, mlr, mc);
    }
}
