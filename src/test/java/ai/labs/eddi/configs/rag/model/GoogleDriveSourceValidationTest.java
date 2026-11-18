/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import ai.labs.eddi.configs.rag.model.IngestionSource.GoogleDriveSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Save-time rules of the {@code googleDrive} source block. */
class GoogleDriveSourceValidationTest {

    private IngestionSource source;
    private GoogleDriveSource drive;

    @BeforeEach
    void setUp() {
        drive = new GoogleDriveSource();
        drive.setConnection("google-drive-sa");
        drive.setSharedDriveId("0AdriveAAA");
        source = new IngestionSource();
        source.setName("handbook");
        source.setType(IngestionSource.TYPE_GOOGLE_DRIVE);
        source.setGoogleDrive(drive);
    }

    private String refusal() {
        return assertThrows(IllegalArgumentException.class, source::validate).getMessage();
    }

    @Test
    @DisplayName("a connection and a Shared Drive are all a source needs; the rest defaults")
    void minimalSource() {
        assertDoesNotThrow(source::validate);
        assertEquals(List.of("0AdriveAAA"), drive.rootFolderIds());
        assertEquals(GoogleDriveSource.FORMATS.size(), drive.formatsOrDefault().size());
        assertEquals(2_000, drive.maxFilesOrDefault());
        assertEquals(25L * 1024 * 1024, drive.maxFileBytesOrDefault());
        assertTrue(drive.isIncludeSubfolders());
    }

    @Test
    @DisplayName("the type needs its block")
    void blockRequired() {
        source.setGoogleDrive(null);
        assertTrue(refusal().contains("needs a 'googleDrive' block"));
    }

    @Test
    @DisplayName("a connection and a drive id are required")
    void requiredFields() {
        drive.setConnection(" ");
        assertTrue(refusal().startsWith("googleDrive.connection"));
        drive.setConnection("google-drive-sa");
        drive.setSharedDriveId(null);
        assertTrue(refusal().startsWith("googleDrive.sharedDriveId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"other/google-drive-sa", "${connection:x}", "has space", "-leading"})
    @DisplayName("a connection field that is not a plain connection name is refused")
    void connectionName(String name) {
        drive.setConnection(name);
        assertTrue(refusal().contains("is not a connection name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc' or name contains '", "a b", "../x", "id\""})
    @DisplayName("an id that could escape the listing query is refused, not escaped")
    void driveIds(String id) {
        drive.setSharedDriveId(id);
        assertTrue(refusal().contains("is not a Drive id"));
        drive.setSharedDriveId("0AdriveAAA");
        drive.setFolderIds(List.of("1folder", id));
        assertTrue(refusal().startsWith("googleDrive.folderIds"));
    }

    @Test
    @DisplayName("folders narrow the roots, duplicates folded")
    void folderRoots() {
        drive.setFolderIds(List.of("1a", "1b", "1a"));
        assertDoesNotThrow(source::validate);
        assertEquals(List.of("1a", "1b"), drive.rootFolderIds());
    }

    @Test
    @DisplayName("formats are the documented names only")
    void formats() {
        drive.setMimeTypes(List.of("google-docs", "pdf"));
        assertDoesNotThrow(source::validate);
        assertEquals(2, drive.formatsOrDefault().size());
        drive.setMimeTypes(List.of("application/pdf"));
        String message = refusal();
        assertTrue(message.contains("Accepted: google-docs"), message);
    }

    @Test
    @DisplayName("limits are bounded")
    void limits() {
        drive.setMaxFiles(0);
        assertTrue(refusal().startsWith("googleDrive.maxFiles"));
        drive.setMaxFiles(null);
        drive.setMaxFileBytes(51L * 1024 * 1024);
        assertTrue(refusal().startsWith("googleDrive.maxFileBytes"));
        drive.setMaxFileBytes(null);
        drive.setExcludeNamePatterns(List.of(""));
        assertTrue(refusal().startsWith("googleDrive.excludeNamePatterns"));
    }

    @Test
    @DisplayName("captureAcl is refused while DOCUMENT_ACL does not exist; false is accepted")
    void captureAcl() {
        drive.setCaptureAcl(false);
        assertDoesNotThrow(source::validate);
        drive.setCaptureAcl(true);
        String message = refusal();
        assertTrue(message.contains("DOCUMENT_ACL"), message);
    }

    @Test
    @DisplayName("a change of type makes a different source; the same type does not")
    void sameType() {
        var web = new IngestionSource();
        assertFalse(source.sameTypeAs(web));
        var again = new IngestionSource();
        again.setType(IngestionSource.TYPE_GOOGLE_DRIVE);
        assertTrue(source.sameTypeAs(again));
        var upload = new IngestionSource();
        upload.setType(IngestionSource.TYPE_UPLOAD);
        assertFalse(upload.sameTypeAs(web));
        web.setType(null);
        assertTrue(web.sameTypeAs(new IngestionSource()), "an absent type is web");
    }
}
