/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M1 of the 2026-10-17 security review: an editor read a knowledge base's
 * embedding {@code apiKey} and store {@code password} verbatim.
 */
class RestRagStoreReadRedactionTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";

    private IRagStore store;
    private ResourceAccessGuard accessGuard;
    private RestRagStore rest;

    @BeforeEach
    void setUp() {
        store = mock(IRagStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestRagStore(store, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class), accessGuard,
                mock(RagSourceIngestionService.class), mock(EmbeddingStoreFactory.class));
    }

    private static RagConfiguration config() {
        var config = new RagConfiguration();
        config.setName("kb");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(new LinkedHashMap<>(Map.of("apiKey", "sk-proj-literal-embedding-key", "model", "text-embedding-3-small")));
        config.setStoreType("pgvector");
        config.setStoreParameters(new LinkedHashMap<>(Map.of("host", "db.internal", "user", "eddi", "password", "pg-literal-password",
                "token", "${vault:pg-token}")));
        return config;
    }

    private RagConfiguration read(RagConfiguration stored, boolean admin) throws Exception {
        when(store.read(ID, 3)).thenReturn(stored);
        when(accessGuard.isAdmin()).thenReturn(admin);
        return rest.readRag(ID, 3);
    }

    @Test
    @DisplayName("an editor reads apiKey and password redacted; the rest and the references stay legible")
    void editorReadsRedacted() throws Exception {
        RagConfiguration stored = config();

        RagConfiguration seen = read(stored, false);

        assertEquals(ConfigSecretRedactor.REDACTED, seen.getEmbeddingParameters().get("apiKey"));
        assertEquals("text-embedding-3-small", seen.getEmbeddingParameters().get("model"));
        assertEquals(ConfigSecretRedactor.REDACTED, seen.getStoreParameters().get("password"));
        assertEquals("db.internal", seen.getStoreParameters().get("host"));
        assertEquals("${vault:pg-token}", seen.getStoreParameters().get("token"));
        assertEquals("pg-literal-password", stored.getStoreParameters().get("password"), "the stored object is never touched");
    }

    @Test
    @DisplayName("an administrator reads the document as stored")
    void adminReadsRaw() throws Exception {
        RagConfiguration stored = config();
        assertSame(stored, read(stored, true));
    }

    @Test
    @DisplayName("ingestion reads the unredacted document for any caller the access check admits")
    void ingestionReadsRaw() throws Exception {
        RagConfiguration stored = config();
        when(store.read(ID, 3)).thenReturn(stored);
        when(accessGuard.isAdmin()).thenReturn(false);

        assertSame(stored, rest.readUnredacted(ID, 3));
    }
}
