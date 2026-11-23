/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import ai.labs.eddi.configs.rag.model.IngestionSource.DriveFolder;
import ai.labs.eddi.configs.rag.model.IngestionSource.GoogleDriveSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which chunks of a Drive source retrieval keeps, judged by their folder chain.
 */
class DriveRetrievalScopeTest {

    private static final String DRIVE = "0AdriveAAA";
    private GoogleDriveSource drive;

    @BeforeEach
    void setUp() {
        drive = new GoogleDriveSource();
        drive.setConnection("google-drive-sa");
        drive.setSharedDriveId(DRIVE);
    }

    private DriveRetrievalScope scope(String recorded) {
        return DriveRetrievalScope.of(drive, recorded);
    }

    @Test
    @DisplayName("the whole drive with nothing excluded restricts nothing")
    void unrestricted() {
        assertFalse(scope(null).restricts());
        assertTrue(scope(null).admits("/a/b/"));
    }

    @Test
    @DisplayName("an excluded id hides its subtree at any depth, and exclusion wins over a root")
    void exclusionWins() {
        drive.setFolders(List.of(DriveFolder.ofId("a")));
        drive.setExcludeFolders(List.of(DriveFolder.ofId("b")));

        assertTrue(scope(null).admits("/a/"));
        assertTrue(scope(null).admits("/a/c/"));
        assertFalse(scope(null).admits("/a/b/"));
        assertFalse(scope(null).admits("/a/b/c/d/"));
    }

    @Test
    @DisplayName("a chunk outside every root is hidden; one without a chain is kept")
    void roots() {
        drive.setFolderIds(List.of("a"));

        assertFalse(scope(null).admits("/x/y/"));
        assertFalse(scope(null).admits("/"));
        assertTrue(scope(null).admits(null), "written before chains were recorded");
    }

    @Test
    @DisplayName("paths count through the ids their runs recorded")
    void recordedPaths() {
        drive.setFolders(List.of(DriveFolder.ofPath("Sales")));
        drive.setExcludeFolders(List.of(DriveFolder.ofPath("Sales/Archive")));
        String recorded = "{\"paths\":{\"include:Sales\":\"s\",\"exclude:Sales/Archive\":\"arc\"}}";

        assertTrue(scope(recorded).admits("/s/"));
        assertFalse(scope(recorded).admits("/s/arc/"));
        assertFalse(scope(recorded).admits("/legal/"));
    }

    @Test
    @DisplayName("a root path no run has resolved lifts the root restriction but keeps the exclusions")
    void unresolvedRoot() {
        drive.setFolders(List.of(DriveFolder.ofPath("Sales"), DriveFolder.ofPath("New")));
        drive.setExcludeFolders(List.of(DriveFolder.ofId("arc")));
        String recorded = "{\"paths\":{\"include:Sales\":\"s\"}}";

        assertTrue(scope(recorded).admits("/legal/"));
        assertFalse(scope(recorded).admits("/legal/arc/"));
    }

    @Test
    @DisplayName("with subfolders off only files directly in a root, or at the drive's top, stay")
    void noSubfolders() {
        drive.setIncludeSubfolders(false);
        assertTrue(scope(null).admits("/"));
        assertFalse(scope(null).admits("/a/"));

        drive.setFolderIds(List.of("a"));
        assertTrue(scope(null).admits("/a/"));
        assertFalse(scope(null).admits("/a/b/"));
    }
}
