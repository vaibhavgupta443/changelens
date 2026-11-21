package com.changelens.core.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The facts ChangeLens extracts from source code with a real parser (no LLM involved).
 * Everything downstream (diffing, impact analysis, visualisation, narration) works from these immutable records.
 */
public final class CodeModel {

    private CodeModel() {
    }

    public enum TypeKind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION }

    /** Architectural role of a class, inferred from Spring stereotypes and naming. */
    public enum Layer { CONTROLLER, SERVICE, REPOSITORY, ENTITY, CONFIG, CLIENT, LISTENER, TEST, OTHER }

    /** How sure we are about where a call goes. Shown in the UI so reviewers know what is certain. */
    public enum Resolution {
        /** The target type was found in this repository. */
        REPO,
        /** The target is a library / JDK type (known by name, not analysed). */
        EXTERNAL,
        /** The receiver's type could not be determined (reflection, lambdas, chained generics, var...). */
        UNRESOLVED
    }

    public record CallSite(String targetType, String targetMethod, int argCount, int line, Resolution resolution) {
        public String key() {
            return targetType + "#" + targetMethod + "/" + argCount;
        }
    }

    /** A control-flow decision. {@code condition} is the normalised source text of the condition (or the selector). */
    public record Branch(String kind, String condition, int line) {
        public String key() {
            return kind + ":" + condition;
        }
    }

    /** Everything a method does that a reviewer cares about, extracted from its AST. */
    public record BehaviorFacts(
            List<CallSite> calls,
            List<Branch> branches,
            List<String> thrown,
            List<String> caught,
            int returns,
            List<String> fieldWrites,
            List<String> stringLiterals) {
        public static BehaviorFacts empty() {
            return new BehaviorFacts(List.of(), List.of(), List.of(), List.of(), 0, List.of(), List.of());
        }
    }

    public record MethodModel(
            String id,
            String typeFqn,
            String name,
            String signature,
            String returnType,
            List<String> paramTypes,
            List<String> paramNames,
            Set<String> modifiers,
            List<String> annotations,
            int startLine,
            int endLine,
            boolean constructor,
            boolean hasBody,
            String rawHash,
            String normHash,
            String alphaHash,
            int statementCount,
            BehaviorFacts facts) {
    }

    public record FieldModel(String name, String type, Set<String> modifiers, List<String> annotations, int line) {
    }

    public record TypeModel(
            String fqn,
            String simpleName,
            String packageName,
            TypeKind kind,
            List<String> extendsTypes,
            List<String> implementsTypes,
            Set<String> modifiers,
            List<String> annotations,
            Layer layer,
            String filePath,
            int startLine,
            int endLine,
            List<FieldModel> fields,
            List<MethodModel> methods) {
        public MethodModel method(String id) {
            return methods.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
        }
    }

    /** A non-Java file that matters to behaviour: build files, config, SQL migrations. */
    public record ResourceFile(String path, String kind, String content) {
    }

    public record Snapshot(
            String repo,
            String ref,
            Map<String, TypeModel> types,
            List<ResourceFile> resources,
            List<String> parseWarnings) {
    }
}
