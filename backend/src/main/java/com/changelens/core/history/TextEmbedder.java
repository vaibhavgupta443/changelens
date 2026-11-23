package com.changelens.core.history;

import java.util.List;

/** Turns texts into vectors so that texts with the same meaning end up close together, even when they share no words. */
public interface TextEmbedder {

    /** One vector per text, in the same order. */
    List<float[]> embed(List<String> texts);

    /** Names the model, so vectors from different models are never mixed in a cache. */
    default String id() {
        return "custom";
    }
}
