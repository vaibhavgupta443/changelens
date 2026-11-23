package com.changelens.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The folder that holds the repositories an MCP client may ask about. A client names a repository by folder name, never by path, and
 * the resolved path is checked again to stay inside the workspace, so a prompt-injected or careless client cannot make the server
 * read an arbitrary folder.
 */
final class Workspace {

    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    /** A git revision. It cannot start with a dash, so it can never be read as a command-line option. */
    private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9._/~^@{}][A-Za-z0-9._/~^@{}-]{0,199}");

    private final Path root;

    Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    /** @throws ToolError if the name is not a folder name or is not a git repository inside the workspace */
    Path resolve(String repo) {
        if (repo == null || !REPO.matcher(repo).matches()) {
            throw new ToolError("'repo' must be a repository folder name inside the workspace, not a path. "
                    + "Use list_repositories to see what is available.");
        }
        Path candidate = root.resolve(repo).normalize();
        if (!candidate.startsWith(root) || candidate.equals(root) || !isRepository(candidate)) {
            throw new ToolError("No git repository named '" + repo + "' in the workspace. Use list_repositories to see what is available.");
        }
        return candidate;
    }

    String revision(String name, String value) {
        if (value == null || !REVISION.matcher(value).matches()) {
            throw new ToolError("'" + name + "' is not a valid git revision (for example main, origin/main, HEAD~1, a branch, tag or commit).");
        }
        return value;
    }

    List<String> repositories() {
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory).filter(Workspace::isRepository)
                    .map(p -> p.getFileName().toString()).filter(n -> REPO.matcher(n).matches()).sorted().toList();
        } catch (IOException e) {
            throw new ToolError("The workspace folder cannot be read: " + root);
        }
    }

    private static boolean isRepository(Path dir) {
        return Files.isDirectory(dir.resolve(".git")) || Files.isRegularFile(dir.resolve("HEAD"));
    }
}
