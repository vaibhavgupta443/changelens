package com.changelens.core.parse;

/** A file's path (repo-relative, forward slashes) and its text. */
public record SourceFile(String path, String content) {
}
