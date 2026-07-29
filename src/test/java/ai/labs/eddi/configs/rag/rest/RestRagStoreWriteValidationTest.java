/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding I3 — a knowledge base the engine cannot honour must be refused where
 * the caller can act on it: the create/update boundary. Retrieval must never be
 * the place a bad {@code chunkStrategy} first shows up.
 * <p>
 * The two historically documented values ({@code paragraph}, {@code sentence})
 * are normalized rather than refused, because ingestion always treated them as
 * recursive and knowledge bases stored with them must remain updatable,
 * importable and duplicable.
 */
class RestRagStoreWriteValidationTest {

    private static final String RAG_ID = "aabbccddee1122334455";

    private IRagStore ragStore;
    private RestRagStore restRagStore;

    @BeforeEach
    void setUp() throws Exception {
        ragStore = mock(IRagStore.class);
        restRagStore = new RestRagStore(ragStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class));

        when(ragStore.create(any())).thenReturn(resourceId(RAG_ID, 1));
        when(ragStore.update(anyString(), anyInt(), any())).thenReturn(2);
    }

    @Test
    @DisplayName("create rejects an unimplemented chunkStrategy instead of storing it")
    void createRejectsUnsupportedChunkStrategy() throws Exception {
        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setChunkStrategy("semantic");

        var thrown = assertThrows(BadRequestException.class, () -> restRagStore.createRag(config));

        assertTrue(thrown.getMessage().contains("semantic"), "the 400 must name the offending value: " + thrown.getMessage());
        verify(ragStore, never()).create(any());
    }

    @Test
    @DisplayName("update rejects an unimplemented chunkStrategy instead of storing it")
    void updateRejectsUnsupportedChunkStrategy() throws Exception {
        var config = new RagConfiguration();
        config.setChunkStrategy("semantic");

        assertThrows(BadRequestException.class, () -> restRagStore.updateRag(RAG_ID, 1, config));

        verify(ragStore, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("create stores the legacy 'paragraph' strategy as the recursive splitting it always was")
    void createNormalizesLegacyChunkStrategy() throws Exception {
        var config = new RagConfiguration();
        config.setName("legacy-kb");
        config.setChunkStrategy("paragraph");

        var response = restRagStore.createRag(config);

        assertEquals(201, response.getStatus());
        ArgumentCaptor<RagConfiguration> stored = ArgumentCaptor.forClass(RagConfiguration.class);
        verify(ragStore).create(stored.capture());
        assertEquals("recursive", stored.getValue().getChunkStrategy(),
                "a legacy strategy must be persisted as what ingestion actually does");
    }

    @Test
    @DisplayName("update of a knowledge base stored with 'sentence' keeps working")
    void updateNormalizesLegacyChunkStrategy() throws Exception {
        var config = new RagConfiguration();
        config.setChunkStrategy("sentence");

        var response = restRagStore.updateRag(RAG_ID, 1, config);

        assertEquals(200, response.getStatus());
        ArgumentCaptor<RagConfiguration> stored = ArgumentCaptor.forClass(RagConfiguration.class);
        verify(ragStore).update(anyString(), anyInt(), stored.capture());
        assertEquals("recursive", stored.getValue().getChunkStrategy());
    }

    @Test
    @DisplayName("a supported chunkStrategy is stored verbatim")
    void supportedChunkStrategyIsStoredUnchanged() throws Exception {
        var config = new RagConfiguration();
        config.setChunkStrategy("recursive");

        restRagStore.createRag(config);

        ArgumentCaptor<RagConfiguration> stored = ArgumentCaptor.forClass(RagConfiguration.class);
        verify(ragStore).create(stored.capture());
        assertEquals("recursive", stored.getValue().getChunkStrategy());
    }

    @Test
    @DisplayName("duplicating a knowledge base stored with a legacy strategy normalizes the copy")
    void duplicateNormalizesLegacyChunkStrategy() throws Exception {
        var storedConfig = new RagConfiguration();
        storedConfig.setName("legacy-kb");
        storedConfig.setChunkStrategy("paragraph");
        when(ragStore.read(RAG_ID, 1)).thenReturn(storedConfig);

        var response = restRagStore.duplicateRag(RAG_ID, 1);

        assertEquals(201, response.getStatus());
        ArgumentCaptor<RagConfiguration> copy = ArgumentCaptor.forClass(RagConfiguration.class);
        verify(ragStore).create(copy.capture());
        assertEquals("recursive", copy.getValue().getChunkStrategy());
    }

    /**
     * The write boundary must not make already-stored data un-copyable. A knowledge
     * base holding a strategy the engine never implemented can still be read back
     * through {@code readRag}; refusing to duplicate it would be a new failure mode
     * for existing data rather than a guard against creating bad data — which is
     * why every other {@code duplicate*} endpoint in the codebase skips validation
     * too.
     */
    @Test
    @DisplayName("duplicating a knowledge base with an unsupported strategy still succeeds")
    void duplicateDoesNotRejectAnUnsupportedStoredStrategy() throws Exception {
        var storedConfig = new RagConfiguration();
        storedConfig.setName("odd-kb");
        storedConfig.setChunkStrategy("semantic");
        when(ragStore.read(RAG_ID, 1)).thenReturn(storedConfig);

        var response = restRagStore.duplicateRag(RAG_ID, 1);

        assertEquals(201, response.getStatus(), "an existing document the store serves must remain copyable");
        verify(ragStore).create(any());
    }

    @Test
    @DisplayName("create still rejects the same strategy duplicate tolerates")
    void createStillRejectsWhatDuplicateTolerates() {
        var config = new RagConfiguration();
        config.setName("odd-kb");
        config.setChunkStrategy("semantic");

        assertThrows(BadRequestException.class, () -> restRagStore.createRag(config));
    }

    private static IResourceStore.IResourceId resourceId(String id, Integer version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }
}
