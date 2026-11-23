package com.changelens.core.history;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import java.util.List;

/**
 * Sentence embeddings from all-MiniLM-L6-v2 (quantised), running in-process on the CPU: no server, no API key, no cost and no data
 * leaving the machine. The model ships inside the library, which is why this class is only available in the "semantic" build.
 */
public final class MiniLmEmbedder implements TextEmbedder {

    private final AllMiniLmL6V2QuantizedEmbeddingModel model = new AllMiniLmL6V2QuantizedEmbeddingModel();

    @Override
    public List<float[]> embed(List<String> texts) {
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        return model.embedAll(segments).content().stream().map(Embedding::vector).toList();
    }

    @Override
    public String id() {
        return "all-minilm-l6-v2-q";
    }

    /**
     * @return the embedder, or null when the model is not on the classpath (the default build) or cannot start on this machine
     */
    public static TextEmbedder tryCreate() {
        try {
            return new MiniLmEmbedder();
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }
}
