/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileUtilitiesTest {

    @Test
    void readTextFromFile_readsContent(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("test.txt").toFile();
        Files.writeString(file.toPath(), "line1\nline2\n");

        String content = FileUtilities.readTextFromFile(file);

        assertTrue(content.contains("line1"));
        assertTrue(content.contains("line2"));
    }

    @Test
    void readTextFromFile_emptyFile_returnsEmpty(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("empty.txt").toFile();
        Files.writeString(file.toPath(), "");

        String content = FileUtilities.readTextFromFile(file);

        assertEquals("", content);
    }

    @Test
    void readTextFromFile_nonExistentFile_throwsIOException() {
        assertThrows(IOException.class,
                () -> FileUtilities.readTextFromFile(new File("/nonexistent/path/file.txt")));
    }

    @Test
    void buildPath_twoDirectories_hasTrailingSeparator() {
        String path = FileUtilities.buildPath("dir1", "dir2");
        assertTrue(path.endsWith(File.separator));
    }

    @Test
    void buildPath_multipleDirectories_joinedWithSeparator() {
        String path = FileUtilities.buildPath("dir1", "dir2", "dir3");
        assertTrue(path.contains("dir1"));
        assertTrue(path.contains("dir2"));
        assertTrue(path.contains("dir3"));
    }

    @Test
    void buildPath_fileAtEnd_noTrailingSeparator() {
        String path = FileUtilities.buildPath("dir1", "file.txt");
        assertFalse(path.endsWith(File.separator));
    }

    @Test
    void buildPath_directoriesWithExistingSeparators_handledCorrectly() {
        String path = FileUtilities.buildPath("dir1" + File.separator, "dir2");
        // Should not double up separators
        assertFalse(path.contains(File.separator + File.separator));
    }

    /**
     * {@code buildPath("foo")} threw StringIndexOutOfBoundsException: with one
     * segment there is no separator, so lastIndexOf returned -1 and substring(-1)
     * blew up inside a path helper.
     */
    @Test
    void buildPath_singleSegment_doesNotThrow() {
        String path = assertDoesNotThrow(() -> FileUtilities.buildPath("foo"));
        assertEquals("foo" + File.separator, path);
    }

    /** Same crash from the other end: endsWith() on an empty builder. */
    @Test
    void buildPath_emptyFirstSegment_isSkippedRatherThanFatal() {
        String path = assertDoesNotThrow(() -> FileUtilities.buildPath("", "x"));
        assertEquals("x" + File.separator, path);
    }

    @Test
    void buildPath_noSegments_returnsEmpty() {
        assertEquals("", FileUtilities.buildPath());
        assertEquals("", FileUtilities.buildPath("", ""));
    }

    @Test
    void buildPath_nullSegment_isSkipped() {
        assertEquals("dir1" + File.separator + "file.txt", FileUtilities.buildPath("dir1", null, "file.txt"));
    }

    @Test
    void readTextFromFile_readsUtf8Exactly(@TempDir Path tempDir) throws IOException {
        // ready() is "can I read without blocking", not "is there more" — the old loop
        // used it as an EOF test and could return a silently truncated string. It also
        // read with the platform default charset, so the same file decoded differently
        // on different hosts.
        String content = "grüße\nzeile2\n";
        Path file = tempDir.resolve("utf8.txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertEquals(content, FileUtilities.readTextFromFile(file.toFile()));
    }
}
