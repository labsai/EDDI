package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
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
}
