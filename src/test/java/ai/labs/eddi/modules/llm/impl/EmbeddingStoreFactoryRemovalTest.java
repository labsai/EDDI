/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Re-embedding removes a knowledge base's vectors, and a read never creates
 * anything in the vector database.
 */
@DisplayName("EmbeddingStoreFactory — removing a knowledge base's vectors")
class EmbeddingStoreFactoryRemovalTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";

    private final GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
    private final SecretResolver secrets = mock(SecretResolver.class);

    @BeforeEach
    void setUp() {
        when(variables.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(secrets.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static RagConfiguration inMemory() {
        var config = new RagConfiguration();
        config.setName("docs");
        config.setStoreNamespace("id");
        config.setStoreType("in-memory");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("model", "m"));
        return config;
    }

    @Test
    @DisplayName("an in-memory knowledge base's store is dropped, and the next write starts a fresh one")
    void inMemoryStoreIsDropped() {
        EmbeddingStoreFactory factory = EmbeddingStoreFactoryInMemoryTest.offlineFactory(variables, secrets, 100, Ticker.systemTicker());
        RagConfiguration config = inMemory();
        EmbeddingStore<TextSegment> before = factory.getOrCreateForIngestion(KB_ID, config);
        before.add(Embedding.from(new float[]{0.1f, 0.2f}), TextSegment.from("old"));

        factory.removeKnowledgeBaseVectors(KB_ID, config);

        EmbeddingStore<TextSegment> after = factory.getOrCreateForIngestion(KB_ID, config);
        assertEquals(0, after.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{0.1f, 0.2f}))
                .maxResults(10).minScore(0.0).build()).matches().size());
    }

    @Test
    @DisplayName("an external store loses the chunks tagged with the knowledge base's id — and only those")
    @SuppressWarnings("unchecked")
    void externalStoreIsEmptiedByTag() {
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        EmbeddingStoreFactory factory = new EmbeddingStoreFactory(variables, secrets) {
            @Override
            EmbeddingStore<TextSegment> build(String ragConfigId, RagConfiguration config) {
                return store;
            }
        };
        var config = inMemory();
        config.setStoreType("qdrant");

        factory.removeKnowledgeBaseVectors(KB_ID, config);

        ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
        verify(store).removeAll(filter.capture());
        assertEquals(metadataKey(KnowledgeBaseStorage.METADATA_KB_ID)
                .isEqualTo(KB_ID).toString(), filter.getValue().toString());
    }

    @Test
    @DisplayName("a store that cannot delete by metadata says so, in words that name what to empty")
    @SuppressWarnings("unchecked")
    void unsupportedStoreIsExplained() {
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        doThrow(new UnsupportedFeatureException("no")).when(store).removeAll(any(Filter.class));
        EmbeddingStoreFactory factory = new EmbeddingStoreFactory(variables, secrets) {
            @Override
            EmbeddingStore<TextSegment> build(String ragConfigId, RagConfiguration config) {
                return store;
            }
        };
        var config = inMemory();
        config.setStoreType("qdrant");

        var e = assertThrows(UnsupportedOperationException.class, () -> factory.removeKnowledgeBaseVectors(KB_ID, config));

        assertTrue(e.getMessage().contains("collectionName"), e.getMessage());
    }

    @Test
    @DisplayName("the stand-in for a table that does not exist yet reads as empty and refuses writes")
    void absentTableReadsAsEmpty() {
        EmbeddingStore<TextSegment> absent = EmbeddingStoreFactory.ABSENT_TABLE;

        assertEquals(List.of(), absent.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1f}))
                .maxResults(5).build()).matches());
        absent.removeAll(mock(Filter.class));
        assertThrows(IllegalStateException.class, () -> absent.add(Embedding.from(new float[]{1f})));
        assertThrows(IllegalStateException.class, () -> absent.addAll(List.of(Embedding.from(new float[]{1f}))));
    }
}
