package com.changelens.core.history;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the vector of every commit on disk, so the model reads each commit once, ever, and a new session starts with the vectors of
 * the commits it already knows. A commit never changes, so a vector never goes stale; only commits that are new since the last
 * search are embedded.
 *
 * <p>One small file per repository and per model recipe, outside the repository. It holds commit ids and numbers, no message text.
 * A missing, truncated or foreign file is treated as an empty cache, never as an error: the cache can always be rebuilt.
 */
public final class EmbeddingCache {

    private static final int MAGIC = 0x434C4543; // "CLEC"
    private static final int MAX_ENTRIES = 20_000;

    private final Path file;

    private EmbeddingCache(Path file) {
        this.file = file;
    }

    /**
     * @param root     folder that holds all caches
     * @param recipe   identifies what was embedded and with which model; a different recipe never reads another's vectors
     * @param repoDir  the repository the commits belong to
     */
    public static EmbeddingCache forRepository(Path root, String recipe, Path repoDir) {
        String repoKey = sha1(repoDir.toAbsolutePath().normalize().toString());
        String safeRecipe = recipe.replaceAll("[^A-Za-z0-9._-]", "_");
        return new EmbeddingCache(root.resolve(safeRecipe).resolve(repoKey + ".vec"));
    }

    /** Vectors by commit id, in the order they were stored. Empty when there is nothing usable on disk. */
    public Map<String, float[]> load() {
        Map<String, float[]> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(new java.io.BufferedInputStream(in))) {
            if (data.readInt() != MAGIC) {
                return new LinkedHashMap<>();
            }
            int dimension = data.readInt();
            int count = data.readInt();
            if (dimension < 1 || dimension > 8192 || count < 0 || count > MAX_ENTRIES * 2) {
                return new LinkedHashMap<>();
            }
            byte[] id = new byte[40];
            for (int i = 0; i < count; i++) {
                data.readFully(id);
                float[] v = new float[dimension];
                for (int d = 0; d < dimension; d++) {
                    v[d] = data.readFloat();
                }
                out.put(new String(id, StandardCharsets.US_ASCII), v);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>(); // damaged or unreadable: start again
        }
    }

    /**
     * Replaces the file with these vectors (the newest {@value #MAX_ENTRIES} if there are more). Written to a temporary file and moved
     * into place, so a crash cannot leave a half-written cache. A failure to write is ignored: the next search just embeds again.
     */
    public void save(Map<String, float[]> vectors) {
        if (vectors.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "vec", ".tmp");
            int dimension = vectors.values().iterator().next().length;
            List<Map.Entry<String, float[]>> usable = vectors.entrySet().stream()
                    .filter(e -> e.getKey().length() == 40 && e.getValue().length == dimension).toList();
            List<Map.Entry<String, float[]>> keep = usable.subList(Math.max(0, usable.size() - MAX_ENTRIES), usable.size());
            try (OutputStream out = Files.newOutputStream(tmp); DataOutputStream data = new DataOutputStream(new java.io.BufferedOutputStream(out))) {
                data.writeInt(MAGIC);
                data.writeInt(dimension);
                data.writeInt(keep.size());
                for (Map.Entry<String, float[]> e : keep) {
                    data.write(e.getKey().getBytes(StandardCharsets.US_ASCII));
                    for (float f : e.getValue()) {
                        data.writeFloat(f);
                    }
                }
            } catch (IOException | RuntimeException ex) {
                Files.deleteIfExists(tmp);
                return;
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // the cache is an optimisation only
        }
    }

    /** Where this cache lives, for tests and for telling the user. */
    public Path file() {
        return file;
    }

    private static String sha1(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
