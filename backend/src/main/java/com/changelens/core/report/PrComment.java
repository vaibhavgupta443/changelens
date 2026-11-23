package com.changelens.core.report;

import com.changelens.core.diff.ChangeModel.BehaviorDelta;
import com.changelens.core.diff.ChangeModel.ChangeKind;
import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.ChangeModel.MethodChange;
import com.changelens.core.diff.ChangeModel.Nature;
import com.changelens.core.diff.ChangeModel.TypeChange;
import com.changelens.core.flow.FlowModel.FlowImpact;
import com.changelens.core.flow.FlowModel.ImpactReport;
import com.changelens.core.flow.FlowModel.Reached;
import com.changelens.core.model.CodeModel.CallSite;
import com.changelens.core.model.CodeModel.Layer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The comment ChangeLens posts on a pull request: short at the top (what to read first, which features are touched, a
 * diagram), and everything else folded away. GitHub renders the Mermaid block as a diagram inside the comment.
 */
public final class PrComment {

    public static final String MARKER = "<!-- changelens-report -->";

    private PrComment() {
    }

    public static String render(ChangeSet cs, ImpactReport impact, int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER).append('\n');
        sb.append("## ChangeLens: what this pull request does\n\n");
        var s = cs.summary();
        if (cs.types().isEmpty()) {
            sb.append("No Java code changes found in this pull request.\n");
            return sb.toString();
        }
        sb.append("**").append(s.filesChanged()).append(" Java files** | ").append(s.typesAdded()).append(" new classes, ")
                .append(s.typesRemoved()).append(" removed, ").append(s.typesModified()).append(" changed, ").append(s.typesRelocated())
                .append(" moved or renamed | **").append(s.methodsBehaviorChanged() + s.methodsAdded()).append(" methods with new or changed logic**")
                .append(" | ").append(s.methodsRelocated() + s.methodsLocalRenameOnly() + s.methodsCosmeticOnly()).append(" safe to skim\n\n");

