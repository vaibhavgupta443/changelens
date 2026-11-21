package com.changelens.backend.analysis;

public class RunNotReadyException extends RuntimeException {

    public RunNotReadyException(String id, AnalysisStatus status) {
        super("analysis '" + id + "' has no report yet (status " + status + ")");
    }
}
