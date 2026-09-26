/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.modules.ingestion.PreviewBusyException;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.ingestion.IngestionPipeline;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.ingestion.files.IngestedFileService;
import ai.labs.eddi.modules.rag.RagIngestionService;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ingestion-source endpoints of {@link RestRagIngestion}.
 *
 * <p>
 * Mostly about access. A run rewrites the knowledge base every agent using the
 * config retrieves from, and a <em>published</em> config grants VIEW to
 * everyone by design — so gating a run on read access would let any editor
 * point a source at any published knowledge base and poison it, on a schedule.
 * The draft this replaces checked nothing at all.
 */
class RestRagIngestionSourcesTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String SOURCE_ID = "src-1";

    private IRestRagStore restRagStore;
    private RagSourceIngestionService sourceIngestionService;
    private IngestedFileService ingestedFileService;
    private ResourceAccessGuard accessGuard;
    private RestRagIngestion rest;

    @BeforeEach
    void setUp() {
        restRagStore = mock(IRestRagStore.class);
        sourceIngestionService = mock(RagSourceIngestionService.class);
        accessGuard = mock(ResourceAccessGuard.class);
        ingestedFileService = mock(IngestedFileService.class);
        rest = new RestRagIngestion(restRagStore, mock(RagIngestionService.class), sourceIngestionService,
                ingestedFileService, accessGuard);

        when(restRagStore.readRag(eq(KB_ID), anyInt())).thenReturn(knowledgeBaseWithSource());
        when(sourceIngestionService.runAsync(anyString(), any(), any())).thenReturn(Optional.of("run-key"));
        when(sourceIngestionService.purge(anyString(), any())).thenReturn(true);
    }

    private static RagConfiguration knowledgeBaseWithSource() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://example.com/");

        var source = new IngestionSource();
        source.setId(SOURCE_ID);
        source.setName("docs");
        source.setWeb(web);

        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setSources(List.of(source));
        return config;
    }

    @Test
    @DisplayName("running a source requires EDIT, not the VIEW that reading the config needs")
    void runRequiresEdit() {
        rest.runSource(KB_ID, SOURCE_ID, 1);

        ArgumentCaptor<AccessLevel> level = ArgumentCaptor.forClass(AccessLevel.class);
        verify(accessGuard).requireAccess(eq(KB_ID), level.capture(), anyString());
        assertEquals(AccessLevel.EDIT, level.getValue());
    }

    @Test
    @DisplayName("a refused run is a 403 and never reaches the service")
    void refusedRunDoesNotIngest() {
        doThrow(new ForbiddenException("nope")).when(accessGuard)
                .requireAccess(anyString(), eq(AccessLevel.EDIT), anyString());

        assertThrows(ForbiddenException.class, () -> rest.runSource(KB_ID, SOURCE_ID, 1));
        verify(sourceIngestionService, never()).runAsync(anyString(), any(), any());
    }

    @Test
    @DisplayName("a started run is 202")
    void startedRunIsAccepted() {
        Response response = rest.runSource(KB_ID, SOURCE_ID, 1);

        assertEquals(Response.Status.ACCEPTED.getStatusCode(), response.getStatus());
    }

    @Test
    @DisplayName("a run refused because one is in flight is 409, not a second crawl")
    void concurrentRunIsConflict() {
        when(sourceIngestionService.runAsync(anyString(), any(), any())).thenReturn(Optional.empty());

        Response response = rest.runSource(KB_ID, SOURCE_ID, 1);

        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
    }

    @Test
    @DisplayName("a store that is down is not reported as a missing knowledge base")
    void storeFailureIsNotANotFound() {
        // Answering 404 for an outage hides it behind a client error: the caller is
        // told their knowledge base does not exist while it is sitting there, and
        // nobody goes to look at the database.
        when(restRagStore.readRag(eq(KB_ID), anyInt())).thenThrow(new IllegalStateException("connection refused"));

        assertThrows(IllegalStateException.class, () -> rest.runSource(KB_ID, SOURCE_ID, 1));
        verify(sourceIngestionService, never()).runAsync(anyString(), any(), any());
    }

    @Test
    @DisplayName("a disabled source is refused rather than started and then failed")
    void disabledSourceIsConflict() {
        var config = knowledgeBaseWithSource();
        config.getSources().get(0).setEnabled(false);
        when(restRagStore.readRag(eq(KB_ID), anyInt())).thenReturn(config);

        Response response = rest.runSource(KB_ID, SOURCE_ID, 1);

        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        verify(sourceIngestionService, never()).runAsync(anyString(), any(), any());
    }

    @Test
    @DisplayName("purging while a run is in flight is refused")
    void purgeDuringRunIsConflict() {
        // The purge deletes the RUNNING row, which is the only thing stopping a
        // second crawl into the same knowledge base. Whether a run holds the source
        // is the purge's own answer, taken under the run claim: a check made here
        // first let a run start in between.
        when(sourceIngestionService.purge(anyString(), any())).thenReturn(false);

        Response response = rest.purgeSource(KB_ID, SOURCE_ID, 1);

        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        verify(sourceIngestionService, never()).activeRun(anyString(), any());
    }

    @Test
    @DisplayName("a preview with no slot free is 429, not a server error")
    void busyPreviewIsTooManyRequests() {
        when(sourceIngestionService.preview(anyString(), any(), any()))
                .thenThrow(new PreviewBusyException("Too many previews are running."));

        Response response = rest.previewSource(KB_ID, SOURCE_ID, 1);

        assertEquals(429, response.getStatus());
        assertEquals("30", response.getHeaderString("Retry-After"));
    }

    @Test
    @DisplayName("a missing version is 400, not a 404 blaming the knowledge base")
    void missingVersionIsBadRequest() {
        // An omitted ?version binds as null; RestVersionInfo.read throws on it and the
        // catch turned that into "RAG configuration not found", which sends the caller
        // looking for the wrong problem.
        Response response = rest.runSource(KB_ID, SOURCE_ID, null);

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        verify(sourceIngestionService, never()).runAsync(anyString(), any(), any());
    }

    @Test
    @DisplayName("an unknown source is 404 rather than a silent no-op")
    void unknownSourceIsNotFound() {
        Response response = rest.runSource(KB_ID, "does-not-exist", 1);

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        verify(sourceIngestionService, never()).runAsync(anyString(), any(), any());
    }

    @Test
    @DisplayName("an unknown knowledge base is 404")
    void unknownKnowledgeBaseIsNotFound() {
        // doAnswer, not thenThrow: readRag declares no checked exception and
        // production sneaky-throws this one, which Mockito's thenThrow refuses.
        when(restRagStore.readRag(eq(KB_ID), anyInt())).thenAnswer(invocation -> {
            throw new IResourceStore.ResourceNotFoundException("gone");
        });

        Response response = rest.runSource(KB_ID, SOURCE_ID, 1);

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    private static IngestionPipeline.IngestionReport previewReport() {
        return new IngestionPipeline.IngestionReport("preview", SOURCE_ID,
                IngestionPipeline.IngestionReport.Outcome.PREVIEW, 1, 1, 0, 0, 0, 0, 0, 0.0,
                false, false, null, Duration.ZERO, null);
    }

    @Test
    @DisplayName("a preview also requires EDIT — it still crawls a third party's site")
    void previewRequiresEdit() {
        when(sourceIngestionService.preview(anyString(), any(), any()))
                .thenReturn(previewReport());

        rest.previewSource(KB_ID, SOURCE_ID, 1);

        verify(accessGuard).requireAccess(eq(KB_ID), eq(AccessLevel.EDIT), anyString());
    }

    @Test
    @DisplayName("reading run history needs only VIEW")
    void historyNeedsOnlyView() {
        when(sourceIngestionService.listRuns(anyString(), any(), anyInt())).thenReturn(List.of());

        rest.readSourceRuns(KB_ID, SOURCE_ID, 1, 20);

        verify(accessGuard).requireAccess(eq(KB_ID), eq(AccessLevel.VIEW), anyString());
    }

    @Test
    @DisplayName("an unbounded history request is clamped instead of loading everything")
    void historyLimitIsClamped() {
        when(sourceIngestionService.listRuns(anyString(), any(), anyInt())).thenReturn(List.of());

        rest.readSourceRuns(KB_ID, SOURCE_ID, 1, 100_000);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(sourceIngestionService).listRuns(anyString(), any(), limit.capture());
        assertTrue(limit.getValue() <= 200, "was " + limit.getValue());
    }

    @Test
    @DisplayName("a missing or nonsensical limit falls back to a default")
    void historyLimitDefaults() {
        when(sourceIngestionService.listRuns(anyString(), any(), anyInt())).thenReturn(List.of());

        rest.readSourceRuns(KB_ID, SOURCE_ID, 1, 0);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(sourceIngestionService).listRuns(anyString(), any(), limit.capture());
        assertEquals(20, limit.getValue());
    }

    @Test
    @DisplayName("purging requires EDIT — it discards content agents retrieve from")
    void purgeRequiresEdit() {
        Response response = rest.purgeSource(KB_ID, SOURCE_ID, 1);

        verify(accessGuard).requireAccess(eq(KB_ID), eq(AccessLevel.EDIT), anyString());
        verify(sourceIngestionService).purge(eq(KB_ID), any(IngestionSource.class));
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    }

    @Test
    @DisplayName("a refused purge never reaches the service")
    void refusedPurgeDoesNothing() {
        doThrow(new ForbiddenException("nope")).when(accessGuard)
                .requireAccess(anyString(), eq(AccessLevel.EDIT), anyString());

        assertThrows(ForbiddenException.class, () -> rest.purgeSource(KB_ID, SOURCE_ID, 1));
        verify(sourceIngestionService, never()).purge(anyString(), any());
    }
}
