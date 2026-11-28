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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private static final String USER_DIR = "\"user.dir\"";

    /**
     * A test that gets hold of either service: {@code new}, a reflective
     * constructor, or any field or local of the type ({@code @InjectMocks}, a
     * reflectively built instance) — and anything reading {@code user.dir}. A mere
     * mention in a comment or a {@code @link} does not count.
     */
    private static final Pattern SHARED_TMP_USE = Pattern.compile(
            "new\\s+Rest(Export|Import)Service\\s*\\("
                    + "|Rest(Export|Import)Service\\.class\\.getDeclaredConstructor"
                    + "|\\bRest(Export|Import)Service\\s+[A-Za-z_][A-Za-z0-9_]*\\s*[;=]"
                    + "|" + Pattern.quote(USER_DIR));

    /**
     * The annotation on a line of its own, as it is written on a top-level class.
     */
    private static final Pattern ANNOTATION_LINE = Pattern.compile("(?m)^@SharedWorkingDirTmp\\s*$");

    /** A top-level class declaration: the first one in the file is the class. */
    private static final Pattern TOP_LEVEL_CLASS = Pattern.compile("(?m)^(?:public |final |abstract )*class \\w+");

    @Test
    @DisplayName("every test that constructs the export or import service, or reads user.dir, carries @SharedWorkingDirTmp")
    void everySharedTmpTestIsSerialised() {
        List<String> unmarked = javaFiles(TEST_SOURCES)
                .filter(file -> !file.getFileName().toString().startsWith("SharedWorkingDirTmp"))
                .filter(file -> {
                    String source = read(file);
                    return SHARED_TMP_USE.matcher(source).find() && !annotatesTheTopLevelClass(source);
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
     * True only when the annotation sits on its own line above the file's first
     * top-level class declaration — not in a Javadoc mention, not on a nested
     * class.
     */
    private static boolean annotatesTheTopLevelClass(String source) {
        Matcher declaration = TOP_LEVEL_CLASS.matcher(source);
        Matcher annotation = ANNOTATION_LINE.matcher(source);
        return declaration.find() && annotation.find() && annotation.start() < declaration.start();
    }

    /**
     * The guard must recognise each way a test gets hold of the service, or it
     * grades nothing.
     */
    @Test
    @DisplayName("the guard recognises construction, reflection and fields, and ignores mentions")
    void theGuardRecognisesEveryWayToGetTheService() {
        for (String use : List.of("var s = new RestExportService(a, b);", "new RestImportService (a)",
                "RestImportService.class.getDeclaredConstructors()", "@InjectMocks RestExportService service;",
                "RestImportService service = createMinimalInstance();", "System.getProperty(\"user.dir\")")) {
            assertTrue(SHARED_TMP_USE.matcher(use).find(), "the guard must flag: " + use);
        }
        for (String mention : List.of("/** {@link RestImportService} helpers */", "// RestExportService builds tmp/",
                "RestExportService.EXPORT_ROOT")) {
            assertFalse(SHARED_TMP_USE.matcher(mention).find(), "a mention is not a use: " + mention);
        }
        assertTrue(annotatesTheTopLevelClass("@SharedWorkingDirTmp\nclass A {\n}"));
        assertFalse(annotatesTheTopLevelClass("/** see {@link SharedWorkingDirTmp} */\nclass A {\n}"));
        assertFalse(annotatesTheTopLevelClass("class A {\n    @SharedWorkingDirTmp\n    class B {}\n}"));
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
                .filter(file -> read(file).contains(USER_DIR))
                .map(file -> file.getFileName().toString())
                .sorted()
                .toList();

        assertFalse(users.isEmpty(), "found no production use of user.dir at all; the scan grades nothing");
        assertEquals(List.of("RestExportService.java", "RestImportService.java"), users,
                "a production class now derives a path from user.dir. Tests that construct it share the working"
                        + " directory across surefire forks: teach SHARED_TMP_USE above to recognise it.");
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
