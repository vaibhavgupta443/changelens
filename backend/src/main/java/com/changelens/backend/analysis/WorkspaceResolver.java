package com.changelens.backend.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Turns a repository name from a request into a folder, and only ever inside the configured workspace. The name is checked by
 * validation already; this is the second, independent check, because it is the one that keeps the API from reading arbitrary
 * folders on the server.
 */
@Component
public class WorkspaceResolver {

    private final Path root;

    public WorkspaceResolver(ChangeLensProperties properties) {
        this.root = properties.workspace().toAbsolutePath().normalize();
    }

    public Path resolve(String repo) {
        Path candidate = root.resolve(repo).normalize();
        if (!candidate.startsWith(root) || candidate.equals(root)) {
            throw new UnknownRepositoryException(repo);
        }
        boolean isRepo = Files.isDirectory(candidate.resolve(".git")) || Files.isRegularFile(candidate.resolve("HEAD"));
        if (!isRepo) {
            throw new UnknownRepositoryException(repo);
        }
        return candidate;
    }

    public Path root() {
        return root;
    }
}
