package com.changelens.backend.analysis;

import java.time.Instant;

/** What the API returns for a run: everything except the (large) rendered reports, which have their own endpoint. */
public record AnalysisView(
        String id, String repo, String base, String head, AnalysisStatus status, Instant createdAt, Instant startedAt,
        Instant finishedAt, Long durationMs, Integer typesChanged, Integer flowsTouched, String error) {

    static AnalysisView of(AnalysisRun r) {
        return new AnalysisView(r.getId(), r.getRepo(), r.getBase(), r.getHead(), r.getStatus(), r.getCreatedAt(), r.getStartedAt(),
                r.getFinishedAt(), r.getDurationMs(), r.getTypesChanged(), r.getFlowsTouched(), r.getError());
    }
}
