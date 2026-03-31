package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.rag.RagIngestionService;
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
        restRagIngestion = new RestRagIngestion(restRagStore, ragIngestionService);
    }

    @Test
    void ingestDocument_shouldReturn202WithIngestionId() {
        var config = new RagConfiguration();
        config.setName("product-docs");
        when(restRagStore.readRag("rag-123", 1)).thenReturn(config);
        when(ragIngestionService.ingest(anyString(), anyString(), anyString(), any())).thenReturn("ingestion-abc");

        Response response = restRagIngestion.ingestDocument("rag-123", 1, null, "test.txt", "Hello world");

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
        when(ragIngestionService.ingest(eq("custom-kb"), anyString(), anyString(), any())).thenReturn("ingestion-xyz");

        Response response = restRagIngestion.ingestDocument("rag-123", 1, "custom-kb", "test.txt", "Hello world");

        assertEquals(202, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("custom-kb", body.get("kbId"));
        verify(ragIngestionService).ingest(eq("custom-kb"), anyString(), anyString(), any());
    }

    @Test
    void ingestDocument_blankContent_shouldReturn400() {
        Response response = restRagIngestion.ingestDocument("rag-123", 1, null, "test.txt", "");

        assertEquals(400, response.getStatus());
    }

    @Test
    void ingestDocument_nullContent_shouldReturn400() {
        Response response = restRagIngestion.ingestDocument("rag-123", 1, null, "test.txt", null);

        assertEquals(400, response.getStatus());
    }

    @Test
    void ingestDocument_configNotFound_shouldReturn404() {
        when(restRagStore.readRag("missing", 1)).thenThrow(new RuntimeException("Not found"));

        Response response = restRagIngestion.ingestDocument("missing", 1, null, "test.txt", "Content");

        assertEquals(404, response.getStatus());
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
