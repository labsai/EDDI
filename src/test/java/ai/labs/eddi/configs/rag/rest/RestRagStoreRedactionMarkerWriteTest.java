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

import static ai.labs.eddi.configs.rest.RedactionMarkerAssertions.assertRefusedNaming;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The RAG store refuses to store the read-redaction marker. */
class RestRagStoreRedactionMarkerWriteTest {

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

    private static RagConfiguration config(String apiKey) {
        var config = new RagConfiguration();
        config.setName("kb");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(new LinkedHashMap<>(Map.of("apiKey", apiKey)));
        return config;
    }

    @Test
    @DisplayName("an editor's read-then-save round trip is refused and nothing is written")
    void editorRoundTripIsRefused() throws Exception {
        when(store.read(ID, 1)).thenReturn(config("sk-proj-literal-embedding-key"));
        RagConfiguration seen = rest.readRag(ID, 1);

        assertRefusedNaming("embeddingParameters.apiKey", () -> rest.updateRag(ID, 1, seen));
        verify(store, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("an administrator is refused the marker on create, and a stored marker is not duplicated")
    void adminCreateAndDuplicateAreRefused() throws Exception {
        when(accessGuard.isAdmin()).thenReturn(true);
        assertRefusedNaming("embeddingParameters.apiKey", () -> rest.createRag(config(ConfigSecretRedactor.REDACTED)));

        when(store.read(ID, 1)).thenReturn(config(ConfigSecretRedactor.REDACTED));
        assertRefusedNaming("embeddingParameters.apiKey", () -> rest.duplicateRag(ID, 1));
        verify(store, never()).create(any());
    }
}
