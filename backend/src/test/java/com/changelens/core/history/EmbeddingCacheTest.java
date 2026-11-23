package com.changelens.core.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmbeddingCacheTest {

    @TempDir
    Path root;

    @TempDir
    Path repo;

    private static String sha(int n) {
        return String.format("%040x", n);
    }

    private static Map<String, float[]> vectors(int count) {
        Map<String, float[]> m = new LinkedHashMap<>();
        for (int i = 1; i <= count; i++) {
            m.put(sha(i), new float[] { i, i / 2f, -i });
        }
        return m;
    }

    @Test
    void vectorsComeBackExactlyAsTheyWentIn() {
        EmbeddingCache cache = EmbeddingCache.forRepository(root, "model-a", repo);

        cache.save(vectors(3));
        Map<String, float[]> back = EmbeddingCache.forRepository(root, "model-a", repo).load();

        assertThat(back).containsOnlyKeys(sha(1), sha(2), sha(3));
        assertThat(back.get(sha(2))).containsExactly(2f, 1f, -2f);
        assertThat(back.keySet()).containsExactly(sha(1), sha(2), sha(3)); // order kept: oldest first
    }

    @Test
    void anEmptyOrMissingCacheLoadsAsEmpty() {
        EmbeddingCache cache = EmbeddingCache.forRepository(root, "model-a", repo);

        assertThat(cache.load()).isEmpty();
        cache.save(Map.of());
        assertThat(cache.file()).doesNotExist();
    }

    @Test
    void aDamagedFileIsTreatedAsAnEmptyCacheNotAnError() throws IOException {
        EmbeddingCache cache = EmbeddingCache.forRepository(root, "model-a", repo);
        cache.save(vectors(5));
        byte[] good = Files.readAllBytes(cache.file());

        Files.write(cache.file(), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 }); // not our format
        assertThat(cache.load()).isEmpty();

        Files.write(cache.file(), java.util.Arrays.copyOf(good, good.length - 7)); // cut off in the middle of a vector
        assertThat(cache.load()).isEmpty();

        Files.write(cache.file(), new byte[0]);
        assertThat(cache.load()).isEmpty();

        cache.save(vectors(2)); // and it heals on the next save
        assertThat(cache.load()).hasSize(2);
    }

    @Test
    void aDifferentModelOrRepositoryNeverReadsTheseVectors(@TempDir Path otherRepo) {
        EmbeddingCache a = EmbeddingCache.forRepository(root, "model-a", repo);
        a.save(vectors(3));

        assertThat(EmbeddingCache.forRepository(root, "model-b", repo).load()).isEmpty();
        assertThat(EmbeddingCache.forRepository(root, "model-a", otherRepo).load()).isEmpty();
        assertThat(EmbeddingCache.forRepository(root, "model-a", repo).load()).hasSize(3);
    }

    @Test
    void theCacheLivesUnderItsRootAndNeverInsideTheRepository() {
        EmbeddingCache cache = EmbeddingCache.forRepository(root, "all-minilm/l6 v2", repo);

        cache.save(vectors(1));

        assertThat(cache.file()).startsWith(root).exists();
        assertThat(cache.file().startsWith(repo)).isFalse();
        assertThat(cache.file().getParent().getFileName().toString()).doesNotContain("/").doesNotContain(" ");
        assertThat(repo).isEmptyDirectory();
    }

    @Test
    void savingLeavesNoTemporaryFilesBehindAndOverwritesInPlace() throws IOException {
        EmbeddingCache cache = EmbeddingCache.forRepository(root, "model-a", repo);

        cache.save(vectors(3));
        cache.save(vectors(4));

        try (Stream<Path> files = Files.list(cache.file().getParent())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly(cache.file().getFileName().toString());
        }
        assertThat(cache.load()).hasSize(4);
    }

    @Test
    void aVeryLargeCacheKeepsTheNewestVectors() {
        EmbeddingCache cache = EmbeddingCache.forRepository(root, "model-a", repo);
        Map<String, float[]> many = new LinkedHashMap<>();
        for (int i = 1; i <= 20_005; i++) {
            many.put(sha(i), new float[] { i });
        }

        cache.save(many);
        Map<String, float[]> back = cache.load();

        assertThat(back).hasSize(20_000);
        assertThat(back).doesNotContainKey(sha(1)).containsKey(sha(6)).containsKey(sha(20_005));
    }

    @Test
    void entriesThatAreNotCommitIdsOrHaveTheWrongSizeAreLeftOut() {
        EmbeddingCache cache = EmbeddingCache.forRepository(root, "model-a", repo);
        Map<String, float[]> mixed = new LinkedHashMap<>();
        mixed.put(sha(1), new float[] { 1, 2, 3 });
        mixed.put("not-a-sha", new float[] { 1, 2, 3 });
        mixed.put(sha(2), new float[] { 1, 2 });

        cache.save(mixed);

        assertThat(cache.load()).containsOnlyKeys(sha(1));
    }
}
