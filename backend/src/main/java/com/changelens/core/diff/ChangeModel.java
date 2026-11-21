package com.changelens.core.diff;

import com.changelens.core.model.CodeModel.Branch;
import com.changelens.core.model.CodeModel.CallSite;
import com.changelens.core.model.CodeModel.Layer;

import java.util.List;

/** The semantic difference between two snapshots. Every record carries enough facts to be shown and cited. */
public final class ChangeModel {

    private ChangeModel() {
    }

    public enum ChangeKind { ADDED, REMOVED, MODIFIED, RENAMED, MOVED, MOVED_RENAMED }

    /**
     * What kind of change it really is, from least to most important for a reviewer:
     * COSMETIC (comments / formatting), LOCAL_RENAME (only local variables or parameters renamed),
     * SIGNATURE (declaration changed, body identical), BEHAVIOR (the body logic changed), NEW / DELETED.
     */
    public enum Nature { NEW, DELETED, COSMETIC, LOCAL_RENAME, SIGNATURE, BEHAVIOR, RELOCATED }

    public record BehaviorDelta(
            List<CallSite> callsAdded,
            List<CallSite> callsRemoved,
            List<Branch> branchesAdded,
            List<Branch> branchesRemoved,
            List<String> thrownAdded,
            List<String> thrownRemoved,
            List<String> caughtAdded,
            List<String> caughtRemoved,
            List<String> fieldWritesAdded,
            List<String> fieldWritesRemoved,
            List<String> literalsAdded,
            List<String> literalsRemoved,
            int returnsDelta) {
        public boolean isEmpty() {
            return callsAdded.isEmpty() && callsRemoved.isEmpty() && branchesAdded.isEmpty() && branchesRemoved.isEmpty()
                    && thrownAdded.isEmpty() && thrownRemoved.isEmpty() && caughtAdded.isEmpty() && caughtRemoved.isEmpty()
                    && fieldWritesAdded.isEmpty() && fieldWritesRemoved.isEmpty() && literalsAdded.isEmpty()
                    && literalsRemoved.isEmpty() && returnsDelta == 0;
        }

        public static BehaviorDelta none() {
            return new BehaviorDelta(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), 0);
        }
    }

    public record DeclDelta(
            List<String> modifiersAdded,
            List<String> modifiersRemoved,
            List<String> annotationsAdded,
            List<String> annotationsRemoved,
            String oldReturnType,
            String newReturnType) {
        public boolean isEmpty() {
            return modifiersAdded.isEmpty() && modifiersRemoved.isEmpty() && annotationsAdded.isEmpty()
                    && annotationsRemoved.isEmpty() && java.util.Objects.equals(oldReturnType, newReturnType);
        }
    }

    public record MethodChange(
            String id,
            String oldId,
            String typeFqn,
            String name,
            String signature,
            String oldSignature,
            ChangeKind kind,
            Nature nature,
            DeclDelta declaration,
            BehaviorDelta behavior,
            String filePath,
            int startLine,
            int endLine,
            int oldStartLine,
            int oldEndLine) {
    }

    public record FieldChange(String name, ChangeKind kind, String oldType, String newType, List<String> annotationsAdded,
                              List<String> annotationsRemoved) {
    }

    public record TypeChange(
            String fqn,
            String oldFqn,
            String simpleName,
            String packageName,
            ChangeKind kind,
            Nature nature,
            Layer layer,
            String filePath,
            List<String> annotationsAdded,
            List<String> annotationsRemoved,
            List<String> supertypesAdded,
            List<String> supertypesRemoved,
            List<FieldChange> fields,
            List<MethodChange> methods,
            int unchangedMethods) {
    }

    public record PackageChange(String name, int typesAdded, int typesRemoved, int typesModified, int typesMoved,
                                int behaviorMethods, int totalMethodChanges) {
    }

    public record Summary(
            int filesChanged,
            int typesAdded, int typesRemoved, int typesModified, int typesRelocated,
            int methodsAdded, int methodsRemoved, int methodsBehaviorChanged, int methodsSignatureChanged,
            int methodsRelocated, int methodsLocalRenameOnly, int methodsCosmeticOnly) {
    }

    public record ChangeSet(
            String repo,
            String baseRef,
            String headRef,
            List<TypeChange> types,
            List<PackageChange> packages,
            Summary summary,
            List<String> warnings) {
    }
}
