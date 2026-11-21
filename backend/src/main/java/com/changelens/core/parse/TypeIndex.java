package com.changelens.core.parse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the repository declares, collected in a first pass so that the second pass can resolve names
 * (imports, same package, nested types) and call targets without a compiler or a classpath.
 */
final class TypeIndex {

    /** Minimal facts about a declared type that call resolution needs. */
    static final class Info {
        final String fqn;
        final String packageName;
        final List<String> superTypeNames = new ArrayList<>();   // raw names as written (resolved lazily per file)
        final Map<String, String> fieldTypes = new LinkedHashMap<>(); // field name -> declared type text
        final Map<String, List<MethodSig>> methods = new HashMap<>();  // method name -> signatures
        String filePath;
        List<String> fileImports = List.of();
        boolean isInterface;

        Info(String fqn, String packageName) {
            this.fqn = fqn;
            this.packageName = packageName;
        }
    }

    record MethodSig(String name, int paramCount, String returnType) {
    }

    private final Map<String, Info> byFqn = new LinkedHashMap<>();
    private final Map<String, Set<String>> bySimple = new HashMap<>();

    void add(Info info) {
        byFqn.put(info.fqn, info);
        String simple = info.fqn.substring(info.fqn.lastIndexOf('.') + 1);
        bySimple.computeIfAbsent(simple, k -> new LinkedHashSet<>()).add(info.fqn);
    }

    Info get(String fqn) {
        return fqn == null ? null : byFqn.get(fqn);
    }

    boolean contains(String fqn) {
        return fqn != null && byFqn.containsKey(fqn);
    }

    Set<String> bySimpleName(String simple) {
        return bySimple.getOrDefault(simple, Set.of());
    }

    Iterable<Info> all() {
        return byFqn.values();
    }
}
