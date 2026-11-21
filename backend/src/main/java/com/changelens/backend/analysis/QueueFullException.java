package com.changelens.backend.analysis;

public class QueueFullException extends RuntimeException {

    public QueueFullException() {
        super("too many analyses are waiting, try again in a moment");
    }
}
