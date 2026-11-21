package com.changelens.cli;

import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.SnapshotDiffer;
import com.changelens.core.flow.FlowModel.ImpactReport;
import com.changelens.core.flow.ImpactAnalyzer;
import com.changelens.core.ingest.GitSnapshotLoader;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.report.MarkdownReport;
import com.changelens.core.report.PrComment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Command-line entry point, also what the GitHub Action runs.
 *
 * <pre>
 * java -jar changelens-cli.jar --repo . --base origin/main --head HEAD [--format pr-comment|markdown] [--max-chars 60000] [--out report.md]
 * </pre>
 */
public final class AnalyzeMain {

    private AnalyzeMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parse(args);
        if (opt.containsKey("help") || !opt.containsKey("base") || !opt.containsKey("head")) {
            System.err.println("usage: changelens --repo <dir> --base <rev> --head <rev> [--format pr-comment|markdown] [--max-chars N] [--out file]");
            System.exit(opt.containsKey("help") ? 0 : 2);
        }
        Path repo = Path.of(opt.getOrDefault("repo", ".")).toAbsolutePath().normalize();
        String name = repo.getFileName() == null ? "repo" : repo.getFileName().toString();
        long t0 = System.nanoTime();
        boolean verbose = opt.containsKey("verbose");
        // base and head are independent, so they are read and parsed at the same time
        // and they share one parse cache, so files that did not change are parsed once
        com.changelens.core.parse.ParseCache cache = new com.changelens.core.parse.ParseCache();
        java.util.concurrent.CompletableFuture<Snapshot> baseFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> load(repo, opt.get("base"), name, cache));
        java.util.concurrent.CompletableFuture<Snapshot> headFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> load(repo, opt.get("head"), name, cache));
        Snapshot base = baseFuture.join();
        long t1 = System.nanoTime();
        Snapshot head = headFuture.join();
        long t2 = System.nanoTime();
        ChangeSet cs = SnapshotDiffer.diff(base, head);
        long t3 = System.nanoTime();
        ImpactReport impact = ImpactAnalyzer.analyze(head, cs);
        if (verbose) {
            System.err.printf("[changelens] base %d ms, head %d ms, diff %d ms, impact %d ms (%d + %d types)%n", (t1 - t0) / 1_000_000,
                    (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, (System.nanoTime() - t3) / 1_000_000, base.types().size(), head.types().size());
        }
        String format = opt.getOrDefault("format", "pr-comment");
        int max = Integer.parseInt(opt.getOrDefault("max-chars", "60000"));
        String out = format.equals("markdown") ? MarkdownReport.render(cs) : PrComment.render(cs, impact, max);
        if (opt.containsKey("out")) {
            Files.writeString(Path.of(opt.get("out")), out, StandardCharsets.UTF_8);
        } else {
            System.out.println(out);
        }
        System.err.printf("[changelens] %d types changed, %d flows touched, %d ms%n", cs.types().size(), impact.flows().size(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    private static Snapshot load(Path repo, String revision, String name, com.changelens.core.parse.ParseCache cache) {
        try {
            return GitSnapshotLoader.load(repo, revision, name, cache);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-h") || a.equals("--help")) {
                m.put("help", "1");
            } else if (a.startsWith("--") && i + 1 < args.length) {
                m.put(a.substring(2), args[++i]);
            }
        }
        return m;
    }
}
