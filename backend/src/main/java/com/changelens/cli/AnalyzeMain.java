package com.changelens.cli;

import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.SnapshotDiffer;
import com.changelens.core.ingest.GitSnapshotLoader;
import com.changelens.core.model.CodeModel.CallSite;
import com.changelens.core.model.CodeModel.Resolution;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.report.MarkdownReport;
import java.nio.file.Path;

/** Command-line entry: {@code AnalyzeMain <repoDir> <baseRev> <headRev>} prints the change report. */
public final class AnalyzeMain {

    private AnalyzeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: AnalyzeMain <repoDir> <baseRev> <headRev>");
            System.exit(2);
        }
        Path repo = Path.of(args[0]);
        long t0 = System.nanoTime();
        Snapshot base = GitSnapshotLoader.load(repo, args[1], repo.getFileName().toString());
        Snapshot head = GitSnapshotLoader.load(repo, args[2], repo.getFileName().toString());
        long t1 = System.nanoTime();
        ChangeSet cs = SnapshotDiffer.diff(base, head);
        long t2 = System.nanoTime();
        System.out.println(MarkdownReport.render(cs));
        long calls = head.types().values().stream().flatMap(t -> t.methods().stream()).flatMap(m -> m.facts().calls().stream()).count();
        long resolved = head.types().values().stream().flatMap(t -> t.methods().stream()).flatMap(m -> m.facts().calls().stream())
                .filter(c -> c.resolution() == Resolution.REPO).count();
        long external = head.types().values().stream().flatMap(t -> t.methods().stream()).flatMap(m -> m.facts().calls().stream())
                .filter((CallSite c) -> c.resolution() == Resolution.EXTERNAL).count();
        System.err.printf("[stats] head: %d types, %d calls (%d in-repo, %d library, %d unresolved); load %d ms, diff %d ms%n",
                head.types().size(), calls, resolved, external, calls - resolved - external, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);
    }
}
