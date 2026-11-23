package com.changelens.mcp;

/** A problem with a tool call that the caller can fix. It is returned to the client as a tool error so it can correct itself and retry. */
final class ToolError extends RuntimeException {

    ToolError(String message) {
        super(message);
    }
}
