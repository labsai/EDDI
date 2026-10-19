/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.rag.IRestRagIngestion.KnowledgeBaseSearch;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseInspector;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.ingestion.files.IngestedFileService;
import ai.labs.eddi.modules.rag.RagIngestionService;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The endpoints that look into a knowledge base: which documents a source
 * holds, a document's chunks, and a test retrieval.
 */
class RestRagIngestionInspectionTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String SOURCE_ID = "src-1";

    private IRestRagStore restRagStore;
    private KnowledgeBaseInspector inspector;
    private ResourceAccessGuard accessGuard;
    private RestRagIngestion rest;

    @BeforeEach
    void setUp() {
        restRagStore = mock(IRestRagStore.class);
        inspector = mock(KnowledgeBaseInspector.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestRagIngestion(restRagStore, mock(RagIngestionService.class),
                mock(RagSourceIngestionService.class), mock(IngestedFileService.class), inspector, accessGuard);
        when(restRagStore.readRag(eq(KB_ID), anyInt())).thenReturn(knowledgeBase());
    }

    private static RagConfiguration knowledgeBase() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://example.com/");
        var source = new IngestionSource();
        source.setId(SOURCE_ID);
        source.setName("docs");
        source.setWeb(web);
        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setMaxResults(7);
        config.setMinScore(0.4);
        config.setSources(List.of(source));
        return config;
    }

    @Test
    @DisplayName("listing documents needs only VIEW — it is ingestion state, not the text")
    void documentsNeedView() {
        when(inspector.listDocuments(eq(KB_ID), any(), any(), anyInt(), anyInt()))
                .thenReturn(new KnowledgeBaseInspector.DocumentPage(0, false, List.of()));

        Response response = rest.readSourceDocuments(KB_ID, SOURCE_ID, 1, null, 0, 50);

        assertEquals(200, response.getStatus());
        verify(accessGuard).requireAccess(eq(KB_ID), eq(AccessLevel.VIEW), anyString());
    }

    @Test
    @DisplayName("a page size above the ceiling is clamped, not passed through")
    void documentPageIsClamped() {
        when(inspector.listDocuments(eq(KB_ID), any(), any(), anyInt(), anyInt()))
                .thenReturn(new KnowledgeBaseInspector.DocumentPage(0, false, List.of()));

        rest.readSourceDocuments(KB_ID, SOURCE_ID, 1, "guide", 10, 1_000_000);

        verify(inspector).listDocuments(eq(KB_ID), any(), eq("guide"), eq(10), eq(500));
    }

    @Test
    @DisplayName("reading chunks returns the knowledge base's text, so it needs EDIT")
    void chunksNeedEdit() {
        doThrow(new ForbiddenException()).when(accessGuard)
                .requireAccess(eq(KB_ID), eq(AccessLevel.EDIT), anyString());

        assertThrows(ForbiddenException.class,
                () -> rest.readDocumentChunks(KB_ID, SOURCE_ID, 1, "https://example.com/a", 50));
        verifyNoInteractions(inspector);
    }

    @Test
    @DisplayName("chunks without a document id are a bad request")
    void chunksNeedADocumentId() {
        assertEquals(400, rest.readDocumentChunks(KB_ID, SOURCE_ID, 1, " ", 50).getStatus());
    }

    @Test
    @DisplayName("a store that cannot filter by metadata is 501, said in words")
    void unsupportedStoreIs501() {
        when(inspector.documentChunks(anyString(), any(), any(), anyString(), anyInt()))
                .thenThrow(new UnsupportedOperationException("cannot filter"));

        Response response = rest.readDocumentChunks(KB_ID, SOURCE_ID, 1, "https://example.com/a", 50);

        assertEquals(501, response.getStatus());
        assertEquals("cannot filter", ((Map<?, ?>) response.getEntity()).get("error"));
    }

    @Test
    @DisplayName("a failing embedding provider is a 502 naming the cause")
    void providerFailureIs502() {
        when(inspector.search(anyString(), any(), any(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new IllegalStateException("401 invalid api key"));

        Response response = rest.searchKnowledgeBase(KB_ID, 1, new KnowledgeBaseSearch("hello", null, null, null));

        assertEquals(502, response.getStatus());
        assertEquals("The embedding model or vector store failed: 401 invalid api key",
                ((Map<?, ?>) response.getEntity()).get("error"));
    }

    @Test
    @DisplayName("search defaults to the knowledge base's own retrieval settings and needs EDIT")
    void searchUsesTheKnowledgeBaseDefaults() {
        when(inspector.search(anyString(), any(), any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of());

        Response response = rest.searchKnowledgeBase(KB_ID, 1, new KnowledgeBaseSearch("hello", null, null, null));

        assertEquals(200, response.getStatus());
        verify(accessGuard).requireAccess(eq(KB_ID), eq(AccessLevel.EDIT), anyString());
        verify(inspector).search(eq(KB_ID), any(), isNull(), eq("hello"), eq(7), eq(0.4));
    }

    @Test
    @DisplayName("search can be narrowed to a source, and an unknown one is 404")
    void searchNarrowsToASource() {
        when(inspector.search(anyString(), any(), any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of());

        rest.searchKnowledgeBase(KB_ID, 1, new KnowledgeBaseSearch("hello", 3, 0.0, SOURCE_ID));
        verify(inspector).search(eq(KB_ID), any(), any(IngestionSource.class), eq("hello"), eq(3), eq(0.0));

        Response unknown = rest.searchKnowledgeBase(KB_ID, 1, new KnowledgeBaseSearch("hello", 3, 0.0, "nope"));
        assertEquals(404, unknown.getStatus());
    }

    @Test
    @DisplayName("an empty or oversized query is refused before anything is embedded")
    void badQueriesAreRefused() {
        assertEquals(400, rest.searchKnowledgeBase(KB_ID, 1, new KnowledgeBaseSearch(" ", null, null, null))
                .getStatus());
        assertEquals(400, rest.searchKnowledgeBase(KB_ID, 1,
                new KnowledgeBaseSearch("x".repeat(RestRagIngestion.MAX_SEARCH_QUERY_LENGTH + 1), null, null, null))
                .getStatus());
        verify(inspector, never()).search(anyString(), any(), any(), anyString(), anyInt(), anyDouble());
    }
}
