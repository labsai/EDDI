/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.IngestionSource.GoogleDriveSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceReferenceGuard;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseInspector;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.ingestion.connector.SourceCredentials;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import ai.labs.eddi.modules.ingestion.drive.FakeDrive;
import ai.labs.eddi.modules.ingestion.drive.GoogleDriveSourceConnector;
import ai.labs.eddi.modules.ingestion.drive.GoogleDriveSourceConnector.PickerFolder;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.PlainTextExtractor;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The folder picker endpoint: who may call it, what it lists, how it pages, and
 * what a Drive refusal becomes.
 */
class RestRagIngestionDriveFoldersTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String SOURCE_ID = "src-drive";
    private static final String WEB_SOURCE_ID = "src-web";
    private static final String DRIVE = "0AdriveAAA";

    private FakeDrive fake;
    private ResourceAccessGuard accessGuard;
    private GoogleDriveSourceConnector connector;
    private RestRagIngestion rest;

    @BeforeEach
    void setUp() {
        fake = new FakeDrive(DRIVE);
        fake.folder("fSales", "Sales", DRIVE).folder("fLegal", "Legal", DRIVE)
                .folder("fOffers", "Offers", "fSales")
                .file("secret", "salaries.txt", "text/plain", DRIVE, "not for the picker");
        connector = new GoogleDriveSourceConnector(fake, new DocumentExtractors(List.of(new PlainTextExtractor())));
        SourceCredentials credentials = (name, url) -> new ResolvedCredential("Authorization", "Bearer fixture-token");

        IRestRagStore restRagStore = mock(IRestRagStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestRagIngestion(restRagStore, mock(RagIngestionService.class), mock(RagSourceIngestionService.class),
                mock(IngestedFileService.class), mock(KnowledgeBaseInspector.class), accessGuard,
                mock(SpaceReferenceGuard.class), connector, credentials);
        when(restRagStore.readRag(eq(KB_ID), anyInt())).thenReturn(knowledgeBase());
    }

    private static RagConfiguration knowledgeBase() {
        var drive = new GoogleDriveSource();
        drive.setConnection("google-drive-sa");
        drive.setSharedDriveId(DRIVE);
        var driveSource = new IngestionSource();
        driveSource.setId(SOURCE_ID);
        driveSource.setName("handbook");
        driveSource.setType(IngestionSource.TYPE_GOOGLE_DRIVE);
        driveSource.setGoogleDrive(drive);

        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://example.com/");
        var webSource = new IngestionSource();
        webSource.setId(WEB_SOURCE_ID);
        webSource.setName("docs");
        webSource.setWeb(web);

        var config = new RagConfiguration();
        config.setName("kb");
        config.setSources(List.of(driveSource, webSource));
        return config;
    }

    @SuppressWarnings("unchecked")
    private static List<PickerFolder> folders(Response response) {
        return (List<PickerFolder>) ((Map<String, Object>) response.getEntity()).get("folders");
    }

    private static Object nextPageToken(Response response) {
        return ((Map<?, ?>) response.getEntity()).get("nextPageToken");
    }

    @Test
    @DisplayName("needs EDIT on the knowledge base, like running the source")
    void requiresEdit() {
        rest.listDriveFolders(KB_ID, SOURCE_ID, 1, "root", null);

        ArgumentCaptor<AccessLevel> level = ArgumentCaptor.forClass(AccessLevel.class);
        verify(accessGuard).requireAccess(eq(KB_ID), level.capture(), anyString());
        assertEquals(AccessLevel.EDIT, level.getValue());
    }

    @Test
    @DisplayName("a caller without EDIT is refused before Drive is asked anything")
    void refusedCallerReachesNoDrive() {
        doThrow(new ForbiddenException("nope")).when(accessGuard)
                .requireAccess(anyString(), eq(AccessLevel.EDIT), anyString());

        assertThrows(ForbiddenException.class, () -> rest.listDriveFolders(KB_ID, SOURCE_ID, 1, "root", null));
        assertTrue(fake.requests.isEmpty());
    }

    @Test
    @DisplayName("lists one level of folders, and no file")
    void listsFolders() {
        Response response = rest.listDriveFolders(KB_ID, SOURCE_ID, 1, "root", null);

        assertEquals(200, response.getStatus());
        assertEquals(List.of("Legal", "Sales"), folders(response).stream().map(PickerFolder::name).toList());
        assertNull(nextPageToken(response));

        Response child = rest.listDriveFolders(KB_ID, SOURCE_ID, 1, "fSales", null);
        assertEquals(List.of(new PickerFolder("fOffers", "Offers")), folders(child));
    }

    @Test
    @DisplayName("pages through a large folder with the token it hands out")
    void paging() {
        for (int i = 0; i < 230; i++) {
            fake.folder(String.format("fTeam%03d", i), String.format("Team %03d", i), "fLegal");
        }

        Response first = rest.listDriveFolders(KB_ID, SOURCE_ID, 1, "fLegal", null);
        Response second = rest.listDriveFolders(KB_ID, SOURCE_ID, 1, "fLegal", (String) nextPageToken(first));

        assertEquals(200, folders(first).size());
        assertEquals(30, folders(second).size());
        assertNull(nextPageToken(second));
    }

    @Test
    @DisplayName("a source that is not a Google Drive source is a 400")
    void notADriveSource() {
        assertEquals(400, rest.listDriveFolders(KB_ID, WEB_SOURCE_ID, 1, "root", null).getStatus());
        assertTrue(fake.requests.isEmpty());
    }

    @Test
    @DisplayName("an unknown source is a 404, and a missing version a 400")
    void unknownSource() {
        assertEquals(404, rest.listDriveFolders(KB_ID, "nope", 1, "root", null).getStatus());
        assertEquals(400, rest.listDriveFolders(KB_ID, SOURCE_ID, null, "root", null).getStatus());
    }

    @Test
    @DisplayName("a malformed parentId is a 400 and reaches no Drive")
    void malformedParent() {
        assertEquals(400, rest.listDriveFolders(KB_ID, SOURCE_ID, 1, "a' or 'b", null).getStatus());
        assertTrue(fake.requests.isEmpty());
    }

    @Test
    @DisplayName("a folder of another drive is a 400; a missing one a 404")
    void foreignAndMissing() {
        fake.folder("fElsewhere", "Elsewhere", "0Aother").inOtherDrive("fElsewhere");

        assertEquals(400, rest.listDriveFolders(KB_ID, SOURCE_ID, 1, "fElsewhere", null).getStatus());
        assertEquals(404, rest.listDriveFolders(KB_ID, SOURCE_ID, 1, "fNoSuch", null).getStatus());
    }

    @Test
    @DisplayName("a drive the service account cannot see is a 422, not a 403 that reads as the caller's own refusal")
    void driveNotVisible() {
        fake.driveVisible = false;

        Response response = rest.listDriveFolders(KB_ID, SOURCE_ID, 1, "root", null);

        assertEquals(422, response.getStatus());
        assertTrue(String.valueOf(((Map<?, ?>) response.getEntity()).get("error")).contains("not a member"));
    }

    @Test
    @DisplayName("Drive throttling is a 429 with Retry-After")
    void throttled() throws SourceException {
        GoogleDriveSourceConnector throttled = mock(GoogleDriveSourceConnector.class);
        when(throttled.listFolders(any(), any(), any()))
                .thenThrow(new SourceException(new SourceError.Throttled("slow down", Duration.ofSeconds(7))));
        IRestRagStore store = mock(IRestRagStore.class);
        when(store.readRag(eq(KB_ID), anyInt())).thenReturn(knowledgeBase());
        RestRagIngestion throttledRest = new RestRagIngestion(store, mock(RagIngestionService.class),
                mock(RagSourceIngestionService.class), mock(IngestedFileService.class),
                mock(KnowledgeBaseInspector.class), accessGuard, mock(SpaceReferenceGuard.class), throttled,
                (name, url) -> null);

        Response response = throttledRest.listDriveFolders(KB_ID, SOURCE_ID, 1, "root", null);

        assertEquals(429, response.getStatus());
        assertEquals("7", response.getHeaderString("Retry-After"));
    }
}
