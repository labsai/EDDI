/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.testing.ChildJvm;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acceptance gate of the windowed path: a 1,200-page PDF of about 40 MB is
 * read to its manifest — every page, window by window — in a JVM whose heap is
 * {@code -Xmx256m}, and again in one whose heap is smaller than the file, which
 * no path that loads the file into memory can survive. Child JVMs, so the bound
 * does not depend on the heap surefire runs with.
 */
@DisplayName("PdfLargeFileHeapTest — a large PDF on a small heap")
class PdfLargeFileHeapTest {

    private static final int PAGES = 1_200;
    private static final long TARGET_BYTES = 40L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private static Path dir;
    private static Path pdf;

    @BeforeAll
    static void writeFixture() throws IOException {
        dir = Files.createTempDirectory("eddi-heap-test-");
        pdf = PdfFixtures.largeTo(dir.resolve("large.pdf"), PAGES, 100, TARGET_BYTES);
    }

    @AfterAll
    static void deleteFixture() throws IOException {
        if (dir != null) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    @DisplayName("the fixture is as large as it claims")
    void fixture() throws IOException {
        long size = Files.size(pdf);
        assertTrue(size > TARGET_BYTES * 9 / 10 && size < TARGET_BYTES * 11 / 10, "size " + size);
    }

    @Test
    @DisplayName("-Xmx256m: all 1,200 pages are read, cut by the outline")
    void gate() throws Exception {
        ChildJvm.Result result = probe("-Xmx256m");

        assertFalse(result.timedOut(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        assertEquals("OK pages=" + PAGES + " units=48 tokens=" + PAGES * tokensPerPage(), result.lastLine(),
                result.output());
    }

    @Test
    @DisplayName("-Xmx64m, less heap than the file is large: only a path that never holds the file survives")
    void heapSmallerThanTheFile() throws Exception {
        // Reading the same file through Files.readAllBytes and the array overload runs
        // out of memory at 64 MB (and at 48 MB); the windowed path needs less than 48.
        ChildJvm.Result result = probe("-Xmx64m");

        assertFalse(result.timedOut(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.lastLine().startsWith("OK pages=" + PAGES + " "), result.output());
    }

    /** What one page of the fixture estimates, from a small copy read in memory. */
    private static int tokensPerPage() throws IOException {
        Path small = PdfFixtures.largeTo(dir.resolve("small.pdf"), 10, 100, 0);
        var manifest = new PdfStructureExtractor().manifest(Files.readAllBytes(small), "small", null, null);
        int tokens = manifest.leaves().stream().mapToInt(unit -> unit.estimatedTokens()).sum();
        assertEquals(0, tokens % 10, "every page alike");
        return tokens / 10;
    }

    private static ChildJvm.Result probe(String heap) throws Exception {
        Path scratch = Files.createTempDirectory(dir, "scratch-");
        return ChildJvm.run(PdfLargeFileHeapProbe.class, List.of(heap, "-XX:+UseSerialGC"),
                List.of(pdf.toString(), scratch.toString(), "50"), TIMEOUT);
    }
}
