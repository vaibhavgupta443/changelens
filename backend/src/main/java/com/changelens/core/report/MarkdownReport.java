package com.changelens.core.report;

import com.changelens.core.diff.ChangeModel.BehaviorDelta;
import com.changelens.core.diff.ChangeModel.ChangeKind;
import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.ChangeModel.MethodChange;
import com.changelens.core.diff.ChangeModel.Nature;
import com.changelens.core.diff.ChangeModel.PackageChange;
import com.changelens.core.diff.ChangeModel.Summary;
import com.changelens.core.diff.ChangeModel.TypeChange;
import com.changelens.core.model.CodeModel.CallSite;
import java.util.List;
import java.util.stream.Collectors;

/** A plain-text account of a change set, from the whole PR down to individual methods. Used for PR comments and the CLI. */
public final class MarkdownReport {

    private MarkdownReport() {
    }

    public static String render(ChangeSet cs) {
        StringBuilder sb = new StringBuilder();
        Summary s = cs.summary();
        sb.append("## ChangeLens: ").append(cs.repo()).append(' ').append(cs.baseRef()).append("..").append(cs.headRef()).append("\n\n");
        sb.append("**Level 1: the whole change**\n\n");
        sb.append("- ").append(s.filesChanged()).append(" Java files affected: ")
                .append(s.typesAdded()).append(" types added, ").append(s.typesRemoved()).append(" removed, ")
                .append(s.typesModified()).append(" modified, ").append(s.typesRelocated()).append(" moved/renamed\n");
        sb.append("- Methods: ").append(s.methodsAdded()).append(" added, ").append(s.methodsRemoved()).append(" removed, **")
                .append(s.methodsBehaviorChanged()).append(" behaviour changed**, ").append(s.methodsSignatureChanged())
                .append(" declaration-only, ").append(s.methodsRelocated()).append(" relocated, ")
                .append(s.methodsLocalRenameOnly()).append(" local-rename-only, ").append(s.methodsCosmeticOnly())
                .append(" formatting/comments only\n\n");
        int noise = s.methodsRelocated() + s.methodsLocalRenameOnly() + s.methodsCosmeticOnly();
        if (noise > 0) {
            sb.append("> ").append(noise).append(" method change(s) are moves, renames or formatting and can be skimmed.\n\n");
        }
        sb.append("**Level 2: packages**\n\n");
        for (PackageChange p : cs.packages()) {
            sb.append("- `").append(p.name().isEmpty() ? "(default)" : p.name()).append("`: ")
                    .append(p.typesAdded()).append(" added, ").append(p.typesRemoved()).append(" removed, ")
                    .append(p.typesModified()).append(" modified, ").append(p.typesMoved()).append(" moved")
                    .append(p.behaviorMethods() > 0 ? ", " + p.behaviorMethods() + " methods with real logic changes" : "").append('\n');
        }
        sb.append("\n**Level 3: classes and methods**\n\n");
        for (TypeChange t : cs.types()) {
            sb.append("### ").append(label(t.kind())).append(" `").append(t.simpleName()).append("` (")
                    .append(t.layer().name().toLowerCase()).append(", ").append(t.nature().name().toLowerCase().replace('_', ' ')).append(")\n");
            if (t.oldFqn() != null && !t.oldFqn().equals(t.fqn())) {
                sb.append("from `").append(t.oldFqn()).append("`\n");
            }
            for (String a : t.annotationsAdded()) {
                sb.append("- annotation added: `").append(a).append("`\n");
            }
            for (String a : t.annotationsRemoved()) {
                sb.append("- annotation removed: `").append(a).append("`\n");
            }
            for (var f : t.fields()) {
                sb.append("- field ").append(f.kind().name().toLowerCase()).append(": `").append(f.name()).append("` ")
                        .append(f.oldType() == null ? "" : f.oldType()).append(f.oldType() != null && f.newType() != null ? " -> " : "")
                        .append(f.newType() == null ? "" : f.newType()).append('\n');
            }
            for (MethodChange m : t.methods()) {
                if (m.nature() == Nature.COSMETIC || m.nature() == Nature.LOCAL_RENAME) {
                    sb.append("- (skim) `").append(m.name()).append("`: ").append(m.nature() == Nature.COSMETIC ? "formatting or comments only"
                            : "only local variables or parameters renamed").append('\n');
                    continue;
                }
                sb.append("- **").append(natureLabel(m)).append("** `").append(m.signature() != null ? m.signature() : m.oldSignature()).append("`");
                if (m.oldSignature() != null && m.signature() != null && !m.oldSignature().equals(m.signature())) {
                    sb.append(" (was `").append(m.oldSignature()).append("`)");
                }
                sb.append('\n');
                if (m.declaration() != null) {
                    m.declaration().annotationsAdded().forEach(a -> sb.append("  - added `").append(a).append("`\n"));
                    m.declaration().annotationsRemoved().forEach(a -> sb.append("  - removed `").append(a).append("`\n"));
                    m.declaration().modifiersAdded().forEach(a -> sb.append("  - modifier added `").append(a).append("`\n"));
                    m.declaration().modifiersRemoved().forEach(a -> sb.append("  - modifier removed `").append(a).append("`\n"));
                }
                behavior(sb, m.behavior());
            }
            if (t.unchangedMethods() > 0) {
                sb.append("- ").append(t.unchangedMethods()).append(" other method(s) unchanged\n");
            }
            sb.append('\n');
        }
        if (!cs.warnings().isEmpty()) {
            sb.append("**Analysis warnings:** ").append(cs.warnings().size()).append(" file(s) could not be fully parsed.\n");
        }
        return sb.toString();
    }

