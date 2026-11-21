package com.changelens.core.flow;

import java.util.List;

/** Records describing how code is entered at runtime and what a change can reach. */
public final class FlowModel {

    private FlowModel() {
    }

    public enum EntryKind { HTTP, MESSAGE, SCHEDULED, EVENT }

    /** A place where the outside world enters the application, for example {@code GET /owners/{id}}. */
    public record EntryPoint(EntryKind kind, String label, String methodId, String typeFqn, String detail) {
    }

    /** One step in a call path. {@code viaInterface} means the target was found through interface/inheritance dispatch. */
    public record Edge(String from, String to, boolean viaInterface, boolean ambiguous) {
    }

    /** A changed method reached from an entry point, with the shortest call path that reaches it. */
    public record Reached(String methodId, int depth, List<String> path, boolean viaInterface) {
    }

    /** An existing feature (entry point) whose behaviour can be affected by this change. */
    public record FlowImpact(EntryPoint entry, boolean entryItselfChanged, List<Reached> reached) {
    }

    /** Unchanged code that depends directly on a changed method (the part nobody reads in the diff). */
    public record BlastRadius(String changedMethod, List<String> unchangedCallers, List<String> coveringTests) {
    }

    public record ImpactReport(List<FlowImpact> flows, List<BlastRadius> blast, List<String> changedWithoutTests,
                               double resolvedCallRatio) {
    }
}
