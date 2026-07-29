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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * builds a {@code DocumentSplitters.recursive} splitter — so any other value
 * was accepted at save time and then silently ignored.
 * {@link RagConfiguration#validate()} knew that already but ran only at
 * retrieval time; the store now runs it on the write path.
 * <p>
 * The two values the docs actually advertised ({@code "paragraph"},
 * {@code "sentence"}) are normalised rather than rejected — they exist in
 * stored configs and in exported ZIPs, and they already behaved as
 * {@code "recursive"}. Rejecting them made those knowledge bases un-updatable
 * and failed the whole agent import they appeared in.
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

    @SuppressWarnings("unchecked")
    private IResourceStorage.IResource<RagConfiguration> stubCreatableStorage() throws Exception {
        var createdResource = mock(IResourceStorage.IResource.class);
        when(resourceStorage.newResource(any(RagConfiguration.class))).thenReturn(createdResource);
        return createdResource;
    }

    @Test
    @DisplayName("create rejects a chunkStrategy that never existed and names the supported ones")
    void createRejectsUnknownChunkStrategy() throws Exception {
        var thrown = assertThrows(IllegalArgumentException.class, () -> ragStore.create(knowledgeBase("semantic")));

        assertTrue(thrown.getMessage().contains("semantic"), "the rejected value must appear in the message: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("recursive"), "the supported values must appear in the message: " + thrown.getMessage());
        verify(resourceStorage, never()).newResource(any());
    }

    @Test
    @DisplayName("update rejects a chunkStrategy that never existed before touching storage")
    void updateRejectsUnknownChunkStrategy() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ragStore.update("kb1", 1, knowledgeBase("kmeans")));

        verify(resourceStorage, never()).read(any(), any());
    }

    /**
     * A knowledge base carrying the previously advertised {@code "paragraph"} must
     * still save. Rejecting it broke every stored config and every exported ZIP
     * that used it, for a value whose runtime behaviour never differed from
     * {@code "recursive"}.
     */
    @Test
    @DisplayName("create normalises the advertised legacy 'paragraph' instead of failing the save")
    void createNormalisesLegacyParagraphStrategy() throws Exception {
        var createdResource = stubCreatableStorage();

        var config = knowledgeBase("paragraph");
        ragStore.create(config);

        assertEquals("recursive", config.getChunkStrategy(), "the legacy alias must be stored as what it always meant");
        verify(resourceStorage).newResource(config);
        verify(resourceStorage).store(createdResource);
    }

    @Test
    @DisplayName("create normalises the advertised legacy 'sentence' instead of failing the save")
    void createNormalisesLegacySentenceStrategy() throws Exception {
        stubCreatableStorage();

        var config = knowledgeBase("  Sentence  ");
        ragStore.create(config);

        assertEquals("recursive", config.getChunkStrategy());
    }

    /**
     * Import is the path that actually hurt: a single legacy value aborted the
     * whole agent ZIP with a 400.
     */
    @Test
    @DisplayName("a legacy chunkStrategy never propagates an exception out of create")
    void legacyChunkStrategyDoesNotAbortAnImport() throws Exception {
        stubCreatableStorage();

        ragStore.create(knowledgeBase("paragraph"));

        verify(resourceStorage).newResource(any(RagConfiguration.class));
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
