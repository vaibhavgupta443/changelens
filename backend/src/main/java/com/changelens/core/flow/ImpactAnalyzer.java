package com.changelens.core.flow;

import com.changelens.core.diff.ChangeModel.ChangeKind;
import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.ChangeModel.MethodChange;
import com.changelens.core.diff.ChangeModel.Nature;
import com.changelens.core.diff.ChangeModel.TypeChange;
import com.changelens.core.flow.FlowModel.BlastRadius;
import com.changelens.core.flow.FlowModel.Edge;
import com.changelens.core.flow.FlowModel.EntryPoint;
import com.changelens.core.flow.FlowModel.FlowImpact;
import com.changelens.core.flow.FlowModel.ImpactReport;
import com.changelens.core.flow.FlowModel.Reached;
import com.changelens.core.model.CodeModel.CallSite;
import com.changelens.core.model.CodeModel.Layer;
import com.changelens.core.model.CodeModel.MethodModel;
import com.changelens.core.model.CodeModel.Resolution;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.model.CodeModel.TypeModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers the questions a diff cannot: which existing features run through the changed code, who else depends on it, and
 * whether any test reaches it. Works on the head snapshot's call graph.
 */
public final class ImpactAnalyzer {

    private static final int MAX_DEPTH = 14;

    private ImpactAnalyzer() {
    }

    public static ImpactReport analyze(Snapshot head, ChangeSet changes) {
        CallGraph graph = new CallGraph(head);
        Set<String> changed = changedMethodIds(changes, head);
        List<EntryPoint> entries = SpringEntryPoints.find(head);
        Map<String, List<String>> httpTests = testsByEndpoint(head, entries);

        List<FlowImpact> flows = new ArrayList<>();
        for (EntryPoint e : entries) {
            Map<String, List<String>> hits = graph.shortestPathsToTargets(e.methodId(), changed, MAX_DEPTH);
            if (hits.isEmpty()) {
                continue;
            }
            List<Reached> reached = new ArrayList<>();
            for (var h : hits.entrySet()) {
                List<String> path = h.getValue();
                boolean viaIface = false;
                for (int i = 0; i + 1 < path.size(); i++) {
                    for (Edge ed : graph.callees(path.get(i))) {
                        if (ed.to().equals(path.get(i + 1)) && ed.viaInterface()) {
                            viaIface = true;
                        }
                    }
                }
                reached.add(new Reached(h.getKey(), path.size() - 1, List.copyOf(path), viaIface));
            }
            reached.sort(Comparator.comparingInt(Reached::depth).thenComparing(Reached::methodId));
            flows.add(new FlowImpact(e, changed.contains(e.methodId()), List.copyOf(reached)));
        }
        flows.sort(Comparator.comparing((FlowImpact f) -> f.entry().label()));

        List<BlastRadius> blast = new ArrayList<>();
        List<String> untested = new ArrayList<>();
        for (String id : changed) {
            MethodModel m = graph.method(id);
            if (m == null) {
                continue;
            }
            TypeModel t = graph.typeOf(id);
            if (t != null && t.layer() == Layer.TEST) {
                continue; // tests changing is not a production risk
            }
            List<String> callers = new ArrayList<>();
            for (Edge e : graph.callers(id)) {
                TypeModel ct = graph.typeOf(e.from());
                if (!changed.contains(e.from()) && ct != null && ct.layer() != Layer.TEST) {
                    callers.add(e.from());
                }
            }
            List<String> tests = coveringTests(graph, id, httpTests);
            if (tests.isEmpty()) {
                untested.add(id);
            }
            if (!callers.isEmpty() || !tests.isEmpty()) {
                blast.add(new BlastRadius(id, callers, tests));
            }
        }
        blast.sort(Comparator.comparing(BlastRadius::changedMethod));
        untested.sort(String::compareTo);
        return new ImpactReport(List.copyOf(flows), List.copyOf(blast), List.copyOf(untested), resolvedRatio(head));
    }

    /** Methods whose runtime behaviour changed or that are new: the ones worth tracing. */
    static Set<String> changedMethodIds(ChangeSet cs, Snapshot head) {
        Set<String> ids = new LinkedHashSet<>();
        for (TypeChange t : cs.types()) {
            if (t.layer() == Layer.TEST) {
                // keep tests that changed out of the "changed production code" set
                continue;
            }
            for (MethodChange m : t.methods()) {
                boolean relevant = m.nature() == Nature.BEHAVIOR || m.nature() == Nature.NEW || m.nature() == Nature.SIGNATURE
                        || (m.kind() == ChangeKind.RENAMED && m.nature() != Nature.COSMETIC);
                if (relevant && m.kind() != ChangeKind.REMOVED && head.types().containsKey(t.fqn())) {
                    ids.add(m.id());
                }
            }
        }
        return ids;
    }

