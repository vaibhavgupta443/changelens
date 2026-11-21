package com.changelens.backend.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** One requested comparison of two revisions of a repository, with its status and (when done) its rendered reports. */
@Entity
@Table(name = "analysis_run", indexes = @Index(name = "idx_analysis_created", columnList = "createdAt"))
public class AnalysisRun {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 100)
    private String repo;

    @Column(nullable = false, length = 200)
    private String base;

    @Column(nullable = false, length = 200)
    private String head;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AnalysisStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant startedAt;
    private Instant finishedAt;
    private Long durationMs;
    private Integer typesChanged;
    private Integer flowsTouched;

    @Column(length = 2000)
    private String error;

    @Column(length = 2_000_000)
    private String prComment;

    @Column(length = 2_000_000)
    private String markdown;

    protected AnalysisRun() {
        // for JPA
    }

    public AnalysisRun(String id, String repo, String base, String head, Instant createdAt) {
        this.id = id;
        this.repo = repo;
        this.base = base;
        this.head = head;
        this.status = AnalysisStatus.QUEUED;
        this.createdAt = createdAt;
    }

    public void markRunning(Instant now) {
        this.status = AnalysisStatus.RUNNING;
        this.startedAt = now;
    }

    public void markDone(Instant now, long durationMs, int typesChanged, int flowsTouched, String prComment, String markdown) {
        this.status = AnalysisStatus.DONE;
        this.finishedAt = now;
        this.durationMs = durationMs;
        this.typesChanged = typesChanged;
        this.flowsTouched = flowsTouched;
        this.prComment = prComment;
        this.markdown = markdown;
    }

    public void markFailed(Instant now, String message) {
        this.status = AnalysisStatus.FAILED;
        this.finishedAt = now;
        this.error = message == null ? "unknown error" : message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    public String getId() {
        return id;
    }

    public String getRepo() {
        return repo;
    }

    public String getBase() {
        return base;
    }

    public String getHead() {
        return head;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Integer getTypesChanged() {
        return typesChanged;
    }

    public Integer getFlowsTouched() {
        return flowsTouched;
    }

    public String getError() {
        return error;
    }

    public String getPrComment() {
        return prComment;
    }

    public String getMarkdown() {
        return markdown;
    }
}
