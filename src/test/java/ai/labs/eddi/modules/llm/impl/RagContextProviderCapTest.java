/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.RagContextProvider.RetrievalResult;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding F7 — {@code formatRagContext} concatenated every chunk from every
 * matched knowledge base with no total limit, so {@code enableWorkflowRag}
 * across N knowledge bases grew the system prompt until the provider rejected
 * the request.
 */
@DisplayName("RagContextProvider — RAG block is bounded (F7)")
class RagContextProviderCapTest {

    private static List<RetrievalResult> results(int knowledgeBases, int chunksPerKb, int chunkChars) {
        var all = new ArrayList<RetrievalResult>();
        for (int kb = 0; kb < knowledgeBases; kb++) {
            for (int c = 0; c < chunksPerKb; c++) {
                all.add(new RetrievalResult("kb-" + kb, Content.from(TextSegment.from("C".repeat(chunkChars)))));
            }
        }
        return all;
    }

    @Test
    @DisplayName("10 knowledge bases cannot push the prompt past the cap")
    void tenKnowledgeBasesStayBounded() {
        var retrieved = results(10, 5, 2000); // 100_000 chars of raw chunks
        int cap = 8000;

        String formatted = RagContextProvider.formatRagContext(retrieved, cap);

        assertTrue(formatted.length() <= cap + 200,
                "formatted context must stay near the cap, was " + formatted.length());
        assertTrue(formatted.contains("omitted"), "the omission must be visible to the model: tail=" + tail(formatted));
    }

    @Test
    @DisplayName("without the cap the same retrieval is unbounded — the regression this guards")
    void unboundedWhenCapDisabled() {
        var retrieved = results(10, 5, 2000);

        String formatted = RagContextProvider.formatRagContext(retrieved, -1);

        assertTrue(formatted.length() > 90_000, "cap disabled restores the old unbounded behaviour, was " + formatted.length());
    }

    @Test
    @DisplayName("a retrieval that fits is formatted unchanged, with source headers")
    void smallRetrievalUnchanged() {
        var retrieved = List.of(
                new RetrievalResult("docs", Content.from(TextSegment.from("alpha"))),
                new RetrievalResult("faq", Content.from(TextSegment.from("beta"))));

        String formatted = RagContextProvider.formatRagContext(retrieved, 8000);

        assertTrue(formatted.contains("### Source: docs"));
        assertTrue(formatted.contains("### Source: faq"));
        assertTrue(formatted.contains("alpha"));
        assertTrue(formatted.contains("beta"));
    }

    @Test
    @DisplayName("the task's maxRagContextChars drives the cap and defaults to a real bound")
    void resolvesCapFromTask() {
        var task = new LlmConfiguration.Task();
        assertEquals(20000, RagContextProvider.resolveMaxChars(task));

        task.setMaxRagContextChars(1234);
        assertEquals(1234, RagContextProvider.resolveMaxChars(task));

        task.setMaxRagContextChars(-1);
        assertEquals(-1, RagContextProvider.resolveMaxChars(task));

        task.setMaxRagContextChars(null);
        assertEquals(-1, RagContextProvider.resolveMaxChars(task));
    }

    private static String tail(String s) {
        return s.length() > 120 ? s.substring(s.length() - 120) : s;
    }
}
