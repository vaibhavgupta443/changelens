package com.changelens.core.parse;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Remembers the parse of every distinct file content. Two snapshots of the same repository (base and head of a pull request)
 * share almost all of their files, so with one cache the common files are parsed once instead of twice.
 *
 * <p>The snapshots are built at the same time, so both reach the same file at about the same moment. The first to ask parses it and
 * the other waits for that result, so a file is never parsed twice even when the two builds race.
 *
 * <p>Cached trees are only read afterwards: the builder normalises methods on clones, never on the shared tree. Use one cache
 * for one analysis and drop it afterwards, so a long-running service does not keep trees alive.
 */
public final class ParseCache {

    private final Map<String, CompletableFuture<ParseResult<CompilationUnit>>> byContent = new ConcurrentHashMap<>();

    ParseResult<CompilationUnit> get(String content, Function<String, ParseResult<CompilationUnit>> parser) {
        CompletableFuture<ParseResult<CompilationUnit>> known = byContent.get(content);
        if (known == null) {
            CompletableFuture<ParseResult<CompilationUnit>> mine = new CompletableFuture<>();
            known = byContent.putIfAbsent(content, mine);
            if (known == null) {
                try {
                    mine.complete(parser.apply(content));
                } catch (RuntimeException | Error e) {
                    mine.completeExceptionally(e);
                    throw e;
                }
                return mine.join();
            }
        }
        return known.join();
    }

    public int size() {
        return byContent.size();
    }
}
