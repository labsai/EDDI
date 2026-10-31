/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entry shapes the inflation budget did not see: a directory entry carrying a
 * payload (drained, unmetered, by closeEntry) and an entry nested deep enough
 * for one mkdirs to create thousands of directories.
 */
@DisplayName("ZipArchive — directory payloads and nesting depth")
class ZipArchiveEntryShapeTest {

    private final ZipArchive zipArchive = new ZipArchive();

    @Test
    @DisplayName("a directory entry with data is refused")
    void directoryWithData(@TempDir Path tempDir) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(bytes)) {
            zos.putNextEntry(new ZipEntry("agent/"));
            zos.write(new byte[64 * 1024]);
            zos.closeEntry();
        }

        var thrown = assertThrows(ZipArchive.MalformedArchiveException.class,
                () -> zipArchive.unzip(new ByteArrayInputStream(bytes.toByteArray()), tempDir.resolve("out").toFile()));
        assertTrue(thrown.getMessage().contains("directory"), thrown.getMessage());
    }

    @Test
    @DisplayName("an empty directory entry is still fine")
    void emptyDirectory(@TempDir Path tempDir) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(bytes)) {
            zos.putNextEntry(new ZipEntry("agent/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("agent/a.agent.json"));
            zos.write("{}".getBytes());
            zos.closeEntry();
        }

        assertDoesNotThrow(() -> zipArchive.unzip(new ByteArrayInputStream(bytes.toByteArray()), tempDir.resolve("out").toFile()));
    }

    @Test
    @DisplayName("an entry nested deeper than the limit is refused")
    void tooDeep(@TempDir Path tempDir) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(bytes)) {
            zos.putNextEntry(new ZipEntry("d/".repeat(ZipArchive.MAX_ENTRY_DEPTH) + "f.json"));
            zos.write("{}".getBytes());
            zos.closeEntry();
        }

        var thrown = assertThrows(ZipArchive.MalformedArchiveException.class,
                () -> zipArchive.unzip(new ByteArrayInputStream(bytes.toByteArray()), tempDir.resolve("out").toFile()));
        assertTrue(thrown.getMessage().contains("deeper"), thrown.getMessage());
    }

    @Test
    @DisplayName("depth counts segments, ignoring a trailing separator")
    void depthOf() {
        assertEquals(1, ZipArchive.depthOf("a.json"));
        assertEquals(3, ZipArchive.depthOf("a/b/c.json"));
        assertEquals(2, ZipArchive.depthOf("a/b/"));
    }
}
