/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceReferenceGuard;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseInspector;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.ingestion.files.IngestedFileService;
import ai.labs.eddi.modules.rag.RagIngestionService;
import ai.labs.eddi.modules.rag.RagIngestionService.DeleteOutcome;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code DELETE /ragstore/rags/{id}/documents} — removing a document pushed
 * through {@code /ingest}.
 */
class RestRagIngestionDeleteDocumentTest {

    private static final String KB = "rag-123";

    private IRestRagStore restRagStore;
    private RagIngestionService ragIngestionService;
    private RagSourceIngestionService sourceIngestionService;
    private ResourceAccessGuard accessGuard;
    private RestRagIngestion rest;
    private RagConfiguration config;

    @BeforeEach
    void setUp() {
        restRagStore = mock(IRestRagStore.class);
        ragIngestionService = mock(RagIngestionService.class);
        sourceIngestionService = mock(RagSourceIngestionService.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestRagIngestion(restRagStore, ragIngestionService, sourceIngestionService, mock(IngestedFileService.class),
                mock(KnowledgeBaseInspector.class), accessGuard, mock(SpaceReferenceGuard.class));

        config = new RagConfiguration();
        config.setName("product-docs");
        when(restRagStore.readRag(KB, 1)).thenReturn(config);
        when(sourceIngestionService.sourceHoldingDocument(anyString(), any(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void deletesTheNamedDocumentOfThisKnowledgeBase() {
        when(ragIngestionService.delete(KB, "pricing.md", config)).thenReturn(DeleteOutcome.DELETED);

        Response response = rest.deleteDocument(KB, 1, "pricing.md");

        assertEquals(200, response.getStatus());
        Map<String, Object> body = body(response);
        assertEquals("deleted", body.get("status"));
        assertEquals("pricing.md", body.get("documentName"));
        assertEquals(KB, body.get("kbId"));
        verify(accessGuard).requireAccess(KB, AccessLevel.EDIT, "RAG configuration");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "unnamed"})
    void withoutAName_isRefusedAndNothingIsDeleted(String documentName) {
        Response response = rest.deleteDocument(KB, 1, documentName);

        assertEquals(400, response.getStatus());
        verify(ragIngestionService, never()).delete(any(), any(), any());
    }

    @Test
    void withoutEdit_isRefusedBeforeAnythingIsRead() {
        doThrow(new ForbiddenException()).when(accessGuard).requireAccess(KB, AccessLevel.EDIT, "RAG configuration");

        assertThrows(ForbiddenException.class, () -> rest.deleteDocument(KB, 1, "pricing.md"));

        verify(restRagStore, never()).readRag(any(), any());
        verify(ragIngestionService, never()).delete(any(), any(), any());
    }

    @Test
    void withoutAVersion_is400() {
        assertEquals(400, rest.deleteDocument(KB, null, "pricing.md").getStatus());
        verify(ragIngestionService, never()).delete(any(), any(), any());
    }

    @Test
    void aNameASourceHolds_isRefusedWithThePathToTheSource() {
        var upload = new IngestionSource();
        upload.setId("handbooks");
        upload.setName("Handbooks");
        upload.setType("upload");
        when(sourceIngestionService.sourceHoldingDocument(KB, config, "handbook.pdf")).thenReturn(Optional.of(upload));

        Response response = rest.deleteDocument(KB, 1, "handbook.pdf");

        assertEquals(409, response.getStatus());
        Map<String, Object> body = body(response);
        assertEquals("handbooks", body.get("sourceId"));
        assertTrue(body.get("error").toString().contains("through the source"), body.get("error").toString());
        verify(ragIngestionService, never()).delete(any(), any(), any());
    }

    @Test
    void aStoreThatCannotDeleteByMetadata_answers200WithAWarning() {
        when(ragIngestionService.delete(KB, "pricing.md", config)).thenReturn(DeleteOutcome.UNSUPPORTED);

        Response response = rest.deleteDocument(KB, 1, "pricing.md");

        assertEquals(200, response.getStatus());
        Map<String, Object> body = body(response);
        assertNotNull(body.get("warning"));
        assertTrue(body.get("warning").toString().contains("still retrievable"));
    }

    @Test
    void aFailingStore_is503SoTheClientRetries() {
        when(ragIngestionService.delete(KB, "pricing.md", config)).thenThrow(new RuntimeException("connection reset"));

        assertEquals(503, rest.deleteDocument(KB, 1, "pricing.md").getStatus());
    }

    @Test
    void aFailingSourceCheck_is503AndNothingIsDeleted() {
        when(sourceIngestionService.sourceHoldingDocument(eq(KB), any(), eq("pricing.md")))
                .thenThrow(new RuntimeException("state store down"));

        assertEquals(503, rest.deleteDocument(KB, 1, "pricing.md").getStatus());
        verify(ragIngestionService, never()).delete(any(), any(), any());
    }

    @Test
    void aReplacementInFlight_is409() {
        when(ragIngestionService.delete(KB, "pricing.md", config)).thenReturn(DeleteOutcome.BUSY);

        assertEquals(409, rest.deleteDocument(KB, 1, "pricing.md").getStatus());
    }

    @Test
    void aConfigurationThatCannotBeAddressed_is400() {
        when(ragIngestionService.delete(KB, "pricing.md", config)).thenThrow(new IllegalArgumentException("no name"));

        assertEquals(400, rest.deleteDocument(KB, 1, "pricing.md").getStatus());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Response response) {
        return (Map<String, Object>) response.getEntity();
    }
}
