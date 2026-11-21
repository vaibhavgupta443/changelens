package com.changelens.core;

import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.SnapshotDiffer;
import com.changelens.core.flow.FlowModel.ImpactReport;
import com.changelens.core.flow.ImpactAnalyzer;
import com.changelens.core.ingest.GitSnapshotLoader;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.parse.ParseCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The whole analysis in one call: read two revisions of a repository, diff them and work out the impact. The CLI and the REST
 * API both use this, so they cannot drift apart.
 *
 * <p>Base and head are read at the same time and share one {@link ParseCache}, so a file that is identical in both is parsed once.
 */
public final class Analyzer {

    /** The result plus how long each phase took, in milliseconds. */
    public record Result(ChangeSet changes, ImpactReport impact, long loadMs, long diffMs, long impactMs, int baseTypes, int headTypes) {

        public long totalMs() {
            return loadMs + diffMs + impactMs;
        }
    }

    private Analyzer() {
    }

    /**
     * @throws IOException if a revision does not exist or the repository cannot be read
     */
    public static Result analyze(Path repoDir, String repoName, String baseRev, String headRev) throws IOException {
        long t0 = System.nanoTime();
        ParseCache cache = new ParseCache();
        CompletableFuture<Snapshot> baseFuture = CompletableFuture.supplyAsync(() -> load(repoDir, baseRev, repoName, cache));
        CompletableFuture<Snapshot> headFuture = CompletableFuture.supplyAsync(() -> load(repoDir, headRev, repoName, cache));
        Snapshot base;
        Snapshot head;
        try {
            base = baseFuture.join();
            head = headFuture.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof UncheckedIOException io) {
                throw io.getCause();
            }
            throw e;
        }
        long t1 = System.nanoTime();
        ChangeSet changes = SnapshotDiffer.diff(base, head);
        long t2 = System.nanoTime();
        ImpactReport impact = ImpactAnalyzer.analyze(head, changes);
        long t3 = System.nanoTime();
        return new Result(changes, impact, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000,
                base.types().size(), head.types().size());
    }

    private static Snapshot load(Path repoDir, String revision, String name, ParseCache cache) {
        try {
            return GitSnapshotLoader.load(repoDir, revision, name, cache);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
