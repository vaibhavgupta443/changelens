package com.changelens.core.ingest;

import com.changelens.core.model.CodeModel.ResourceFile;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.parse.JavaSnapshotBuilder;
import com.changelens.core.parse.ParseCache;
import com.changelens.core.parse.SourceFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Reads a repository at any commit straight from git objects (no checkout, no working tree), so a base and a head can be
 * analysed side by side and the same code works for a local clone and for a freshly fetched pull-request ref.
 */
public final class GitSnapshotLoader {

    private static final long MAX_FILE_BYTES = 1_500_000;
    private static final Pattern SKIP_DIR = Pattern.compile("(^|/)(target|build|node_modules|\\.git|\\.idea|generated|out)/");
    private static final Pattern RESOURCE = Pattern.compile(
            "(^|/)(pom\\.xml|build\\.gradle(\\.kts)?|settings\\.gradle(\\.kts)?|CODEOWNERS|application[^/]*\\.(ya?ml|properties)"
                    + "|bootstrap[^/]*\\.(ya?ml|properties)|openapi[^/]*\\.(ya?ml|json)|swagger[^/]*\\.(ya?ml|json))$|(^|/)db/migration/[^/]+\\.sql$"
                    + "|(^|/)src/main/resources/.+\\.(sql|ya?ml|properties)$");

    private GitSnapshotLoader() {
    }

    public static Snapshot load(Path repoDir, String revision, String repoName) throws IOException {
        return load(repoDir, revision, repoName, new ParseCache());
    }

    /** Loads with a shared {@link ParseCache}, so files that did not change between two revisions are parsed once. */
    public static Snapshot load(Path repoDir, String revision, String repoName, ParseCache cache) throws IOException {
        Path gitDir = repoDir.resolve(".git");
        FileRepositoryBuilder b = new FileRepositoryBuilder();
        b = java.nio.file.Files.isDirectory(gitDir) ? b.setGitDir(gitDir.toFile()) : b.setGitDir(repoDir.toFile()).setBare();
        try (Repository repo = b.build(); RevWalk walk = new RevWalk(repo)) {
            ObjectId id = repo.resolve(revision);
            if (id == null) {
                throw new IOException("unknown revision: " + revision);
            }
            RevCommit commit = walk.parseCommit(id);
            List<SourceFile> java = new ArrayList<>();
            List<ResourceFile> resources = new ArrayList<>();
            try (TreeWalk tw = new TreeWalk(repo)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next()) {
                    String path = tw.getPathString();
                    if (SKIP_DIR.matcher(path).find()) {
                        continue;
                    }
                    boolean isJava = path.endsWith(".java");
                    boolean isResource = !isJava && RESOURCE.matcher(path).find();
                    if (!isJava && !isResource) {
                        continue;
                    }
                    ObjectLoader loader = repo.open(tw.getObjectId(0));
                    if (loader.getSize() > MAX_FILE_BYTES) {
                        continue;
                    }
                    String text = new String(loader.getBytes(), StandardCharsets.UTF_8);
                    if (isJava) {
                        java.add(new SourceFile(path, text));
                    } else {
                        resources.add(new ResourceFile(path, kindOf(path), text));
                    }
                }
            }
            return JavaSnapshotBuilder.build(repoName, commit.getName().substring(0, 10), java, resources, cache);
        }
    }

    static String kindOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith("pom.xml") || p.contains("build.gradle") || p.contains("settings.gradle")) {
            return "build";
        }
        if (p.endsWith("codeowners")) {
            return "codeowners";
        }
        if (p.contains("openapi") || p.contains("swagger")) {
            return "openapi";
        }
        if (p.contains("db/migration/") || p.endsWith(".sql")) {
            return "migration";
        }
        return "config";
    }
}
