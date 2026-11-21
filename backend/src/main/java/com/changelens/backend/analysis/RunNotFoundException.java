package com.changelens.backend.analysis;

public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(String id) {
        super("no analysis with id '" + id + "'");
    }
}
