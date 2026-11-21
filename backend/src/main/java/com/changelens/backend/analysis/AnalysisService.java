package com.changelens.backend.analysis;

import com.changelens.core.Analyzer;
import com.changelens.core.report.MarkdownReport;
import com.changelens.core.report.PrComment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Accepts analysis requests and runs them on a small, bounded worker pool. A request is stored first (status QUEUED), so the
 * caller gets an id immediately and can poll; the heavy work never runs on a web thread.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private static final int PR_COMMENT_MAX_CHARS = 60_000;

    private final AnalysisRepository runs;
    private final WorkspaceResolver workspace;
    private final ExecutorService executor;

    public AnalysisService(AnalysisRepository runs, WorkspaceResolver workspace, ExecutorService analysisExecutor) {
        this.runs = runs;
        this.workspace = workspace;
        this.executor = analysisExecutor;
    }

    /** Validates that the repository exists, stores the run and queues it. */
    public AnalysisRun submit(AnalysisRequest request) {
        workspace.resolve(request.repo()); // fail before anything is stored
        AnalysisRun run = runs.save(new AnalysisRun(UUID.randomUUID().toString(), request.repo(), request.base(), request.head(), Instant.now()));
        try {
            executor.execute(() -> execute(run.getId()));
        } catch (RejectedExecutionException e) {
            runs.delete(run);
            throw new QueueFullException();
        }
        return run;
    }

    void execute(String id) {
        AnalysisRun run = runs.findById(id).orElse(null);
        if (run == null) {
            return;
        }
        run.markRunning(Instant.now());
        runs.save(run);
        try {
            Analyzer.Result result = Analyzer.analyze(workspace.resolve(run.getRepo()), run.getRepo(), run.getBase(), run.getHead());
            run.markDone(Instant.now(), result.totalMs(), result.changes().types().size(), result.impact().flows().size(),
                    PrComment.render(result.changes(), result.impact(), PR_COMMENT_MAX_CHARS), MarkdownReport.render(result.changes()));
        } catch (Exception e) {
            log.warn("analysis {} failed: {}", id, e.toString());
            run.markFailed(Instant.now(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        runs.save(run);
    }

    /** A run that was queued or running when the server stopped will never finish, so it must not look alive forever. */
    @EventListener(ApplicationReadyEvent.class)
    void failInterruptedRuns() {
        List<AnalysisRun> stuck = runs.findByStatusIn(List.of(AnalysisStatus.QUEUED, AnalysisStatus.RUNNING));
        stuck.forEach(r -> r.markFailed(Instant.now(), "interrupted by a server restart"));
        runs.saveAll(stuck);
    }

    public AnalysisRun get(String id) {
        return runs.findById(id).orElseThrow(() -> new RunNotFoundException(id));
    }
}
