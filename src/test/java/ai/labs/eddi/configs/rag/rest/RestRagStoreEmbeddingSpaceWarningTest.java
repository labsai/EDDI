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
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.secrets.SecretResolver;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Saving a knowledge base with another embedding model says what that means for
 * the vectors it already holds, instead of letting old and new mix without a
 * word.
 */
@DisplayName("RestRagStore — saving a changed embedding model")
class RestRagStoreEmbeddingSpaceWarningTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";

    private IRagStore ragStore;
    private RestRagStore restRagStore;

    @BeforeEach
    void setUp() throws Exception {
        ragStore = mock(IRagStore.class);
        restRagStore = new RestRagStore(ragStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class), mock(RagSourceIngestionService.class),
                new EmbeddingStoreFactory(mock(GlobalVariableResolver.class), mock(SecretResolver.class), 1000));
        when(ragStore.read(eq(KB_ID), anyInt())).thenReturn(qdrant("emb-8", "docs"));
        when(ragStore.update(eq(KB_ID), anyInt(), any(RagConfiguration.class))).thenReturn(2);
    }

    private static RagConfiguration qdrant(String model, String collection) {
        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setStoreNamespace("id");
        config.setStoreType("qdrant");
        config.setStoreParameters(Map.of("host", "qdrant.internal", "collectionName", collection));
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("model", model));
        return config;
    }

    @Test
    @DisplayName("a changed model on the same store answers 200 with a warning that names both models and the way out")
    void changedModelWarns() {
        Response response = restRagStore.updateRag(KB_ID, 1, qdrant("other-8", "docs"));

        assertEquals(200, response.getStatus());
        assertTrue(response.getLocation().toString().contains("version=2"), String.valueOf(response.getLocation()));
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("EMBEDDING_SPACE_CHANGED", body.get("code"));
        String warning = (String) ((List<?>) body.get("warnings")).get(0);
        assertTrue(warning.contains("emb-8") && warning.contains("other-8"), warning);
        assertTrue(warning.contains("/ragstore/rags/" + KB_ID + "/reembed?version=2"), warning);
    }

    @Test
    @DisplayName("an update that keeps the model answers as before, with no body")
    void sameModelDoesNotWarn() {
        var updated = qdrant("emb-8", "docs");
        updated.setChunkSize(256);

        Response response = restRagStore.updateRag(KB_ID, 1, updated);

        assertEquals(200, response.getStatus());
        assertNull(response.getEntity());
    }

    @Test
    @DisplayName("moving the vectors somewhere else starts empty, so a new model there warns about nothing")
    void movedStoreDoesNotWarn() {
        Response response = restRagStore.updateRag(KB_ID, 1, qdrant("other-8", "elsewhere"));

        assertNull(response.getEntity());
    }

    @Test
    @DisplayName("an in-memory knowledge base is not tracked: its store is replaced instead")
    void inMemoryDoesNotWarn() {
        var before = qdrant("emb-8", "docs");
        before.setStoreType("in-memory");
        before.setStoreParameters(null);
        var after = qdrant("other-8", "docs");
        after.setStoreType("in-memory");
        after.setStoreParameters(null);
        try {
            when(ragStore.read(eq(KB_ID), anyInt())).thenReturn(before);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertNull(restRagStore.updateRag(KB_ID, 1, after).getEntity());
    }
}
