/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.semantic;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A deterministic stand-in for a provider: one dimension per keyword, counting
 * its occurrences, plus a constant dimension so no vector is all zeros. Texts
 * sharing keywords are similar, texts sharing none are not.
 */
public class KeywordEmbeddingModel implements EmbeddingModel {
    private final List<String> keywords;
    public final AtomicInteger requests = new AtomicInteger();
    public final AtomicInteger embeddedTexts = new AtomicInteger();
    public volatile RuntimeException failure;

    public KeywordEmbeddingModel(String... keywords) {
        this.keywords = List.of(keywords);
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        requests.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        List<Embedding> embeddings = new ArrayList<>();
        for (TextSegment segment : textSegments) {
            embeddedTexts.incrementAndGet();
            String text = segment.text().toLowerCase(Locale.ROOT);
            float[] vector = new float[keywords.size() + 1];
            for (int i = 0; i < keywords.size(); i++) {
                vector[i] = text.split(keywords.get(i), -1).length - 1;
            }
            vector[keywords.size()] = 0.1f;
            embeddings.add(Embedding.from(vector));
        }
        return Response.from(embeddings);
    }
}
