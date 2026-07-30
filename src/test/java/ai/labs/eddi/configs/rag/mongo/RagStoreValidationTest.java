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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding I3 leftover: {@code chunkStrategy} has no reader — ingestion always
 * builds a {@code DocumentSplitters.recursive} splitter — so any other value
 * was accepted at save time and then silently ignored.
 * <p>
 * What the store does on write is <strong>normalise, never reject</strong>. The
 * two values the docs actually advertised ({@code "paragraph"},
 * {@code "sentence"}) are rewritten to the {@code "recursive"} they always
 * meant; anything else is stored verbatim. Rejecting here broke the three
 * callers that replay an existing document — see the comment on
 * {@link #createDoesNotRejectUnknownChunkStrategy()}.
 * <p>
 * The author-facing rejection is asserted in
 * {@code RestRagStoreWriteValidationTest} (the write boundary) and the two
 * layers are asserted together in {@code RagStoreLayeringTest}.
 */
@DisplayName("RagStore — save-time chunkStrategy normalisation")
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

    /**
     * These two cases previously asserted that the store <em>rejects</em> an
     * unsupported strategy. That assertion was the bug: this hook runs on every
     * write, including the three callers that replay a document which already
     * exists — {@code duplicateRag}, {@code RestImportService.createNewRags} (a
     * direct store write whose only catch is {@code ResourceStoreException}, so
     * this exception aborted the entire agent import) and {@code UpgradeExecutor}.
     * <p>
     * Rejecting here silently contradicted {@code RestRagStore.duplicateRag}, which
     * is documented <em>and tested</em> to tolerate an unsupported stored strategy.
     * That test passed only because it mocks {@code IRagStore} and so never reached
     * this method — neither test crossed the layer boundary, which is why the
     * contradiction survived. {@code RagStoreLayeringTest} now wires the real store
     * into the real resource to close that gap.
     * <p>
     * The author-facing rejection lives at the write boundary
     * ({@code RestRagStore.prepareForWrite}, a 400), the only layer that can tell
     * author input from a replayed document.
     */
    @Test
    @DisplayName("create accepts an unsupported strategy verbatim — rejection belongs at the write boundary")
    void createDoesNotRejectUnknownChunkStrategy() throws Exception {
        stubCreatableStorage();

        var config = knowledgeBase("semantic");
        ragStore.create(config);

        assertEquals("semantic", config.getChunkStrategy(),
                "an unsupported value must be left verbatim, so a duplicate is a faithful copy of its original");
        verify(resourceStorage).newResource(config);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("update accepts an unsupported strategy rather than aborting a replayed write")
    void updateDoesNotRejectUnknownChunkStrategy() throws Exception {
        var existing = mock(IResourceStorage.IResource.class);
        when(resourceStorage.read(eq("kb1"), eq(1))).thenReturn(existing);

        assertDoesNotThrow(() -> ragStore.update("kb1", 1, knowledgeBase("kmeans")));
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