    private static void behavior(StringBuilder sb, BehaviorDelta b) {
        if (b == null || b.isEmpty()) {
            return;
        }
        b.branchesAdded().forEach(x -> sb.append("  - new ").append(x.kind()).append(" `").append(x.condition()).append("` (line ").append(x.line()).append(")\n"));
        b.branchesRemoved().forEach(x -> sb.append("  - removed ").append(x.kind()).append(" `").append(x.condition()).append("`\n"));
        calls(sb, "now calls", b.callsAdded());
        calls(sb, "no longer calls", b.callsRemoved());
        if (!b.thrownAdded().isEmpty()) {
            sb.append("  - now throws ").append(String.join(", ", b.thrownAdded())).append('\n');
        }
        if (!b.thrownRemoved().isEmpty()) {
            sb.append("  - no longer throws ").append(String.join(", ", b.thrownRemoved())).append('\n');
        }
        if (!b.caughtAdded().isEmpty()) {
            sb.append("  - now catches ").append(String.join(", ", b.caughtAdded())).append('\n');
        }
        if (!b.fieldWritesAdded().isEmpty()) {
            sb.append("  - now writes field(s) ").append(String.join(", ", b.fieldWritesAdded())).append('\n');
        }
        if (!b.literalsAdded().isEmpty()) {
            sb.append("  - new string constants: ").append(b.literalsAdded().stream().limit(4).map(x -> "`" + x + "`").collect(Collectors.joining(", "))).append('\n');
        }
    }

    private static void calls(StringBuilder sb, String verb, List<CallSite> calls) {
        if (calls.isEmpty()) {
            return;
        }
        sb.append("  - ").append(verb).append(": ").append(calls.stream().limit(6).map(c -> "`" + simple(c.targetType()) + "." + c.targetMethod()
                + (c.resolution().name().equals("UNRESOLVED") ? "?" : "") + "`").distinct().collect(Collectors.joining(", ")))
                .append(calls.size() > 6 ? ", ..." : "").append('\n');
    }

    private static String simple(String t) {
        return t.substring(t.lastIndexOf('.') + 1);
    }

    private static String label(ChangeKind k) {
        return switch (k) {
            case ADDED -> "NEW";
            case REMOVED -> "DELETED";
            case MODIFIED -> "CHANGED";
            case RENAMED -> "RENAMED";
            case MOVED -> "MOVED";
            case MOVED_RENAMED -> "MOVED+RENAMED";
        };
    }

    private static String natureLabel(MethodChange m) {
        return switch (m.kind()) {
            case ADDED -> "new";
            case REMOVED -> "removed";
            case RENAMED -> m.nature() == Nature.BEHAVIOR ? "renamed and logic changed" : "renamed";
            case MOVED, MOVED_RENAMED -> "moved";
            case MODIFIED -> switch (m.nature()) {
                case BEHAVIOR -> "logic changed";
                case SIGNATURE -> "declaration changed";
                default -> m.nature().name().toLowerCase();
            };
        };
    }
}
