/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ZipArchiveTest {

    private ZipArchive zipArchive;

    @BeforeEach
    void setUp() {
        zipArchive = new ZipArchive();
    }

    @Test
    void createZip_withFiles_producesValidZip(@TempDir Path tempDir) throws IOException {
        // Create source structure
        Path sourceDir = tempDir.resolve("source");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("file1.txt"), "content1");
        Files.writeString(sourceDir.resolve("file2.json"), "{\"key\": \"value\"}");

        Path targetZip = tempDir.resolve("output.zip");

        zipArchive.createZip(sourceDir.toString(), targetZip.toString(), tempDir);

        assertTrue(Files.exists(targetZip));
        assertTrue(Files.size(targetZip) > 0);

        // Verify contents
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(targetZip.toFile()))) {
            int count = 0;
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                assertTrue(entry.getName().equals("file1.txt") || entry.getName().equals("file2.json"));
                count++;
            }
            assertEquals(2, count);
        }
    }

    @Test
    void createZip_withSubdirectories_includesNestedFiles(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source");
        Path subDir = sourceDir.resolve("subdir");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("nested.txt"), "nested content");

        Path targetZip = tempDir.resolve("output.zip");

        zipArchive.createZip(sourceDir.toString(), targetZip.toString(), tempDir);

        assertTrue(Files.exists(targetZip));
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(targetZip.toFile()))) {
            ZipEntry entry = zis.getNextEntry();
            assertNotNull(entry);
            assertTrue(entry.getName().contains("nested.txt"));
        }
    }

    @Test
    void createZip_targetEscapesBaseDir_throwsIOException(@TempDir Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("source");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("file.txt"), "data");

        // Target path escapes the allowed base dir
        Path evilTarget = tempDir.resolveSibling("evil_output.zip");
        assertThrows(IOException.class,
                () -> zipArchive.createZip(sourceDir.toString(), evilTarget.toString(), tempDir));
    }

    @Test
    void unzip_validZip_extractsFiles(@TempDir Path tempDir) throws IOException {
        // Create a zip in memory
        Path zipFile = tempDir.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
            zos.putNextEntry(new ZipEntry("hello.txt"));
            zos.write("hello world".getBytes());
            zos.closeEntry();
        }

        // Unzip
        File targetDir = tempDir.resolve("extracted").toFile();
        try (InputStream is = new FileInputStream(zipFile.toFile())) {
            zipArchive.unzip(is, targetDir);
        }

        File extractedFile = new File(targetDir, "hello.txt");
        assertTrue(extractedFile.exists());
        assertEquals("hello world", Files.readString(extractedFile.toPath()));
    }

    @Test
    void unzip_zipSlipAttack_throwsIOException(@TempDir Path tempDir) throws IOException {
        // Create a malicious zip with path traversal
        Path zipFile = tempDir.resolve("evil.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
            zos.putNextEntry(new ZipEntry("../../evil.txt"));
            zos.write("malicious".getBytes());
            zos.closeEntry();
        }

        File targetDir = tempDir.resolve("extracted").toFile();
        try (InputStream is = new FileInputStream(zipFile.toFile())) {
            assertThrows(IOException.class, () -> zipArchive.unzip(is, targetDir));
        }
    }

    @Test
    void unzip_withDirectories_createsStructure(@TempDir Path tempDir) throws IOException {
        Path zipFile = tempDir.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
            zos.putNextEntry(new ZipEntry("subdir/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("subdir/file.txt"));
            zos.write("content".getBytes());
            zos.closeEntry();
        }

        File targetDir = tempDir.resolve("extracted").toFile();
        try (InputStream is = new FileInputStream(zipFile.toFile())) {
            zipArchive.unzip(is, targetDir);
        }

        assertTrue(new File(targetDir, "subdir").isDirectory());
        assertTrue(new File(targetDir, "subdir/file.txt").exists());
    }
}
