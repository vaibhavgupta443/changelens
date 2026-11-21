package com.changelens.core.flow;

import com.changelens.core.flow.FlowModel.Edge;
import com.changelens.core.model.CodeModel.CallSite;
import com.changelens.core.model.CodeModel.MethodModel;
import com.changelens.core.model.CodeModel.Resolution;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.model.CodeModel.TypeKind;
import com.changelens.core.model.CodeModel.TypeModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who calls whom, built from the resolved call sites of a snapshot.
 *
 * <p>Only calls resolved to code in this repository become edges. A call on an interface (or an abstract method) is expanded
 * to every in-repo implementation and marked {@code viaInterface}, because at run time any of them may run.
 */
public final class CallGraph {

    private final Map<String, MethodModel> methods = new HashMap<>();
    private final Map<String, List<Edge>> out = new HashMap<>();
    private final Map<String, List<Edge>> in = new HashMap<>();
    private final Map<String, TypeModel> types;
    private int edgeCount;

    public CallGraph(Snapshot s) {
        this.types = s.types();
        Map<String, List<MethodModel>> byType = new HashMap<>();
        for (TypeModel t : s.types().values()) {
            for (MethodModel m : t.methods()) {
                methods.put(m.id(), m);
                byType.computeIfAbsent(t.fqn(), k -> new ArrayList<>()).add(m);
            }
        }
        Map<String, Set<String>> implementers = implementers(s);
        for (TypeModel t : s.types().values()) {
            for (MethodModel caller : t.methods()) {
                for (CallSite c : caller.facts().calls()) {
                    if (c.resolution() != Resolution.REPO) {
                        continue;
                    }
                    for (Target tg : targets(c, byType, implementers)) {
                        if (!tg.methodId().equals(caller.id())) {
                            add(new Edge(caller.id(), tg.methodId(), tg.viaInterface(), tg.ambiguous()));
                        }
                    }
                }
            }
        }
    }

    private record Target(String methodId, boolean viaInterface, boolean ambiguous) {
    }

    private List<Target> targets(CallSite c, Map<String, List<MethodModel>> byType, Map<String, Set<String>> implementers) {
        List<Target> res = new ArrayList<>();
        List<MethodModel> declared = matching(byType.get(c.targetType()), c);
        boolean ambiguous = declared.size() > 1;
        for (MethodModel m : declared) {
            res.add(new Target(m.id(), false, ambiguous));
        }
        TypeModel owner = types.get(c.targetType());
        boolean abstractTarget = owner != null && (owner.kind() == TypeKind.INTERFACE || owner.modifiers().contains("abstract")
                || declared.stream().anyMatch(m -> !m.hasBody()));
        if (abstractTarget || declared.isEmpty()) {
            for (String impl : implementers.getOrDefault(c.targetType(), Set.of())) {
                List<MethodModel> found = matching(byType.get(impl), c);
                for (MethodModel m : found) {
                    res.add(new Target(m.id(), true, found.size() > 1));
                }
            }
        }
        return res;
    }

    private static List<MethodModel> matching(List<MethodModel> list, CallSite c) {
        List<MethodModel> res = new ArrayList<>();
        if (list == null) {
            return res;
        }
        for (MethodModel m : list) {
            if (m.name().equals(c.targetMethod()) && m.paramTypes().size() == c.argCount()) {
                res.add(m);
            }
        }
        return res;
    }

    /** For every in-repo type, all (transitive) in-repo subtypes that implement or extend it. */
    private static Map<String, Set<String>> implementers(Snapshot s) {
        Map<String, Set<String>> direct = new HashMap<>();
        Map<String, List<String>> simpleToFqn = new HashMap<>();
        for (TypeModel t : s.types().values()) {
            simpleToFqn.computeIfAbsent(t.simpleName(), k -> new ArrayList<>()).add(t.fqn());
        }
        for (TypeModel t : s.types().values()) {
            List<String> supers = new ArrayList<>(t.extendsTypes());
            supers.addAll(t.implementsTypes());
            for (String raw : supers) {
                String simple = raw.replaceAll("<.*>", "").trim();
                simple = simple.substring(simple.lastIndexOf('.') + 1);
                List<String> candidates = simpleToFqn.getOrDefault(simple, List.of());
                if (candidates.size() == 1) {
                    direct.computeIfAbsent(candidates.get(0), k -> new LinkedHashSet<>()).add(t.fqn());
                }
            }
        }
        Map<String, Set<String>> closure = new HashMap<>();
        for (String root : direct.keySet()) {
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> q = new ArrayDeque<>(direct.get(root));
            while (!q.isEmpty()) {
                String x = q.poll();
                if (seen.add(x)) {
                    q.addAll(direct.getOrDefault(x, Set.of()));
                }
            }
            closure.put(root, seen);
        }
        return closure;
    }

    private void add(Edge e) {
        List<Edge> list = out.computeIfAbsent(e.from(), k -> new ArrayList<>());
        for (Edge x : list) {
            if (x.to().equals(e.to())) {
                return;
            }
        }
        list.add(e);
        in.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
        edgeCount++;
    }

    public List<Edge> callees(String methodId) {
        return out.getOrDefault(methodId, List.of());
    }

    public List<Edge> callers(String methodId) {
        return in.getOrDefault(methodId, List.of());
    }

    public MethodModel method(String id) {
        return methods.get(id);
    }

    public int edgeCount() {
        return edgeCount;
    }

    public int methodCount() {
        return methods.size();
    }

    public TypeModel typeOf(String methodId) {
        MethodModel m = methods.get(methodId);
        return m == null ? null : types.get(m.typeFqn());
    }

    /** Shortest path (fewest calls) from {@code start} to any id in {@code targets}, with the nearest target and path. */
    public Map<String, List<String>> shortestPathsToTargets(String start, Set<String> targets, int maxDepth) {
        Map<String, String> parent = new HashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(start);
        depth.put(start, 0);
        Map<String, List<String>> found = new HashMap<>();
        Set<String> visited = new HashSet<>();
        visited.add(start);
        while (!q.isEmpty()) {
            String cur = q.poll();
            int d = depth.get(cur);
            if (targets.contains(cur)) {
                List<String> path = new ArrayList<>();
                for (String x = cur; x != null; x = parent.get(x)) {
                    path.add(0, x);
                }
                found.put(cur, path);
            }
            if (d >= maxDepth) {
                continue;
            }
            for (Edge e : callees(cur)) {
                if (visited.add(e.to())) {
                    parent.put(e.to(), cur);
                    depth.put(e.to(), d + 1);
                    q.add(e.to());
                }
            }
        }
        return found;
    }
}
