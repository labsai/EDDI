/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What deleting or updating a knowledge base does to its in-memory vector
 * store.
 *
 * <p>
 * An in-memory store is no longer evicted on its own, so the knowledge base's
 * lifecycle is the only thing that frees it: a delete that leaves no readable
 * version, or an update after which the store is unreachable (renamed, moved to
 * another store type) or meaningless (another embedding model). Anything else —
 * a delete of an older version, an update of chunking or retrieval settings —
 * must keep the documents.
 */
@DisplayName("RestRagStore — in-memory vector store lifecycle")
class RestRagStoreInMemoryStoreTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String KB_NAME = "product-docs";

    private IRagStore ragStore;
    private EmbeddingStoreFactory storeFactory;
    private RestRagStore restRagStore;

    @BeforeEach
    void setUp() throws Exception {
        ragStore = mock(IRagStore.class);
        storeFactory = new EmbeddingStoreFactory(mock(GlobalVariableResolver.class), mock(SecretResolver.class), 1000);
        restRagStore = new RestRagStore(ragStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class), mock(RagSourceIngestionService.class), storeFactory);
        when(ragStore.read(eq(KB_ID), anyInt())).thenReturn(inMemoryKnowledgeBase());
        when(ragStore.update(eq(KB_ID), anyInt(), any(RagConfiguration.class))).thenReturn(2);
    }

    private static RagConfiguration inMemoryKnowledgeBase() {
        var config = new RagConfiguration();
        config.setName(KB_NAME);
        config.setStoreType("in-memory");
        return config;
    }

    private EmbeddingStore<TextSegment> storeWithOneSegment() {
        var store = storeFactory.getOrCreateForIngestion(inMemoryKnowledgeBase(), KB_NAME);
        store.add(Embedding.from(new float[]{0.1f, 0.2f}), TextSegment.from("a fact"));
        return store;
    }

    private int segmentsNow() {
        return storeFactory.getOrCreate(inMemoryKnowledgeBase(), KB_NAME)
                .search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(new float[]{0.1f, 0.2f}))
                        .minScore(0.0)
                        .build())
                .matches().size();
    }

    private static IResourceStore.IResourceId currentVersion(int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return KB_ID;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    @Test
    @DisplayName("deleting the last version drops the store and its documents")
    void deleteDropsTheStore() throws Exception {
        var store = storeWithOneSegment();
        when(ragStore.getCurrentResourceId(KB_ID)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

        restRagStore.deleteRag(KB_ID, 1, false);

        assertNotSame(store, storeFactory.getOrCreate(inMemoryKnowledgeBase(), KB_NAME));
        assertEquals(0, segmentsNow());
    }

    @Test
    @DisplayName("deleting an older version keeps the live version's documents")
    void deletingAnOlderVersionKeepsTheStore() throws Exception {
        var store = storeWithOneSegment();
        when(ragStore.getCurrentResourceId(KB_ID)).thenReturn(currentVersion(3));

        restRagStore.deleteRag(KB_ID, 1, false);

        assertSame(store, storeFactory.getOrCreate(inMemoryKnowledgeBase(), KB_NAME));
        assertEquals(1, segmentsNow());
    }

    @Test
    @DisplayName("an update that keeps name and embedding configuration keeps the documents")
    void ordinaryUpdateKeepsTheStore() {
        var store = storeWithOneSegment();
        var updated = inMemoryKnowledgeBase();
        updated.setChunkSize(256);

        restRagStore.updateRag(KB_ID, 1, updated);

        assertSame(store, storeFactory.getOrCreate(inMemoryKnowledgeBase(), KB_NAME));
        assertEquals(1, segmentsNow());
    }

    @Test
    @DisplayName("renaming drops the store under the old name, which nothing can reach any more")
    void renameDropsTheOldStore() {
        var store = storeWithOneSegment();
        var renamed = inMemoryKnowledgeBase();
        renamed.setName("renamed-docs");

        restRagStore.updateRag(KB_ID, 1, renamed);

        assertNotSame(store, storeFactory.getOrCreate(inMemoryKnowledgeBase(), KB_NAME));
    }
}
