package com.changelens.core.parse;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Remembers the parse of every distinct file content. Two snapshots of the same repository (base and head of a pull request)
 * share almost all of their files, so with one cache the common files are parsed once instead of twice.
 *
 * <p>Cached trees are only read afterwards: the builder normalises methods on clones, never on the shared tree. Use one cache
 * for one analysis and drop it afterwards, so a long-running service does not keep trees alive.
 */
public final class ParseCache {

    private final Map<String, ParseResult<CompilationUnit>> byContent = new ConcurrentHashMap<>();

    ParseResult<CompilationUnit> get(String content, Function<String, ParseResult<CompilationUnit>> parser) {
        ParseResult<CompilationUnit> hit = byContent.get(content);
        if (hit != null) {
            return hit;
        }
        ParseResult<CompilationUnit> parsed = parser.apply(content);
        ParseResult<CompilationUnit> earlier = byContent.putIfAbsent(content, parsed);
        return earlier != null ? earlier : parsed;
    }

    public int size() {
        return byContent.size();
    }
}
