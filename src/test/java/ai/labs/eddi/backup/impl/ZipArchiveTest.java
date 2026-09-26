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
    void roundTrip_keepsTheConnectionsDirectoryOfAnAgentArchive(@TempDir Path tempDir) throws IOException {
        // The export writes connections/<id>.connection.json beside the agent file;
        // the import reads it back from the unzipped tree. The round trip has to
        // preserve the nested directory and the file's content byte for byte, or the
        // reference the agent carries dangles on the other side.
        Path sourceDir = tempDir.resolve("source");
        Path connectionsDir = sourceDir.resolve("connections");
        Files.createDirectories(connectionsDir);
        Files.writeString(sourceDir.resolve("aabb11112222333344445555.agent.json"), "{\"workflows\":[]}");
        String connectionJson = "{\"name\":\"jira\",\"staticAuth\":{\"valueTemplate\":\"Bearer ${vault:jira-token}\"}}";
        Files.writeString(connectionsDir.resolve("68a1b2c3d4e5f60718293a4b.connection.json"), connectionJson);
        Path targetZip = tempDir.resolve("agent.zip");

        zipArchive.createZip(sourceDir.toString(), targetZip.toString(), tempDir);
        File extracted = tempDir.resolve("extracted").toFile();
        try (InputStream is = new FileInputStream(targetZip.toFile())) {
            zipArchive.unzip(is, extracted);
        }

        File restored = new File(new File(extracted, "connections"), "68a1b2c3d4e5f60718293a4b.connection.json");
        assertTrue(restored.exists(), "the connections/ directory must survive the round trip");
        assertEquals(connectionJson, Files.readString(restored.toPath()));
        assertTrue(new File(extracted, "aabb11112222333344445555.agent.json").exists());
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

    // ==================== H15 — unpacking limits ====================

    /**
     * A decompression bomb: 8 MiB of zeros deflates to a few KiB, so the upload is
     * tiny and what lands on disk is not. The limit is counted from the bytes
     * actually inflated.
     */
    @Test
    void unzip_totalInflatedSizeOverTheLimit_isRefusedWithoutWritingItAll(@TempDir Path tempDir) throws IOException {
        byte[] zip = zipOf(8, 1024 * 1024);
        assertTrue(zip.length < 64 * 1024, "the fixture must be a small upload: " + zip.length);
        var limited = new ZipArchive(10_000, 4L * 1024 * 1024, 5L * 1024 * 1024);

        File targetDir = tempDir.resolve("extracted").toFile();
        var thrown = assertThrows(ZipArchive.ZipLimitExceededException.class,
                () -> limited.unzip(new ByteArrayInputStream(zip), targetDir));

        assertTrue(thrown.getMessage().contains(ZipArchive.MAX_TOTAL_BYTES_PROPERTY), thrown.getMessage());
        assertTrue(bytesUnder(targetDir.toPath()) <= 5L * 1024 * 1024 + 4096, "stopped at the limit, not after the whole archive");
    }

    @Test
    void unzip_oneEntryOverThePerEntryLimit_isRefused(@TempDir Path tempDir) throws IOException {
        byte[] zip = zipOf(1, 2 * 1024 * 1024);
        var limited = new ZipArchive(10_000, 1024 * 1024, 256L * 1024 * 1024);

        var thrown = assertThrows(ZipArchive.ZipLimitExceededException.class,
                () -> limited.unzip(new ByteArrayInputStream(zip), tempDir.resolve("extracted").toFile()));

        assertTrue(thrown.getMessage().contains(ZipArchive.MAX_ENTRY_BYTES_PROPERTY), thrown.getMessage());
    }

    /**
     * ZipInputStream trusts a STORED entry's header for how much to read, but a
     * DEFLATED entry's declared size is only compared after the data has been
     * inflated. Here the local header claims 16 bytes for an entry that inflates to
     * 2 MiB: the limit must trip while inflating, on the bytes actually produced.
     */
    @Test
    void unzip_entryWhoseHeaderUnderstatesItsSize_isStillRefused(@TempDir Path tempDir) throws IOException {
        byte[] zip = zipOf(1, 2 * 1024 * 1024);
        // Local file header: signature at 0, general-purpose flag at 6, uncompressed
        // size at 22. Clear the data-descriptor bit so the header's own size is the one
        // on record, and make it lie.
        assertEquals(0x04034b50, readIntLe(zip, 0));
        zip[6] = (byte) (zip[6] & ~0x08);
        writeIntLe(zip, 22, 16);
        var limited = new ZipArchive(10_000, 1024 * 1024, 256L * 1024 * 1024);

        File targetDir = tempDir.resolve("extracted").toFile();
        var thrown = assertThrows(IOException.class, () -> limited.unzip(new ByteArrayInputStream(zip), targetDir));

        assertTrue(thrown instanceof ZipArchive.ZipLimitExceededException, "tripped by the limit, not by the mismatch: " + thrown);
        assertTrue(bytesUnder(targetDir.toPath()) <= 1024 * 1024 + 4096, "stopped at the limit");
    }

    private static int readIntLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8 | (bytes[offset + 2] & 0xff) << 16 | (bytes[offset + 3] & 0xff) << 24;
    }

    private static void writeIntLe(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    @Test
    void unzip_moreEntriesThanTheLimit_isRefused(@TempDir Path tempDir) throws IOException {
        byte[] zip = zipOf(11, 10);
        var limited = new ZipArchive(10, 1024 * 1024, 256L * 1024 * 1024);

        var thrown = assertThrows(ZipArchive.ZipLimitExceededException.class,
                () -> limited.unzip(new ByteArrayInputStream(zip), tempDir.resolve("extracted").toFile()));

        assertTrue(thrown.getMessage().contains(ZipArchive.MAX_ENTRIES_PROPERTY), thrown.getMessage());
    }

    @Test
    void unzip_archiveWithinEveryLimit_isUnpackedWhole(@TempDir Path tempDir) throws IOException {
        byte[] zip = zipOf(10, 1024);
        var limited = new ZipArchive(10, 1024, 10 * 1024);

        File targetDir = tempDir.resolve("extracted").toFile();
        limited.unzip(new ByteArrayInputStream(zip), targetDir);

        assertEquals(10 * 1024, bytesUnder(targetDir.toPath()));
    }

    @Test
    void defaultLimitsAreTheDocumentedOnes() {
        assertEquals(10_000, ZipArchive.DEFAULT_MAX_ENTRIES);
        assertEquals(32L * 1024 * 1024, ZipArchive.DEFAULT_MAX_ENTRY_BYTES);
        assertEquals(256L * 1024 * 1024, ZipArchive.DEFAULT_MAX_TOTAL_BYTES);
    }

    /** {@code entries} files of {@code entryBytes} zeros each. */
    private static byte[] zipOf(int entries, int entryBytes) throws IOException {
        var out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            byte[] zeros = new byte[entryBytes];
            for (int i = 0; i < entries; i++) {
                zos.putNextEntry(new ZipEntry("dir/file" + i + ".json"));
                zos.write(zeros);
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static long bytesUnder(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }
}
