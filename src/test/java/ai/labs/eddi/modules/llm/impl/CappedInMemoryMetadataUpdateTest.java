/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SegmentMetadataUpdates} on the in-memory store: the matching chunks
 * get the new values under the same ids and vectors, nothing else moves, and
 * the cap is neither charged nor bypassed.
 */
class CappedInMemoryMetadataUpdateTest {

    private final AtomicInteger reserved = new AtomicInteger();
    private CappedInMemoryEmbeddingStore store;

    @BeforeEach
    void setUp() {
        Object lock = new Object();
        store = new CappedInMemoryEmbeddingStore("kb", new CappedInMemoryEmbeddingStore.CapacityGuard() {
            @Override
            public Object lock() {
                return lock;
            }

            @Override
            public void reserve(String knowledgeBaseId, int incoming) {
                reserved.addAndGet(incoming);
            }
        });
    }

    private void chunk(String id, String documentId, String sourceKey, String title, float[] vector) {
        var metadata = new Metadata(new HashMap<>(Map.of("documentId", documentId, "sourceKey", sourceKey,
                "title", title, "runId", "run-1", "url", "https://old/" + documentId)));
        store.add(id, Embedding.from(vector), TextSegment.from("text of " + id, metadata));
    }

    private List<EmbeddingMatch<TextSegment>> all() {
        return store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1f, 0f}))
                .maxResults(100).minScore(0.0).build()).matches();
    }

    private EmbeddingMatch<TextSegment> byId(String id) {
        return all().stream().filter(match -> match.embeddingId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("rewrites only the matching chunks, keeping their ids, vectors, text and other metadata")
    void updatesOnlyTheDocument() {
        chunk("a1", "doc-a", "src", "Old", new float[]{1f, 0f});
        chunk("a2", "doc-a", "src", "Old", new float[]{0f, 1f});
        chunk("b1", "doc-b", "src", "Other", new float[]{0.5f, 0.5f});
        chunk("x1", "doc-a", "another-source", "Old", new float[]{1f, 1f});
        int reservedBefore = reserved.get();

        var values = new HashMap<String, String>();
        values.put("title", "New");
        values.put("url", "https://new/doc-a");
        int changed = store.updateMetadata(Map.of("sourceKey", "src", "documentId", "doc-a"), values);

        assertEquals(2, changed);
        assertEquals(4, store.size());
        assertEquals(reservedBefore, reserved.get(), "a rewrite is not charged to the cap");
        for (String id : List.of("a1", "a2")) {
            TextSegment segment = byId(id).embedded();
            assertEquals("New", segment.metadata().getString("title"));
            assertEquals("https://new/doc-a", segment.metadata().getString("url"));
            assertEquals("run-1", segment.metadata().getString("runId"));
            assertEquals("text of " + id, segment.text());
        }
        assertArrayEquals(new float[]{0f, 1f}, byId("a2").embedding().vector());
        assertEquals("Other", byId("b1").embedded().metadata().getString("title"));
        assertEquals("Old", byId("x1").embedded().metadata().getString("title"), "another source's copy stays");
    }

    @Test
    @DisplayName("a chunk whose vector is the opposite of all ones is still found")
    void oppositeVectorIsFound() {
        chunk("n1", "doc-n", "src", "Old", new float[]{-1f, -1f});

        assertEquals(1, store.updateMetadata(Map.of("documentId", "doc-n"), Map.of("title", "New")));
        assertEquals("New", byId("n1").embedded().metadata().getString("title"));
    }

    @Test
    @DisplayName("an empty store, or no match, changes nothing")
    void nothingToChange() {
        assertEquals(0, store.updateMetadata(Map.of("documentId", "doc-a"), Map.of("title", "New")));
        chunk("b1", "doc-b", "src", "Other", new float[]{1f, 0f});
        assertEquals(0, store.updateMetadata(Map.of("documentId", "doc-a"), Map.of("title", "New")));
        assertEquals("Other", byId("b1").embedded().metadata().getString("title"));
    }

    @Test
    @DisplayName("a null value removes the key")
    void nullRemoves() {
        chunk("a1", "doc-a", "src", "Old", new float[]{1f, 0f});
        var values = new HashMap<String, String>();
        values.put("url", null);
        store.updateMetadata(Map.of("documentId", "doc-a"), values);
        assertFalse(byId("a1").embedded().metadata().containsKey("url"));
    }

    @Test
    @DisplayName("refuses an update that would match everything, or a key it could not splice safely")
    void refusesBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> store.updateMetadata(Map.of(), Map.of("title", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> store.updateMetadata(Map.of("documentId') OR ('1", "x"), Map.of("title", "x")));
        assertThrows(IllegalArgumentException.class, () -> store.updateMetadata(Map.of("documentId", "a"), Map.of()));
    }

    @Test
    @DisplayName("a retired store refuses the rewrite like any other write")
    void retiredRefuses() {
        chunk("a1", "doc-a", "src", "Old", new float[]{1f, 0f});
        store.retire();
        assertThrows(InMemoryStoreRetiredException.class,
                () -> store.updateMetadata(Map.of("documentId", "doc-a"), Map.of("title", "New")));
    }
}
