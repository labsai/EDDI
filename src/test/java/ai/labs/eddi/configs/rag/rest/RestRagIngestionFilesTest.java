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
import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.modules.ingestion.files.IngestedFileService;
import ai.labs.eddi.modules.rag.RagIngestionService;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * The uploaded-file endpoints of {@link RestRagIngestion}.
 *
 * <p>
 * The access rules matter here for the same reason they do for a run: what is
 * uploaded becomes what every agent using this knowledge base answers from, and
 * a published configuration grants VIEW to everyone by design. Reading the file
 * list is a VIEW; putting something in it, or taking something out, is an EDIT.
 */
@DisplayName("RestRagIngestion — uploaded files")
class RestRagIngestionFilesTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String UPLOAD_SOURCE = "src-files";
    private static final String WEB_SOURCE = "src-web";

    private IRestRagStore restRagStore;
    private RagSourceIngestionService sourceIngestionService;
    private IngestedFileService ingestedFileService;
    private ResourceAccessGuard accessGuard;
    private RestRagIngestion rest;

    @BeforeEach
    void setUp() {
        restRagStore = mock(IRestRagStore.class);
        sourceIngestionService = mock(RagSourceIngestionService.class);
        ingestedFileService = mock(IngestedFileService.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestRagIngestion(restRagStore, mock(RagIngestionService.class), sourceIngestionService,
                ingestedFileService, accessGuard);

        when(restRagStore.readRag(eq(KB_ID), anyInt())).thenReturn(knowledgeBase());
        when(sourceIngestionService.activeRun(anyString(), any())).thenReturn(Optional.empty());
        when(ingestedFileService.list(anyString(), any())).thenReturn(List.of(storedFile()));
        when(ingestedFileService.upload(anyString(), any(), any()))
                .thenReturn(new IngestedFileService.UploadOutcome(List.of(storedFile()), List.of()));
        when(ingestedFileService.delete(anyString(), any(), any(), anyString()))
                .thenReturn(IngestedFileService.DeleteOutcome.DELETED);
    }

    private static RagConfiguration knowledgeBase() {
        var upload = new IngestionSource();
        upload.setId(UPLOAD_SOURCE);
        upload.setName("handbooks");
        upload.setType(IngestionSource.TYPE_UPLOAD);

        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://example.com/");
        var crawl = new IngestionSource();
        crawl.setId(WEB_SOURCE);
        crawl.setName("docs");
        crawl.setWeb(web);

        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setSources(List.of(upload, crawl));
        return config;
    }

    private static IIngestedFileStore.StoredFile storedFile() {
        return new IIngestedFileStore.StoredFile("f1", KB_ID + ":" + UPLOAD_SOURCE, "handbook.pdf",
                "application/pdf", 1024, "abc123", Instant.parse("2026-09-18T09:00:00Z"));
    }

    /**
     * A multipart part backed by a real temporary file, as the runtime supplies.
     */
    private static FileUpload part(String fileName, String content) throws IOException {
        Path temporary = Files.createTempFile("upload", ".bin");
        temporary.toFile().deleteOnExit();
        Files.writeString(temporary, content);

        FileUpload upload = mock(FileUpload.class);
        when(upload.fileName()).thenReturn(fileName);
        when(upload.uploadedFile()).thenReturn(temporary);
        return upload;
    }

    @Test
    @DisplayName("uploading requires EDIT")
    void uploadRequiresEdit() throws IOException {
        rest.uploadSourceFiles(KB_ID, UPLOAD_SOURCE, 1, List.of(part("a.txt", "hello")));

        ArgumentCaptor<AccessLevel> level = ArgumentCaptor.forClass(AccessLevel.class);
        verify(accessGuard).requireAccess(eq(KB_ID), level.capture(), anyString());
        assertEquals(AccessLevel.EDIT, level.getValue());
    }

    @Test
    @DisplayName("a refused upload never reaches the store")
    void refusedUploadStoresNothing() throws IOException {
        doThrow(new ForbiddenException("nope")).when(accessGuard)
                .requireAccess(anyString(), eq(AccessLevel.EDIT), anyString());

        var file = part("a.txt", "hello");
        assertThrows(ForbiddenException.class,
                () -> rest.uploadSourceFiles(KB_ID, UPLOAD_SOURCE, 1, List.of(file)));
        verify(ingestedFileService, never()).upload(anyString(), any(), any());
    }

    @Test
    @DisplayName("listing files needs only VIEW")
    void listingNeedsOnlyView() {
        rest.readSourceFiles(KB_ID, UPLOAD_SOURCE, 1);

        ArgumentCaptor<AccessLevel> level = ArgumentCaptor.forClass(AccessLevel.class);
        verify(accessGuard).requireAccess(eq(KB_ID), level.capture(), anyString());
        assertEquals(AccessLevel.VIEW, level.getValue());
    }

    @Test
    @DisplayName("reports what was stored and what was refused")
    void reportsBothOutcomes() throws IOException {
        when(ingestedFileService.upload(anyString(), any(), any())).thenReturn(
                new IngestedFileService.UploadOutcome(
                        List.of(storedFile()),
                        List.of(new IngestedFileService.RejectedFile("locked.pdf", "This PDF is encrypted."))));

        Response response = rest.uploadSourceFiles(KB_ID, UPLOAD_SOURCE, 1,
                List.of(part("a.pdf", "x"), part("locked.pdf", "y")));

        // 200, not 400: one file of two failing is a success with a caveat, and a
        // client that treats 4xx as "nothing happened" would have the operator
        // re-uploading files that are already there.
        assertEquals(200, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals(1, ((List<?>) body.get("stored")).size());
        assertEquals(1, ((List<?>) body.get("rejected")).size());
    }

    @Test
    @DisplayName("a batch where nothing could be stored is a 400 that says why")
    void nothingStoredIsABadRequest() throws IOException {
        when(ingestedFileService.upload(anyString(), any(), any())).thenReturn(
                new IngestedFileService.UploadOutcome(List.of(),
                        List.of(new IngestedFileService.RejectedFile("chart.png", "Images hold no text."))));

        Response response = rest.uploadSourceFiles(KB_ID, UPLOAD_SOURCE, 1, List.of(part("chart.png", "x")));

        assertEquals(400, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertTrue(body.get("rejected").toString().contains("Images hold no text."), body.toString());
    }

    @Test
    @DisplayName("a request with no files says what to send instead of storing nothing quietly")
    void emptyRequestIsABadRequest() {
        Response response = rest.uploadSourceFiles(KB_ID, UPLOAD_SOURCE, 1, List.of());

        assertEquals(400, response.getStatus());
        verify(ingestedFileService, never()).upload(anyString(), any(), any());
    }

    @Test
    @DisplayName("refuses files on a source that crawls")
    void refusesFilesOnACrawlSource() throws IOException {
        Response response = rest.uploadSourceFiles(KB_ID, WEB_SOURCE, 1, List.of(part("a.txt", "x")));

        // 409 rather than 404: the source exists, it simply does not take files,
        // and telling the caller it is missing sends them looking for a bug
        // somewhere else.
        assertEquals(409, response.getStatus());
        verify(ingestedFileService, never()).upload(anyString(), any(), any());
    }

    @Test
    @DisplayName("a source that is not there is a 404")
    void unknownSourceIsNotFound() {
        assertEquals(404, rest.readSourceFiles(KB_ID, "nope", 1).getStatus());
    }

    @Test
    @DisplayName("an omitted version is a 400, not a 404 about a knowledge base that exists")
    void missingVersionIsABadRequest() {
        assertEquals(400, rest.readSourceFiles(KB_ID, UPLOAD_SOURCE, null).getStatus());
    }

    @Test
    @DisplayName("refuses to delete a file while a run is reading it")
    void refusesDeleteDuringARun() {
        when(sourceIngestionService.activeRun(anyString(), any())).thenReturn(
                Optional.of(new IIngestionStateStore.IngestionRun("run-1", KB_ID + ":" + UPLOAD_SOURCE,
                        IIngestionStateStore.IngestionRun.Status.RUNNING, Instant.now(), null,
                        0, 0, 0, 0, 0, 0, 0.0, null)));

        Response response = rest.deleteSourceFile(KB_ID, UPLOAD_SOURCE, "f1", 1);

        // The run is reading these files and writing state rows for them; removing
        // one underneath it leaves the run recording a document whose vectors were
        // just deleted.
        assertEquals(409, response.getStatus());
        verify(ingestedFileService, never()).delete(anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("a deleted file whose chunks could not go says so in the answer")
    void warnsWhenChunksRemain() {
        when(ingestedFileService.delete(anyString(), any(), any(), anyString()))
                .thenReturn(IngestedFileService.DeleteOutcome.DELETED_BUT_CHUNKS_REMAIN);

        Response response = rest.deleteSourceFile(KB_ID, UPLOAD_SOURCE, "f1", 1);

        assertEquals(200, response.getStatus());
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertTrue(String.valueOf(body.get("warning")).contains("still retrievable"), body.toString());
    }

    @Test
    @DisplayName("deleting a file that is not there is a 404")
    void deletingAnUnknownFileIsNotFound() {
        when(ingestedFileService.delete(anyString(), any(), any(), anyString()))
                .thenReturn(IngestedFileService.DeleteOutcome.NOT_FOUND);

        assertEquals(404, rest.deleteSourceFile(KB_ID, UPLOAD_SOURCE, "gone", 1).getStatus());
    }

    @Test
    @DisplayName("describes a file by what it is, not by where it is stored")
    void describesFiles() {
        Response response = rest.readSourceFiles(KB_ID, UPLOAD_SOURCE, 1);

        List<?> files = (List<?>) response.getEntity();
        Map<?, ?> file = (Map<?, ?>) files.getFirst();
        assertEquals("handbook.pdf", file.get("fileName"));
        assertEquals("application/pdf", file.get("mimeType"));
        assertEquals(1024L, file.get("sizeBytes"));
        // The source key is an internal address and says which knowledge base a
        // file belongs to; it has no business in an API response.
        assertTrue(file.get("fileId") != null);
        assertTrue(file.values().stream().noneMatch(value -> String.valueOf(value).contains(KB_ID + ":")),
                file.toString());
    }
}
