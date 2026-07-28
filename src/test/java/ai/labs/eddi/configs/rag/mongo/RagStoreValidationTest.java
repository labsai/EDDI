/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.mongo;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding I3 leftover: {@code chunkStrategy} has no reader — ingestion always
 * builds a {@code DocumentSplitters.recursive} splitter — so
 * {@code "paragraph"} / {@code "sentence"} were accepted at save time and then
 * silently ignored. {@link RagConfiguration#validate()} knew that already but
 * ran only at retrieval time; the store now runs it on the write path.
 */
@DisplayName("RagStore — save-time chunkStrategy validation")
class RagStoreValidationTest {

    private IResourceStorage<RagConfiguration> resourceStorage;
    private RagStore ragStore;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        var storageFactory = mock(IResourceStorageFactory.class);
        resourceStorage = mock(IResourceStorage.class);

        when(storageFactory.create(eq("rags"), any(), eq(RagConfiguration.class), any(String[].class))).thenReturn(resourceStorage);

        ragStore = new RagStore(storageFactory, mock(IDocumentBuilder.class));
    }

    private static RagConfiguration knowledgeBase(String chunkStrategy) {
        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setChunkStrategy(chunkStrategy);
        return config;
    }

    @Test
    @DisplayName("create rejects an unimplemented chunkStrategy and names the supported ones")
    void createRejectsUnimplementedChunkStrategy() throws Exception {
        var thrown = assertThrows(IllegalArgumentException.class, () -> ragStore.create(knowledgeBase("paragraph")));

        assertTrue(thrown.getMessage().contains("paragraph"), "the rejected value must appear in the message: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("recursive"), "the supported values must appear in the message: " + thrown.getMessage());
        verify(resourceStorage, never()).newResource(any());
    }

    @Test
    @DisplayName("update rejects an unimplemented chunkStrategy before touching storage")
    void updateRejectsUnimplementedChunkStrategy() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ragStore.update("kb1", 1, knowledgeBase("sentence")));

        verify(resourceStorage, never()).read(any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("the implemented chunkStrategy is still persisted")
    void recursiveChunkStrategyIsAccepted() throws Exception {
        var createdResource = mock(IResourceStorage.IResource.class);
        when(resourceStorage.newResource(any(RagConfiguration.class))).thenReturn(createdResource);

        var config = knowledgeBase("recursive");
        ragStore.create(config);

        verify(resourceStorage).newResource(config);
        verify(resourceStorage).store(createdResource);
    }
}
