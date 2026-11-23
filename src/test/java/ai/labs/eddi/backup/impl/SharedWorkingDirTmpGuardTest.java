/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests run in several surefire forks at once (CI passes
 * {@code -Dsurefire.forkCount}), and every fork has the same working directory.
 * A test that touches {@code <working directory>/tmp/} is only safe while
 * {@link SharedWorkingDirTmp} serialises it against the others; without it the
 * failure is intermittent — one fork's cleanup deleting another's export — so
 * nothing else would point at the missing annotation.
 */
@DisplayName("tests sharing the working directory's tmp/ are serialised across forks")
class SharedWorkingDirTmpGuardTest {

    private static final Path TEST_SOURCES = Path.of("src", "test", "java");
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final String USER_DIR_LOOKUP = "getProperty(\"user.dir\")";
    private static final List<String> SHARED_TMP_USES = List.of(
            "new " + "RestExportService(", "new " + "RestImportService(", USER_DIR_LOOKUP);

    @Test
    @DisplayName("every test that constructs the export or import service, or reads user.dir, carries @SharedWorkingDirTmp")
    void everySharedTmpTestIsSerialised() {
        List<String> unmarked = javaFiles(TEST_SOURCES)
                .filter(file -> !file.getFileName().toString().startsWith("SharedWorkingDirTmp"))
                .filter(file -> {
                    String source = read(file);
                    return SHARED_TMP_USES.stream().anyMatch(source::contains)
                            && !source.contains("@" + SharedWorkingDirTmp.class.getSimpleName());
                })
                .map(Path::toString)
                .sorted()
                .toList();

        assertEquals(List.of(), unmarked,
                "these tests share <working directory>/tmp/ with every other surefire fork but are not annotated"
                        + " @SharedWorkingDirTmp, so they can delete each other's files when they happen to run at"
                        + " the same time. Annotate the top-level class.");
    }

    /**
     * The test above recognises a shared-tmp test by the classes it constructs. A
     * third production class deriving a path from {@code user.dir} would be a third
     * such signal this guard does not know, so it has to be added here consciously
     * rather than slip past.
     */
    @Test
    @DisplayName("only the export and import services derive a scratch path from user.dir")
    void onlyTheKnownServicesUseTheWorkingDirectory() {
        List<String> users = javaFiles(MAIN_SOURCES)
                .filter(file -> read(file).contains(USER_DIR_LOOKUP))
                .map(file -> file.getFileName().toString())
                .sorted()
                .toList();

        assertFalse(users.isEmpty(), "found no production use of user.dir at all; the scan grades nothing");
        assertEquals(List.of("RestExportService.java", "RestImportService.java"), users,
                "a production class now derives a path from user.dir. Tests that construct it share the working"
                        + " directory across surefire forks: add `new <ThatClass>(` to SHARED_TMP_USES above.");
    }

    private static Stream<Path> javaFiles(Path root) {
        try {
            return Files.walk(root).filter(file -> file.toString().endsWith(".java")).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
