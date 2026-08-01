/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.mongo.RagStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The RAG write path across both layers at once, with the <strong>real</strong>
 * {@link RagStore} behind the real {@link RestRagStore}.
 * <p>
 * This test exists because the two single-layer suites disagreed and neither
 * could notice. {@code RestRagStoreWriteValidationTest} asserts that
 * {@code duplicateRag} tolerates an unsupported stored {@code chunkStrategy} —
 * but it mocks {@code IRagStore}, so its {@code create} was a stub and the
 * store's own write hook never ran. {@code RagStoreValidationTest} asserted the
 * store <em>rejects</em> that same value. Both passed. In production the
 * store's rejection won, so duplicating such a knowledge base failed and
 * {@code RestImportService.createNewRags} — which writes straight to the store
 * via {@code createResourceDirect} and only catches
 * {@code ResourceStoreException} — aborted the entire agent import.
 * <p>
 * Mocking stops at the storage layer here: the factory hands back a mocked
 * {@link IResourceStorage} so nothing touches MongoDB, but every line of
 * validation and normalisation in both classes is the production code.
 * <p>
 * The property under test is the division of labour. Author input is rejected
 * at the write boundary with an actionable 400; a document that already exists
 * is copied as-is, because a store that serves a document through
 * {@code readRag} must not refuse to copy it.
 */
@DisplayName("RAG write path — REST boundary and store together")
class RagStoreLayeringTest {

    private static final String RAG_ID = "aabbccddee1122334455";

    /** Never valid, never advertised — the value the two suites disagreed about. */
    private static final String UNSUPPORTED = "semantic";

    /**
     * Advertised for years, never implemented — must be normalised, not refused.
     */
    private static final String LEGACY_ALIAS = "paragraph";

    private IResourceStorage<RagConfiguration> resourceStorage;
    private RagStore ragStore;
    private RestRagStore restRagStore;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        var storageFactory = mock(IResourceStorageFactory.class);
        resourceStorage = mock(IResourceStorage.class);
        when(storageFactory.create(eq("rags"), any(), eq(RagConfiguration.class), any(String[].class)))
                .thenReturn(resourceStorage);

        // HistorizedResourceStore.create returns the new resource as the IResourceId,
        // and RestRagStore turns that into a Location URI — so id and version have to
        // be present or URI building NPEs before any assertion is reached.
        var createdResource = mock(IResourceStorage.IResource.class);
        when(createdResource.getId()).thenReturn(RAG_ID);
        when(createdResource.getVersion()).thenReturn(1);
        when(resourceStorage.newResource(any(RagConfiguration.class))).thenReturn(createdResource);

        ragStore = new RagStore(storageFactory, mock(IDocumentBuilder.class));
        restRagStore = new RestRagStore(ragStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class));
    }

    private static RagConfiguration knowledgeBase(String chunkStrategy) {
        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setChunkStrategy(chunkStrategy);
        return config;
    }

    /**
     * Makes {@code readRag(RAG_ID, 1)} serve {@code config} through the real store.
     */
    @SuppressWarnings("unchecked")
    private void stubStoredDocument(RagConfiguration config) throws Exception {
        var stored = mock(IResourceStorage.IResource.class);
        when(stored.getData()).thenReturn(config);
        when(resourceStorage.read(RAG_ID, 1)).thenReturn(stored);
    }

    @Test
    @DisplayName("author input with an unsupported strategy is refused at the boundary")
    void createRejectsAuthorSuppliedUnsupportedStrategy() {
        var thrown = assertThrows(BadRequestException.class, () -> restRagStore.createRag(knowledgeBase(UNSUPPORTED)));

        // The 400 has to be actionable, or the author cannot tell what to write
        // instead.
        assertEquals(400, thrown.getResponse().getStatus());
    }

    /**
     * The regression this whole test class was written for. Reverting
     * {@code RagStore.validate()} to call {@code content.validate()} makes this
     * fail with the very {@code IllegalArgumentException} that aborted agent
     * imports.
     */
    @Test
    @DisplayName("a stored document with an unsupported strategy can still be duplicated")
    void duplicateSurvivesAnUnsupportedStoredStrategy() throws Exception {
        // Stubbed at the storage layer, not on ragStore: the store here is the real
        // one, which is the entire point — a mocked store is what hid this bug.
        stubStoredDocument(knowledgeBase(UNSUPPORTED));

        var response = assertDoesNotThrow(() -> restRagStore.duplicateRag(RAG_ID, 1),
                "the store serves this document through readRag, so it must not refuse to copy it");

        assertEquals(201, response.getStatus());
    }

    /**
     * ZIP import writes through {@code createResourceDirect}, i.e. straight to the
     * store with no REST layer in front, and catches only
     * {@code ResourceStoreException}. Anything else escapes and rolls the whole
     * import back, so the store must not throw on a document that already exists.
     */
    @Test
    @DisplayName("a direct store write, as ZIP import performs it, does not abort")
    void directStoreWriteDoesNotAbortAnImport() {
        assertDoesNotThrow(() -> ragStore.create(knowledgeBase(UNSUPPORTED)),
                "an IllegalArgumentException here rolls back the entire agent import");
    }

    @Test
    @DisplayName("an unsupported strategy is preserved verbatim, so a duplicate is faithful")
    void unsupportedStrategyIsNotSilentlyRewritten() throws Exception {
        var config = knowledgeBase(UNSUPPORTED);

        ragStore.create(config);

        assertEquals(UNSUPPORTED, config.getChunkStrategy(),
                "rewriting it would make the copy differ from the original it was copied from");
    }

    @Test
    @DisplayName("a legacy alias is normalised on both the boundary and the direct path")
    void legacyAliasIsNormalisedOnEitherPath() throws Exception {
        var viaBoundary = knowledgeBase(LEGACY_ALIAS);
        restRagStore.createRag(viaBoundary);
        assertEquals("recursive", viaBoundary.getChunkStrategy());

        var viaStore = knowledgeBase(LEGACY_ALIAS);
        ragStore.create(viaStore);
        assertEquals("recursive", viaStore.getChunkStrategy(),
                "import writes straight to the store, so normalisation cannot live only at the boundary");
    }

    @Test
    @DisplayName("the supported strategy is accepted on both paths and left alone")
    void supportedStrategyPassesBothLayers() throws Exception {
        var viaBoundary = knowledgeBase("recursive");
        assertDoesNotThrow(() -> restRagStore.createRag(viaBoundary));
        assertEquals("recursive", viaBoundary.getChunkStrategy());
    }
}
