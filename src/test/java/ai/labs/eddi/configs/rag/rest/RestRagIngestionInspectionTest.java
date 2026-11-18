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
import ai.labs.eddi.engine.security.spaces.SpaceReferenceGuard;
import ai.labs.eddi.configs.rag.model.VectorSpace;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseInspector;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseReembedService;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.ingestion.files.IngestedFileService;
import ai.labs.eddi.modules.rag.OperatorFailureException;
import ai.labs.eddi.modules.rag.RagFailures;
import ai.labs.eddi.modules.rag.RagIngestionService;
import ai.labs.eddi.modules.rag.VectorSpaceGuard;
import ai.labs.eddi.modules.rag.VectorSpaceMismatchException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private SpaceReferenceGuard spaceReferenceGuard;
    private RestRagIngestion rest;

    @BeforeEach
    void setUp() {
        restRagStore = mock(IRestRagStore.class);
        inspector = mock(KnowledgeBaseInspector.class);
        accessGuard = mock(ResourceAccessGuard.class);
        spaceReferenceGuard = mock(SpaceReferenceGuard.class);
        rest = new RestRagIngestion(restRagStore, mock(RagIngestionService.class),
                mock(RagSourceIngestionService.class), mock(IngestedFileService.class), inspector, accessGuard, spaceReferenceGuard);
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
        when(inspector.documentChunkPage(anyString(), any(), any(), anyString(), anyInt()))
                .thenThrow(new UnsupportedOperationException("cannot filter"));

        Response response = rest.readDocumentChunks(KB_ID, SOURCE_ID, 1, "https://example.com/a", 50);

        assertEquals(501, response.getStatus());
        assertEquals("cannot filter", ((Map<?, ?>) response.getEntity()).get("error"));
    }

    @Test
    @DisplayName("a failing embedding provider is a 502 with a fixed sentence, never the provider's text")
    void providerFailureIs502() {
        when(inspector.search(anyString(), any(), any(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new IllegalStateException("401 invalid api key"));

        Response response = rest.searchKnowledgeBase(KB_ID, 1, new KnowledgeBaseSearch("hello", null, null, null));

        assertEquals(502, response.getStatus());
        assertEquals(RagFailures.GENERIC, ((Map<?, ?>) response.getEntity()).get("error"));
    }

    @Test
    @DisplayName("a driver failure with SQL in it reaches the caller as a sentence, and the SQL stays in the log")
    void driverTextNeverReachesTheCaller() {
        when(inspector.search(anyString(), any(), any(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("The embedding model or vector store failed: org.postgresql.util.PSQLException: "
                        + "ERROR: different vector dimensions 8 and 16", new SQLException("INSERT INTO eddi_kbid_x (embedding)")));

        Response response = rest.searchKnowledgeBase(KB_ID, 1, new KnowledgeBaseSearch("hello", null, null, null));

        assertEquals(502, response.getStatus());
        String error = (String) ((Map<?, ?>) response.getEntity()).get("error");
        assertFalse(error.contains("PSQLException") || error.contains("INSERT") || error.contains("dimensions 8"), error);
    }

    @Test
    @DisplayName("a knowledge base whose store holds another embedding space is 409 with a stable code")
    void vectorSpaceMismatchIs409() {
        when(inspector.search(anyString(), any(), any(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new VectorSpaceMismatchException("holds emb-8, configured other-8"));

        Response response = rest.searchKnowledgeBase(KB_ID, 1, new KnowledgeBaseSearch("hello", null, null, null));

        assertEquals(409, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals(VectorSpaceMismatchException.CODE, body.get("code"));
        assertEquals("holds emb-8, configured other-8", body.get("error"));
    }

    @Test
    @DisplayName("chunks say when the document has more than were returned")
    void chunksReportTruncation() {
        when(inspector.documentChunkPage(anyString(), any(), any(), anyString(), anyInt()))
                .thenReturn(new KnowledgeBaseInspector.ChunkPage(List.of(), true));

        Response response = rest.readDocumentChunks(KB_ID, SOURCE_ID, 1, "https://example.com/a", 50);

        assertEquals(200, response.getStatus());
        assertEquals(true, ((Map<?, ?>) response.getEntity()).get("truncated"));
    }

    @Test
    @DisplayName("the vector-space endpoint shows both spaces and what to do when they differ")
    void vectorSpaceStatus() {
        VectorSpaceGuard guard = mock(VectorSpaceGuard.class);
        rest.vectorSpaceGuard = guard;
        when(guard.status(eq(KB_ID), any())).thenReturn(new VectorSpaceGuard.Status(true,
                new VectorSpace("openai", "emb-8", "", "", 8), new VectorSpace("openai", "other-8", "", "", null), false));

        Response response = rest.readVectorSpace(KB_ID, 1);

        assertEquals(200, response.getStatus());
        verify(accessGuard).requireAccess(eq(KB_ID), eq(AccessLevel.VIEW), anyString());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals(false, body.get("matches"));
        assertEquals("emb-8", ((Map<?, ?>) body.get("stored")).get("model"));
        assertEquals("other-8", ((Map<?, ?>) body.get("configured")).get("model"));
        assertTrue(((String) body.get("action")).contains("/reembed"));
    }

    @Test
    @DisplayName("re-embedding needs EDIT, starts a run per source, and says what it could not rebuild")
    void reembedStartsRuns() {
        KnowledgeBaseReembedService service = mock(KnowledgeBaseReembedService.class);
        rest.reembedService = service;
        when(service.reembed(eq(KB_ID), any())).thenReturn(new KnowledgeBaseReembedService.Result(null,
                List.of(new KnowledgeBaseReembedService.SourceOutcome(SOURCE_ID, "run-9", null)), List.of("direct documents")));

        Response response = rest.reembed(KB_ID, 1);

        assertEquals(202, response.getStatus());
        verify(accessGuard).requireAccess(eq(KB_ID), eq(AccessLevel.EDIT), anyString());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals(List.of("direct documents"), body.get("notReEmbedded"));
        assertEquals("run-9", ((Map<?, ?>) ((List<?>) body.get("sources")).get(0)).get("runId"));
    }

    @Test
    @DisplayName("re-embedding while a run is in flight is 409 and changes nothing")
    void reembedRefusedWhileARunIsInFlight() {
        KnowledgeBaseReembedService service = mock(KnowledgeBaseReembedService.class);
        rest.reembedService = service;
        when(service.reembed(eq(KB_ID), any()))
                .thenReturn(new KnowledgeBaseReembedService.Result(SOURCE_ID, List.of(), List.of()));

        assertEquals(409, rest.reembed(KB_ID, 1).getStatus());
    }

    @Test
    @DisplayName("an older version is refused: agents use the latest, and the record would describe the wrong model")
    void reembedOfAnOlderVersionIsRefused() throws Exception {
        KnowledgeBaseReembedService service = mock(KnowledgeBaseReembedService.class);
        rest.reembedService = service;
        when(restRagStore.getCurrentResourceId(KB_ID)).thenReturn(new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return KB_ID;
            }

            @Override
            public Integer getVersion() {
                return 4;
            }
        });

        Response response = rest.reembed(KB_ID, 1);

        assertEquals(409, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("NOT_LATEST_VERSION", body.get("code"));
        assertEquals(4, body.get("latestVersion"));
        verify(service, never()).reembed(anyString(), any());
    }

    @Test
    @DisplayName("a refusal to re-embed (shared chunks, shared table size) is a 409 with its code and the knowledge bases involved")
    void reembedRefusalsAreConflicts() {
        KnowledgeBaseReembedService service = mock(KnowledgeBaseReembedService.class);
        rest.reembedService = service;
        when(service.reembed(eq(KB_ID), any())).thenThrow(new KnowledgeBaseReembedService.ReembedRefusedException(
                KnowledgeBaseReembedService.CODE_SHARED_CHUNKS, "kb2 keeps the same chunks", List.of("kb2")));

        Response response = rest.reembed(KB_ID, 1);

        assertEquals(409, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("SHARED_CHUNKS", body.get("code"));
        assertEquals(List.of("kb2"), body.get("knowledgeBases"));
    }

    @Test
    @DisplayName("an unresolved variable is a retryable 409 with its own code, not a 5xx")
    void reembedUnresolvedVariable() {
        KnowledgeBaseReembedService service = mock(KnowledgeBaseReembedService.class);
        rest.reembedService = service;
        when(service.reembed(eq(KB_ID), any())).thenThrow(new OperatorFailureException("variable not resolved"));

        Response response = rest.reembed(KB_ID, 1);

        assertEquals(409, response.getStatus());
        assertEquals("EMBEDDING_SETTINGS_UNRESOLVED", ((Map<?, ?>) response.getEntity()).get("code"));
    }

    @Test
    @DisplayName("an IllegalArgumentException that quotes its input is not echoed to the caller")
    void badRequestsDoNotEchoInput() {
        when(restRagStore.readRag(eq(KB_ID), anyInt())).thenThrow(new IllegalArgumentException("http://user:pw@host/?token=1"));

        Response response = rest.searchKnowledgeBase(KB_ID, 1, new KnowledgeBaseSearch("hello", null, null, null));

        assertEquals(400, response.getStatus());
        assertFalse(String.valueOf(((Map<?, ?>) response.getEntity()).get("error")).contains("user:pw"));
    }

    @Test
    @DisplayName("a store that cannot be emptied is 503 with a sentence, not the driver's text")
    void reembedStoreFailure() {
        KnowledgeBaseReembedService service = mock(KnowledgeBaseReembedService.class);
        rest.reembedService = service;
        when(service.reembed(eq(KB_ID), any())).thenThrow(new IllegalStateException("jdbc:postgresql://secret-host/db refused"));

        Response response = rest.reembed(KB_ID, 1);

        assertEquals(503, response.getStatus());
        assertFalse(((String) ((Map<?, ?>) response.getEntity()).get("error")).contains("secret-host"));
    }

    @Test
    @DisplayName("a knowledge base naming another space's variables or secrets is not run for a non-member: no deployment checked it")
    void foreignSpaceReferencesAreRefusedBeforeTheStoreIsBuilt() {
        doThrow(new ForbiddenException("not a member")).when(spaceReferenceGuard).requireMayUse(any(), anyString());

        assertThrows(ForbiddenException.class, () -> rest.searchKnowledgeBase(KB_ID, 1, new KnowledgeBaseSearch("hello", null, null, null)));
        verify(spaceReferenceGuard).requireMayUse(any(RagConfiguration.class), eq("knowledge base"));
        verifyNoInteractions(inspector);
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