        List<Ranked> top = rankedBehavior(cs);
        sb.append("### Read these first\n\n");
        if (top.isEmpty()) {
            sb.append("No method has new or changed logic. This change is moves, renames, formatting or declarations only.\n\n");
        } else {
            int n = 0;
            for (Ranked r : top) {
                if (++n > 8) {
                    sb.append("- ...and ").append(top.size() - 8).append(" more in the full list below\n");
                    break;
                }
                sb.append(n).append(". `").append(simple(r.mc().typeFqn())).append('.').append(label(r.mc())).append("`");
                String why = why(r.mc());
                if (!why.isEmpty()) {
                    sb.append(": ").append(why);
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        if (!impact.flows().isEmpty()) {
            sb.append("### Existing features this change runs through\n\n");
            int n = 0;
            for (FlowImpact f : impact.flows()) {
                if (++n > 10) {
                    sb.append("- ...and ").append(impact.flows().size() - 10).append(" more\n");
                    break;
                }
                Reached r = f.reached().get(0);
                sb.append("- **`").append(f.entry().label()).append("`** reaches `")
                        .append(chain(r.path())).append('`').append(r.viaInterface() ? " (through an interface, so it depends on which implementation runs)" : "")
                        .append(f.entryItselfChanged() ? " (the endpoint method itself changed)" : "").append('\n');
            }
            sb.append('\n');
        } else {
            sb.append("### Existing features this change runs through\n\nNo HTTP endpoint, listener or scheduled job reaches the changed code through calls ChangeLens could resolve.\n\n");
        }

        String diagram = MermaidView.render(cs, impact);
        if (!diagram.isEmpty()) {
            sb.append("### Map\n\n```mermaid\n").append(diagram).append("```\n\n");
        }

        if (!impact.blast().isEmpty()) {
            sb.append("<details><summary>Unchanged code that depends on changed code (").append(impact.blast().size()).append(")</summary>\n\n");
            for (var b : impact.blast()) {
                if (b.unchangedCallers().isEmpty()) {
                    continue;
                }
                sb.append("- `").append(shortId(b.changedMethod())).append("` is called by ")
                        .append(b.unchangedCallers().stream().limit(5).map(c -> "`" + shortId(c) + "`").collect(Collectors.joining(", ")))
                        .append(b.unchangedCallers().size() > 5 ? ", ..." : "").append('\n');
            }
            sb.append("\n</details>\n\n");
        }
        if (!impact.changedWithoutTests().isEmpty()) {
            sb.append("<details><summary>Changed code no test reaches (").append(impact.changedWithoutTests().size()).append(")</summary>\n\n");
            impact.changedWithoutTests().stream().limit(20).forEach(id -> sb.append("- `").append(shortId(id)).append("`\n"));
            sb.append("\nThis is a static check: a test that reaches the code only through reflection or an unresolved call is not counted.\n\n</details>\n\n");
        }

        sb.append("<details><summary>All changes by package, class and method</summary>\n\n");
        sb.append(MarkdownReport.render(cs).replaceFirst("(?s)^## [^\\n]*\\n\\n", ""));
        sb.append("\n</details>\n\n");

        sb.append("<sub>ChangeLens reads the code with a Java parser and does not run it. About ")
                .append(Math.round(impact.resolvedCallRatio() * 100)).append("% of calls were traced to code in this repository; the rest are library calls or could not be resolved (reflection, lambdas, chained calls), so a feature can be affected without being listed. ")
                .append("Moves, renames and formatting are reported as such, but not proven to preserve behaviour.</sub>\n");

        String out = sb.toString();
        if (out.length() > maxChars) {
            int cut = out.lastIndexOf('\n', maxChars - 400);
            out = out.substring(0, Math.max(0, cut)) + "\n\n*(report shortened to fit the comment limit; the full report is in the job summary)*\n";
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private record Ranked(MethodChange mc, int score) {
    }

    private static List<Ranked> rankedBehavior(ChangeSet cs) {
        List<Ranked> out = new ArrayList<>();
        for (TypeChange t : cs.types()) {
            if (t.layer() == Layer.TEST) {
                continue;
            }
            for (MethodChange m : t.methods()) {
                if (m.nature() == Nature.BEHAVIOR || (m.nature() == Nature.NEW && t.kind() != ChangeKind.ADDED)
                        || (m.nature() == Nature.SIGNATURE && m.declaration() != null
                        && (!m.declaration().annotationsAdded().isEmpty() || !m.declaration().annotationsRemoved().isEmpty()))) {
                    out.add(new Ranked(m, score(m, t)));
                }
            }
        }
        out.sort(Comparator.comparingInt(Ranked::score).reversed().thenComparing(r -> r.mc().id()));
        return out;
    }

    private static int score(MethodChange m, TypeChange t) {
        int s = 1;
        BehaviorDelta b = m.behavior();
        if (b != null) {
            s += 3 * b.branchesAdded().size() + 2 * b.branchesRemoved().size() + 2 * b.thrownAdded().size() + b.callsAdded().size()
                    + b.callsRemoved().size() + 2 * b.fieldWritesAdded().size();
        }
        if (m.declaration() != null) {
            s += 3 * (m.declaration().annotationsAdded().size() + m.declaration().annotationsRemoved().size());
        }
        if (t.layer() == Layer.CONTROLLER || t.layer() == Layer.SERVICE || t.layer() == Layer.REPOSITORY) {
            s += 2;
        }
        return s;
    }

    static String why(MethodChange m) {
        List<String> parts = new ArrayList<>();
        if (m.nature() == Nature.NEW) {
            parts.add("new method");
        }
        BehaviorDelta b = m.behavior();
        if (b != null) {
            b.branchesAdded().stream().limit(2).forEach(x -> parts.add("new " + x.kind() + (x.condition().isEmpty() ? "" : " `" + trim(x.condition()) + "`")));
            if (!b.branchesRemoved().isEmpty()) {
                parts.add(b.branchesRemoved().size() + " branch(es) removed");
            }
            if (!b.thrownAdded().isEmpty()) {
                parts.add("now throws " + String.join(", ", new LinkedHashSet<>(b.thrownAdded())));
            }
            if (!b.thrownRemoved().isEmpty()) {
                parts.add("no longer throws " + String.join(", ", new LinkedHashSet<>(b.thrownRemoved())));
            }
            if (!b.callsAdded().isEmpty()) {
                parts.add("now calls " + calls(b.callsAdded()));
            }
            if (!b.callsRemoved().isEmpty()) {
                parts.add("no longer calls " + calls(b.callsRemoved()));
            }
            if (!b.fieldWritesAdded().isEmpty()) {
                parts.add("writes " + String.join(", ", b.fieldWritesAdded()));
            }
        }
        if (m.declaration() != null) {
            m.declaration().annotationsAdded().forEach(a -> parts.add("added `" + trim(a) + "`"));
            m.declaration().annotationsRemoved().forEach(a -> parts.add("removed `" + trim(a) + "`"));
        }
        return String.join("; ", parts.stream().limit(5).toList());
    }

    private static String calls(List<CallSite> cs) {
        return cs.stream().limit(3).map(c -> "`" + callLabel(c) + "`").distinct().collect(Collectors.joining(", "))
                + (cs.size() > 3 ? ", ..." : "");
    }

    /** Type.method when the receiver is known, just method() when it could not be resolved, new Type(...) for constructors. */
    public static String callLabel(CallSite c) {
        if ("<init>".equals(c.targetMethod())) {
            return "new " + simple(c.targetType()) + "(...)";
        }
        boolean unknown = c.targetType() == null || c.targetType().equals("?") || c.targetType().equals("super");
        return unknown ? c.targetMethod() + "()" : simple(c.targetType()) + "." + c.targetMethod();
    }

    private static String trim(String s) {
        String t = s.replace('`', '\'');
        return t.length() > 60 ? t.substring(0, 57) + "..." : t;
    }

    static String label(MethodChange m) {
        String sig = m.signature() != null ? m.signature() : m.oldSignature();
        int open = sig.indexOf('(');
        return open < 0 ? sig : sig.substring(sig.lastIndexOf(' ', open) + 1);
    }

    static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    static String shortId(String id) {
        int hash = id.indexOf('#');
        String type = simple(id.substring(0, hash < 0 ? id.length() : hash));
        String rest = hash < 0 ? "" : id.substring(hash + 1);
        int paren = rest.indexOf('(');
        return type + "." + (paren < 0 ? rest : rest.substring(0, paren));
    }

    private static String chain(List<String> path) {
        return path.stream().map(PrComment::shortId).collect(Collectors.joining(" -> "));
    }

    @SuppressWarnings("unused")
    private static Map<String, List<TypeChange>> byPackage(ChangeSet cs) {
        Map<String, List<TypeChange>> m = new LinkedHashMap<>();
        cs.types().forEach(t -> m.computeIfAbsent(t.packageName(), k -> new ArrayList<>()).add(t));
        return m;
    }

    @SuppressWarnings("unused")
    private static Set<String> unused() {
        return Set.of();
    }
}
