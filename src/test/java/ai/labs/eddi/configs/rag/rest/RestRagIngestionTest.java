/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.rag.RagIngestionService;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.ingestion.files.IngestedFileService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RestRagIngestionTest {

    @Mock
    private IRestRagStore restRagStore;
    @Mock
    private RagIngestionService ragIngestionService;

    private RestRagIngestion restRagIngestion;

    @BeforeEach
    void setUp() {
        openMocks(this);
        restRagIngestion = new RestRagIngestion(restRagStore, ragIngestionService, mock(RagSourceIngestionService.class),
                mock(IngestedFileService.class), mock(ResourceAccessGuard.class));
    }

    @Test
    void ingestDocument_shouldReturn202WithIngestionId() {
        var config = new RagConfiguration();
        config.setName("product-docs");
        when(restRagStore.readRag("rag-123", 1)).thenReturn(config);
        when(ragIngestionService.ingest(anyString(), anyString(), anyString(), any(), anyBoolean())).thenReturn("ingestion-abc");

        Response response = restRagIngestion.ingestDocument("rag-123", 1, null, "test.txt", false, "Hello world");

        assertEquals(202, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("ingestion-abc", body.get("ingestionId"));
        assertEquals("product-docs", body.get("kbId"));
        assertEquals("pending", body.get("status"));
    }

    @Test
    void ingestDocument_withExplicitKbId_shouldUseProvidedKbId() {
        var config = new RagConfiguration();
        config.setName("product-docs");
        when(restRagStore.readRag("rag-123", 1)).thenReturn(config);
        when(ragIngestionService.ingest(eq("custom-kb"), anyString(), anyString(), any(), anyBoolean())).thenReturn("ingestion-xyz");

        Response response = restRagIngestion.ingestDocument("rag-123", 1, "custom-kb", "test.txt", false, "Hello world");

        assertEquals(202, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("custom-kb", body.get("kbId"));
        verify(ragIngestionService).ingest(eq("custom-kb"), anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void ingestDocument_blankContent_shouldReturn400() {
        Response response = restRagIngestion.ingestDocument("rag-123", 1, null, "test.txt", false, "");

        assertEquals(400, response.getStatus());
    }

    @Test
    void ingestDocument_nullContent_shouldReturn400() {
        Response response = restRagIngestion.ingestDocument("rag-123", 1, null, "test.txt", false, null);

        assertEquals(400, response.getStatus());
    }

    @Test
    void ingestDocument_configNotFound_shouldReturn404() {
        when(restRagStore.readRag("missing", 1)).thenThrow(new RuntimeException("Not found"));

        Response response = restRagIngestion.ingestDocument("missing", 1, null, "test.txt", false, "Content");

        assertEquals(404, response.getStatus());
    }

    @Test
    void ingestDocument_replace_isPassedThrough() {
        var config = new RagConfiguration();
        config.setName("product-docs");
        when(restRagStore.readRag("rag-123", 1)).thenReturn(config);
        when(ragIngestionService.ingest(anyString(), anyString(), anyString(), any(), anyBoolean())).thenReturn("ingestion-abc");

        Response response = restRagIngestion.ingestDocument("rag-123", 1, null, "policy.txt", true, "Refunds within 47 days.");

        assertEquals(202, response.getStatus());
        verify(ragIngestionService).ingest(eq("product-docs"), anyString(), eq("policy.txt"), any(), eq(true));
    }

    /**
     * Replacement is keyed on the name, and every unnamed document shares "unnamed"
     * — so replacing without a name would delete all of them.
     */
    @Test
    void ingestDocument_replaceWithoutAName_isRefused() {
        for (String name : new String[]{"unnamed", "", " ", null}) {
            Response response = restRagIngestion.ingestDocument("rag-123", 1, null, name, true, "Content");

            assertEquals(400, response.getStatus(), "documentName=" + name);
        }
        verify(ragIngestionService, never()).ingest(any(), any(), any(), any(), anyBoolean());
        verify(restRagStore, never()).readRag(any(), any());
    }

    @Test
    void getIngestionStatus_carriesTheWarningWhenThereIsOne() {
        when(ragIngestionService.getStatus("ing-1")).thenReturn("completed");
        when(ragIngestionService.getWarning("ing-1")).thenReturn("previous version still retrievable");

        Response response = restRagIngestion.getIngestionStatus("rag-123", "ing-1");

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("completed", body.get("status"));
        assertEquals("previous version still retrievable", body.get("warning"));
    }

    @Test
    void getIngestionStatus_unknownIdIs404() {
        when(ragIngestionService.getStatus("never-started")).thenReturn(RagIngestionService.STATUS_UNKNOWN);

        Response response = restRagIngestion.getIngestionStatus("rag-123", "never-started");

        assertEquals(404, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("never-started", body.get("ingestionId"));
        assertEquals(RagIngestionService.STATUS_UNKNOWN, body.get("status"));
    }

    @Test
    void getIngestionStatus_shouldReturnStatus() {
        when(ragIngestionService.getStatus("ing-123")).thenReturn("completed");

        Response response = restRagIngestion.getIngestionStatus("rag-123", "ing-123");

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("ing-123", body.get("ingestionId"));
        assertEquals("completed", body.get("status"));
    }
}
