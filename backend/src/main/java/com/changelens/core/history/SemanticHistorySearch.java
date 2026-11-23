package com.changelens.core.history;

import com.changelens.core.history.HistoryIndex.Entry;
import com.changelens.core.history.HistoryIndex.Hit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds commits by meaning as well as by words. A keyword search cannot connect "draw pictures from a text prompt" with a commit
 * that says "image generation"; a sentence-embedding model can. The two rankings are combined with reciprocal rank fusion, so a
 * commit that both rankings like beats one that only one of them likes, and neither score scale has to be tuned against the other.
 *
 * <p>Results are still real commits from the index. The model only changes their order; it never writes text.
 */
public final class SemanticHistorySearch {

    public enum Mode { KEYWORD, SEMANTIC, HYBRID }

    private static final int BATCH = 64;
    private static final int FUSION_DEPTH = 50;
    private static final double RRF_K = 60;
    /**
     * Meaning-based hits below this cosine similarity are dropped. Nearest-neighbour search always has a nearest neighbour, so without a
     * floor a question about nothing in the history would still return commits. Calibrated on real data (see tools/history-eval).
     */
    static final double DEFAULT_MIN_SIMILARITY = Double.parseDouble(System.getProperty("changelens.minSimilarity", "0.45"));

    private final HistoryIndex index;
    private final TextEmbedder embedder;
    private final double minSimilarity;
    private volatile float[][] vectors;

    public SemanticHistorySearch(HistoryIndex index, TextEmbedder embedder) {
        this(index, embedder, DEFAULT_MIN_SIMILARITY);
    }

    public SemanticHistorySearch(HistoryIndex index, TextEmbedder embedder, double minSimilarity) {
        this.index = index;
        this.embedder = embedder;
        this.minSimilarity = minSimilarity;
    }

    public List<Hit> search(String query, int limit, Mode mode) {
        if (query == null || query.isBlank() || limit < 1) {
            return List.of();
        }
        return switch (mode) {
            case KEYWORD -> index.search(query, limit);
            case SEMANTIC -> semantic(query, limit);
            case HYBRID -> hybrid(query, limit);
        };
    }

    private List<Hit> semantic(String query, int limit) {
        float[] q = normalised(embedder.embed(List.of(query)).get(0));
        float[][] docs = vectors();
        List<Integer> order = new ArrayList<>();
        double[] score = new double[docs.length];
        for (int i = 0; i < docs.length; i++) {
            score[i] = dot(q, docs[i]);
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingDouble(i -> -score[i]).thenComparing(i -> i));
        List<Entry> entries = index.entries();
        return order.stream().filter(i -> score[i] >= minSimilarity).limit(limit).map(i -> new Hit(entries.get(i), score[i], List.of())).toList();
    }

    private List<Hit> hybrid(String query, int limit) {
        List<Hit> keyword = index.search(query, FUSION_DEPTH);
        List<Hit> meaning = semantic(query, FUSION_DEPTH);
        Map<String, Double> fused = new HashMap<>();
        Map<String, Hit> byCommit = new HashMap<>();
        Map<String, List<String>> words = new HashMap<>();
        for (int r = 0; r < keyword.size(); r++) {
            String sha = keyword.get(r).entry().sha();
            fused.merge(sha, 1.0 / (RRF_K + r + 1), Double::sum);
            byCommit.put(sha, keyword.get(r));
            words.put(sha, keyword.get(r).matchedTerms());
        }
        for (int r = 0; r < meaning.size(); r++) {
            String sha = meaning.get(r).entry().sha();
            fused.merge(sha, 1.0 / (RRF_K + r + 1), Double::sum);
            byCommit.putIfAbsent(sha, meaning.get(r));
        }
        return fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(e -> new Hit(byCommit.get(e.getKey()).entry(), e.getValue(), words.getOrDefault(e.getKey(), List.of())))
                .toList();
    }

    /** Embeds every commit once, the first time meaning is needed. */
    private float[][] vectors() {
        float[][] v = vectors;
        if (v == null) {
            synchronized (this) {
                if (vectors == null) {
                    List<Entry> entries = index.entries();
                    float[][] all = new float[entries.size()][];
                    for (int from = 0; from < entries.size(); from += BATCH) {
                        int to = Math.min(from + BATCH, entries.size());
                        List<String> texts = entries.subList(from, to).stream().map(HistoryIndex::embeddingText).toList();
                        List<float[]> out = embedder.embed(texts);
                        for (int i = 0; i < out.size(); i++) {
                            all[from + i] = normalised(out.get(i));
                        }
                    }
                    vectors = all;
                }
                v = vectors;
            }
        }
        return v;
    }

    static float[] normalised(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
