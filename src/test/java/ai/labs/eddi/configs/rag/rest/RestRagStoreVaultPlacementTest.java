/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.EmbeddingParameters;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * The save-time half of the embedding-path fix for finding 1 of the 2026-11
 * review: a knowledge base with {@code ${vault:…}} outside {@code apiKey} is
 * refused with a 400 that names the key.
 */
@DisplayName("RestRagStore — vault references only in the embedding credential")
class RestRagStoreVaultPlacementTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";

    private IRagStore store;
    private RestRagStore rest;

    @BeforeEach
    void setUp() {
        store = mock(IRagStore.class);
        rest = new RestRagStore(store, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class), mock(ResourceAccessGuard.class),
                mock(RagSourceIngestionService.class), mock(EmbeddingStoreFactory.class));
    }

    private static RagConfiguration config(Map<String, String> embeddingParameters) {
        var config = new RagConfiguration();
        config.setName("kb");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(new LinkedHashMap<>(embeddingParameters));
        return config;
    }

    @Test
    @DisplayName("create and update refuse a reference in model, naming the key and not the reference")
    void createAndUpdateAreRefused() throws Exception {
        var exploit = config(Map.of("apiKey", "own-key", "model", "${vault:any-secret}"));

        var created = assertThrows(BadRequestException.class, () -> rest.createRag(exploit));
        assertTrue(created.getMessage().contains("'model'"), created.getMessage());
        assertFalse(created.getMessage().contains("any-secret"));
        assertThrows(BadRequestException.class, () -> rest.updateRag(ID, 1, exploit));
        verify(store, never()).create(any());
        verify(store, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("duplicate refuses a stored configuration carrying one")
    void duplicateIsRefused() throws Exception {
        when(store.read(ID, 1)).thenReturn(config(Map.of("apiKey", "k", "modelName", "${vault:any-secret}")));

        assertThrows(BadRequestException.class, () -> rest.duplicateRag(ID, 1));
        verify(store, never()).create(any());
    }

    @Test
    @DisplayName("a store parameter carrying one is refused on create and duplicate")
    void storeParameterIsRefused() throws Exception {
        var exploit = config(Map.of("apiKey", "k"));
        exploit.setStoreType("pgvector");
        exploit.setStoreParameters(Map.of("password", "${vault:pg}", "database", "${vault:any-secret}"));

        var created = assertThrows(BadRequestException.class, () -> rest.createRag(exploit));
        assertTrue(created.getMessage().contains("'storeParameters.database'"), created.getMessage());
        when(store.read(ID, 1)).thenReturn(exploit);
        assertThrows(BadRequestException.class, () -> rest.duplicateRag(ID, 1));
        verify(store, never()).create(any());
    }

    @Test
    @DisplayName("a reference in apiKey is what the field is for")
    void apiKeyReferenceIsAccepted() {
        assertNull(EmbeddingParameters.findMisplacedVaultReference("openai", Map.of("apiKey", "${vault:openai-key}", "model", "m")));
        assertNull(EmbeddingParameters.findMisplacedVaultReference("gemini", Map.of("apiKey", "${vault:k}")));
    }

    @Test
    @DisplayName("providers authenticated by the cloud credential chain take no reference")
    void credentialFreeProviders() {
        assertTrue(EmbeddingParameters.findMisplacedVaultReference("bedrock", Map.of("region", "${vault:r}")).contains("'region'"));
        assertTrue(EmbeddingParameters.findMisplacedVaultReference("azure-openai", Map.of("apiKey", "k", "endpoint", "${vault:e}"))
                .contains("'endpoint'"));
    }
}
