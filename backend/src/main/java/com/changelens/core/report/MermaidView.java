package com.changelens.core.report;

import com.changelens.core.diff.ChangeModel.ChangeKind;
import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.ChangeModel.Nature;
import com.changelens.core.diff.ChangeModel.TypeChange;
import com.changelens.core.flow.FlowModel.FlowImpact;
import com.changelens.core.flow.FlowModel.ImpactReport;
import com.changelens.core.flow.FlowModel.Reached;
import com.changelens.core.model.CodeModel.Layer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Mermaid flowchart of the change: changed classes grouped by package and coloured by what happened to them, with the
 * entry points that reach them. GitHub draws Mermaid blocks inside comments, so no extra tool is needed to see it.
 */
public final class MermaidView {

    private static final int MAX_TYPES = 22;
    private static final int MAX_FLOWS = 5;

    private MermaidView() {
    }

    public static String render(ChangeSet cs, ImpactReport impact) {
        List<TypeChange> shown = new ArrayList<>();
        for (TypeChange t : cs.types()) {
            if (t.layer() != Layer.TEST && t.nature() != Nature.COSMETIC) {
                shown.add(t);
            }
        }
        if (shown.isEmpty()) {
            return "";
        }
        shown.sort(Comparator.comparingInt((TypeChange t) -> rank(t)).thenComparing(TypeChange::fqn));
        final int eligible = shown.size();
        if (shown.size() > MAX_TYPES) {
            shown = new ArrayList<>(shown.subList(0, MAX_TYPES));
        }
        Map<String, String> ids = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder("flowchart LR\n");
        Map<String, List<TypeChange>> byPkg = new LinkedHashMap<>();
        for (TypeChange t : shown) {
            byPkg.computeIfAbsent(t.packageName(), k -> new ArrayList<>()).add(t);
        }
        int n = 0;
        int p = 0;
        for (var e : byPkg.entrySet()) {
            sb.append("  subgraph P").append(++p).append("[\"").append(esc(e.getKey().isEmpty() ? "(default)" : lastTwo(e.getKey()))).append("\"]\n");
            for (TypeChange t : e.getValue()) {
                String id = "T" + (++n);
                ids.put(t.fqn(), id);
                sb.append("    ").append(id).append("[\"").append(esc(t.simpleName())).append("<br/>").append(sub(t)).append("\"]:::")
                        .append(style(t)).append('\n');
            }
            sb.append("  end\n");
        }
        // entry points and the type-to-type steps of the flows that reach changed code
        Set<String> edges = new LinkedHashSet<>();
        int f = 0;
        for (FlowImpact fi : impact.flows()) {
            if (f >= MAX_FLOWS) {
                break;
            }
            Reached r = fi.reached().get(0);
            String eid = "E" + (++f);
            sb.append("  ").append(eid).append("([\"").append(esc(fi.entry().label())).append("\"]):::entry\n");
            String prev = eid;
            String prevType = null;
            for (String mid : r.path()) {
                String typeFqn = mid.substring(0, mid.indexOf('#'));
                if (typeFqn.equals(prevType)) {
                    continue;
                }
                String tid = ids.get(typeFqn);
                if (tid == null) {
                    tid = "C" + (++n);
                    ids.put(typeFqn, tid);
                    sb.append("  ").append(tid).append("[\"").append(esc(PrComment.simple(typeFqn))).append("\"]:::ctx\n");
                }
                edges.add("  " + prev + " --> " + tid + "\n");
                prev = tid;
                prevType = typeFqn;
            }
        }
        edges.forEach(sb::append);
        sb.append("  classDef added fill:#d4f5dd,stroke:#2e8b57,color:#111;\n");
        sb.append("  classDef removed fill:#ffd9d9,stroke:#c0392b,color:#111;\n");
        sb.append("  classDef changed fill:#fff1c2,stroke:#d4a017,color:#111;\n");
        sb.append("  classDef moved fill:#d9e8ff,stroke:#3b6fd4,color:#111;\n");
        sb.append("  classDef quiet fill:#eeeeee,stroke:#999,color:#111;\n");
        sb.append("  classDef ctx fill:#ffffff,stroke:#999,stroke-dasharray:3 3,color:#111;\n");
        sb.append("  classDef entry fill:#e8e0ff,stroke:#6a4fd4,color:#111;\n");
        if (eligible > shown.size()) {
            sb.append("  %% ").append(eligible - shown.size()).append(" more changed types not drawn\n");
        }
        return sb.toString();
    }

    private static int rank(TypeChange t) {
        return switch (t.nature()) {
            case BEHAVIOR, NEW, DELETED -> 0;
            case SIGNATURE -> 1;
            case RELOCATED -> 2;
            default -> 3;
        };
    }

    private static String style(TypeChange t) {
        if (t.kind() == ChangeKind.ADDED) {
            return "added";
        }
        if (t.kind() == ChangeKind.REMOVED) {
            return "removed";
        }
        if (t.kind() == ChangeKind.MOVED || t.kind() == ChangeKind.RENAMED || t.kind() == ChangeKind.MOVED_RENAMED) {
            return "moved";
        }
        return t.nature() == Nature.BEHAVIOR ? "changed" : "quiet";
    }

    private static String sub(TypeChange t) {
        long logic = t.methods().stream().filter(m -> m.nature() == Nature.BEHAVIOR || m.nature() == Nature.NEW).count();
        String layer = t.layer().name().toLowerCase();
        return switch (style(t)) {
            case "added" -> "new " + layer;
            case "removed" -> "deleted";
            case "moved" -> "moved";
            default -> logic > 0 ? logic + " logic change" + (logic > 1 ? "s" : "") : layer;
        };
    }

    private static String lastTwo(String pkg) {
        String[] parts = pkg.split("\\.");
        return parts.length <= 2 ? pkg : parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static String esc(String s) {
        return s.replace("\"", "'").replace("[", "(").replace("]", ")").replace("{", "(").replace("}", ")");
    }
}
