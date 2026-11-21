package com.changelens.backend.analysis;

public class UnknownRepositoryException extends RuntimeException {

    public UnknownRepositoryException(String repo) {
        super("no repository named '" + repo + "' in the workspace");
    }
}
