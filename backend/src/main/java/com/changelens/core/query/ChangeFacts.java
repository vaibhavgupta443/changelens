package com.changelens.core.query;

import com.changelens.core.diff.ChangeModel.BehaviorDelta;
import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.ChangeModel.DeclDelta;
import com.changelens.core.diff.ChangeModel.MethodChange;
import com.changelens.core.diff.ChangeModel.Nature;
import com.changelens.core.diff.ChangeModel.Summary;
import com.changelens.core.diff.ChangeModel.TypeChange;
import com.changelens.core.flow.FlowModel.BlastRadius;
import com.changelens.core.flow.FlowModel.FlowImpact;
import com.changelens.core.flow.FlowModel.ImpactReport;
import com.changelens.core.report.PrComment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only questions about one analysed change. Everything returned is a fact the engine computed from the code, and every method
 * is identified by its id (a <em>citation</em>), so a caller, a person, an MCP client or a language model, can be checked: a
 * statement that cites an id this class does not know, or attributes a fact the engine did not produce, is rejected by
 * {@link #isKnown(String)} and {@link #hasFact(String, String)}.
 *
 * <p>Nothing here guesses and nothing here calls a model.
 */
public final class ChangeFacts {

    /** One thing the engine established about a method, for example {@code calls-added: now calls OwnerRepository.saveAndFlush}. */
    public record Fact(String kind, String text) {
    }

    public record MethodFact(String id, String type, String signature, String change, String nature, String file, int startLine,
                             int endLine, List<Fact> facts) {
    }

    public record ReachedFact(String method, int depth, List<String> path, boolean viaInterface) {
    }

    public record EntryFact(String kind, String label, String entryMethod, boolean entryItselfChanged, List<ReachedFact> reached) {
    }

    public record BlastFact(String changedMethod, List<String> unchangedCallers, List<String> coveringTests) {
    }

    public record Overview(String repo, String base, String head, int filesChanged, int methodsAdded, int methodsRemoved,
                           int methodsBehaviorChanged, int methodsSignatureChanged, int methodsRelocated,
                           int methodsLocalRenameOnly, int methodsCosmeticOnly, int endpointsAffected, int changedWithoutTests,
                           double resolvedCallRatio, List<String> warnings) {
    }

    private static final List<Nature> IMPORTANCE = List.of(Nature.BEHAVIOR, Nature.NEW, Nature.SIGNATURE, Nature.DELETED,
            Nature.RELOCATED, Nature.LOCAL_RENAME, Nature.COSMETIC);

    private final ChangeSet changes;
    private final ImpactReport impact;
    private final Map<String, MethodChange> methods = new LinkedHashMap<>();
    private final Set<String> known = new HashSet<>();

    public ChangeFacts(ChangeSet changes, ImpactReport impact) {
        this.changes = changes;
        this.impact = impact;
        for (TypeChange t : changes.types()) {
            known.add(t.fqn());
            if (t.oldFqn() != null) {
                known.add(t.oldFqn());
            }
            for (MethodChange m : t.methods()) {
                methods.put(m.id(), m);
                known.add(m.id());
                if (m.oldId() != null) {
                    known.add(m.oldId());
                }
            }
        }
        for (FlowImpact f : impact.flows()) {
            known.add(f.entry().methodId());
            f.reached().forEach(r -> {
                known.add(r.methodId());
                known.addAll(r.path());
            });
        }
        for (BlastRadius b : impact.blast()) {
            known.add(b.changedMethod());
            known.addAll(b.unchangedCallers());
            known.addAll(b.coveringTests());
        }
        known.addAll(impact.changedWithoutTests());
    }

    public Overview overview() {
        Summary s = changes.summary();
        return new Overview(changes.repo(), changes.baseRef(), changes.headRef(), s.filesChanged(), s.methodsAdded(),
                s.methodsRemoved(), s.methodsBehaviorChanged(), s.methodsSignatureChanged(), s.methodsRelocated(),
                s.methodsLocalRenameOnly(), s.methodsCosmeticOnly(), impact.flows().size(), impact.changedWithoutTests().size(),
                impact.resolvedCallRatio(), changes.warnings());
    }

    /**
     * @param nature      restrict to one nature such as {@code BEHAVIOR} (case-insensitive); null or blank for all
     * @param typeContains restrict to types whose name contains this text (case-insensitive); null or blank for all
     * @return changed methods, most important first
     * @throws IllegalArgumentException if {@code nature} is not one of the known natures
     */
    public List<MethodFact> changedMethods(String nature, String typeContains) {
        Nature wanted = parseNature(nature);
        String needle = typeContains == null ? "" : typeContains.toLowerCase(Locale.ROOT);
        List<MethodFact> out = new ArrayList<>();
        for (TypeChange t : changes.types()) {
            if (!needle.isEmpty() && !t.fqn().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            for (MethodChange m : t.methods()) {
                if (wanted == null || m.nature() == wanted) {
                    out.add(toFact(t, m));
                }
            }
        }
        out.sort(Comparator.comparingInt((MethodFact f) -> IMPORTANCE.indexOf(Nature.valueOf(f.nature()))).thenComparing(MethodFact::id));
        return out;
    }

    /** The existing features (HTTP endpoints, listeners, jobs) whose call path reaches changed code. */
    public List<EntryFact> affectedEntryPoints() {
        return impact.flows().stream()
                .map(f -> new EntryFact(f.entry().kind().name(), f.entry().label(), f.entry().methodId(), f.entryItselfChanged(),
                        f.reached().stream().map(r -> new ReachedFact(r.methodId(), r.depth(), r.path(), r.viaInterface())).toList()))
                .toList();
    }

    /** Changed methods that no test reaches. The check is static, so a test that reaches the method by reflection is not seen. */
    public List<String> untestedChanges() {
        return impact.changedWithoutTests();
    }

    /**
     * @param reference an exact id, or part of one such as {@code OrderService#cancel} or {@code cancel}
     * @return every known method id the reference matches (zero, one, or several when it is ambiguous)
     */
    public List<String> resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            return List.of();
        }
        String ref = reference.strip();
        if (known.contains(ref)) {
            return List.of(ref);
        }
        List<String> hits = known.stream().filter(id -> id.contains("#") && (id.endsWith("#" + ref) || id.contains(ref)))
                .sorted().toList();
        return hits;
    }

    public Optional<BlastFact> blastRadius(String methodId) {
        return impact.blast().stream().filter(b -> b.changedMethod().equals(methodId))
                .map(b -> new BlastFact(b.changedMethod(), b.unchangedCallers(), b.coveringTests())).findFirst();
    }

    /** True if the id is a type or method that appears in this analysis. */
    public boolean isKnown(String citation) {
        return citation != null && known.contains(citation);
    }

    /**
     * True if the engine produced this fact for this method: the citation is known and one of its facts has exactly this text
     * (ignoring case and surrounding space).
     */
    public boolean hasFact(String citation, String text) {
        MethodChange m = citation == null ? null : methods.get(citation);
        if (m == null || text == null) {
            return false;
        }
        String wanted = text.strip().toLowerCase(Locale.ROOT);
        return toFact(null, m).facts().stream().anyMatch(f -> f.text().toLowerCase(Locale.ROOT).equals(wanted));
    }

    private static Nature parseNature(String nature) {
        if (nature == null || nature.isBlank()) {
            return null;
        }
        try {
            return Nature.valueOf(nature.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown nature '" + nature + "'. Use one of: " + String.join(", ",
                    IMPORTANCE.stream().map(Enum::name).toList()));
        }
    }

    private MethodFact toFact(TypeChange t, MethodChange m) {
        List<Fact> facts = new ArrayList<>();
        switch (m.kind()) {
            case ADDED -> facts.add(new Fact("new-method", "new method"));
            case REMOVED -> facts.add(new Fact("removed-method", "method removed"));
            case RENAMED -> facts.add(new Fact("renamed", "renamed from " + m.oldSignature()));
            case MOVED -> facts.add(new Fact("moved", "moved from " + m.oldId()));
            case MOVED_RENAMED -> facts.add(new Fact("moved-renamed", "moved and renamed from " + m.oldId()));
            default -> {
            }
        }
        BehaviorDelta b = m.behavior();
        if (b != null) {
            b.branchesAdded().forEach(x -> facts.add(new Fact("branch-added", "adds " + x.kind() + (x.condition().isEmpty() ? "" : " on `" + x.condition() + "`"))));
            b.branchesRemoved().forEach(x -> facts.add(new Fact("branch-removed", "removes " + x.kind() + (x.condition().isEmpty() ? "" : " on `" + x.condition() + "`"))));
            distinct(b.thrownAdded()).forEach(x -> facts.add(new Fact("throws-added", "now throws " + x)));
            distinct(b.thrownRemoved()).forEach(x -> facts.add(new Fact("throws-removed", "no longer throws " + x)));
            distinct(b.caughtAdded()).forEach(x -> facts.add(new Fact("catch-added", "now catches " + x)));
            distinct(b.caughtRemoved()).forEach(x -> facts.add(new Fact("catch-removed", "no longer catches " + x)));
            distinct(b.callsAdded().stream().map(PrComment::callLabel).toList()).forEach(x -> facts.add(new Fact("calls-added", "now calls " + x)));
            distinct(b.callsRemoved().stream().map(PrComment::callLabel).toList()).forEach(x -> facts.add(new Fact("calls-removed", "no longer calls " + x)));
            distinct(b.fieldWritesAdded()).forEach(x -> facts.add(new Fact("field-write-added", "now writes " + x)));
            distinct(b.fieldWritesRemoved()).forEach(x -> facts.add(new Fact("field-write-removed", "no longer writes " + x)));
            distinct(b.literalsAdded()).forEach(x -> facts.add(new Fact("literal-added", "new literal " + x)));
            distinct(b.literalsRemoved()).forEach(x -> facts.add(new Fact("literal-removed", "literal removed " + x)));
            if (b.returnsDelta() != 0) {
                facts.add(new Fact("returns", (b.returnsDelta() > 0 ? "+" : "") + b.returnsDelta() + " return statement(s)"));
            }
        }
        DeclDelta d = m.declaration();
        if (d != null) {
            d.annotationsAdded().forEach(x -> facts.add(new Fact("annotation-added", "added " + x)));
            d.annotationsRemoved().forEach(x -> facts.add(new Fact("annotation-removed", "removed " + x)));
            d.modifiersAdded().forEach(x -> facts.add(new Fact("modifier-added", "now " + x)));
            d.modifiersRemoved().forEach(x -> facts.add(new Fact("modifier-removed", "no longer " + x)));
            if (!java.util.Objects.equals(d.oldReturnType(), d.newReturnType())) {
                facts.add(new Fact("return-type", "return type " + d.oldReturnType() + " -> " + d.newReturnType()));
            }
            if (!java.util.Objects.equals(d.oldParamTypes(), d.newParamTypes())) {
                facts.add(new Fact("param-types", "parameters " + d.oldParamTypes() + " -> " + d.newParamTypes()));
            }
        }
        String type = t != null ? t.fqn() : m.typeFqn();
        return new MethodFact(m.id(), type, m.signature() != null ? m.signature() : m.oldSignature(), m.kind().name(),
                m.nature().name(), m.filePath(), m.startLine(), m.endLine(), List.copyOf(facts));
    }

    private static List<String> distinct(List<String> in) {
        return List.copyOf(new LinkedHashSet<>(in));
    }
}