    private static List<String> coveringTests(CallGraph g, String target, Map<String, List<String>> httpTests) {
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        Map<String, Integer> depth = new java.util.HashMap<>();
        q.add(target);
        depth.put(target, 0);
        seen.add(target);
        List<String> tests = new ArrayList<>();
        while (!q.isEmpty()) {
            String cur = q.poll();
            int d = depth.get(cur);
            TypeModel t = g.typeOf(cur);
            if (t != null && t.layer() == Layer.TEST) {
                MethodModel m = g.method(cur);
                if (m != null && m.annotations().stream().anyMatch(a -> a.startsWith("@Test") || a.startsWith("@ParameterizedTest")
                        || a.startsWith("@RepeatedTest"))) {
                    tests.add(cur);
                }
                continue;
            }
            if (d >= 8) {
                continue;
            }
            for (Edge e : g.callers(cur)) {
                if (seen.add(e.from())) {
                    depth.put(e.from(), d + 1);
                    q.add(e.from());
                }
            }
            for (String testId : httpTests.getOrDefault(cur, List.of())) { // a test that calls this endpoint over HTTP
                if (seen.add(testId)) {
                    depth.put(testId, d + 1);
                    q.add(testId);
                }
            }
        }
        tests.sort(String::compareTo);
        return tests;
    }

    /**
     * Tests that drive an endpoint over HTTP (MockMvc, WebTestClient, TestRestTemplate) never call the handler method, so a
     * call graph cannot see them. Their URL string constants give them away: a literal equal to an endpoint path (with
     * {placeholders} matching any segment) counts as the test reaching that endpoint's handler.
     */
    private static Map<String, List<String>> testsByEndpoint(Snapshot head, List<EntryPoint> entries) {
        Map<String, List<String>> out = new java.util.HashMap<>();
        record Ep(java.util.regex.Pattern rx, String handler) {
        }
        List<Ep> eps = new ArrayList<>();
        for (EntryPoint e : entries) {
            if (e.kind() != FlowModel.EntryKind.HTTP) {
                continue;
            }
            String path = e.label().substring(e.label().indexOf(' ') + 1);
            eps.add(new Ep(pathPattern(path), e.methodId()));
        }
        for (TypeModel t : head.types().values()) {
            if (t.layer() != Layer.TEST) {
                continue;
            }
            for (MethodModel m : t.methods()) {
                if (m.annotations().stream().noneMatch(a -> a.startsWith("@Test") || a.startsWith("@ParameterizedTest") || a.startsWith("@RepeatedTest"))) {
                    continue;
                }
                for (String lit : m.facts().stringLiterals()) {
                    String p = lit.contains("?") ? lit.substring(0, lit.indexOf('?')) : lit;
                    if (!p.startsWith("/")) {
                        continue;
                    }
                    for (Ep ep : eps) {
                        if (ep.rx().matcher(p).matches()) {
                            out.computeIfAbsent(ep.handler(), k -> new ArrayList<>()).add(m.id());
                        }
                    }
                }
            }
        }
        return out;
    }

    /** "/owners/{id}/pets" becomes a pattern that matches "/owners/7/pets" and also the literal "/owners/{id}/pets". */
    static java.util.regex.Pattern pathPattern(String path) {
        StringBuilder rx = new StringBuilder("^");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{[^/}]+}").matcher(path);
        int last = 0;
        while (m.find()) {
            rx.append(java.util.regex.Pattern.quote(path.substring(last, m.start()))).append("[^/]+");
            last = m.end();
        }
        rx.append(java.util.regex.Pattern.quote(path.substring(last))).append("$");
        return java.util.regex.Pattern.compile(rx.toString());
    }

    private static double resolvedRatio(Snapshot s) {
        long total = 0;
        long repo = 0;
        for (TypeModel t : s.types().values()) {
            for (MethodModel m : t.methods()) {
                for (CallSite c : m.facts().calls()) {
                    total++;
                    if (c.resolution() == Resolution.REPO) {
                        repo++;
                    }
                }
            }
        }
        return total == 0 ? 0 : (double) repo / total;
    }
}
