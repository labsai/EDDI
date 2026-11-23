/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.extract.document.Block;
import ai.labs.eddi.modules.ingestion.extract.document.DocumentManifest;
import ai.labs.eddi.modules.ingestion.extract.document.DocumentUnit;
import ai.labs.eddi.modules.ingestion.extract.document.SplitConfig;
import ai.labs.eddi.modules.ingestion.extract.document.SplitStrategy;
import ai.labs.eddi.modules.ingestion.extract.document.TextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The windowed overloads against the array overloads, on the same bytes: the
 * same manifest and the same blocks, whatever the window size — and the bounds
 * per window, not per document.
 */
@DisplayName("PdfStructureExtractor — a file read window by window")
class PdfStructureExtractorWindowedTest {

    private final PdfStructureExtractor extractor = new PdfStructureExtractor();

    @TempDir
    Path dir;

    private PdfWindowReader.Options reading;

    @BeforeEach
    void setUp() throws IOException {
        reading = PdfWindowReader.Options.of(Files.createDirectories(dir.resolve("scratch")), null, null);
    }

    static Stream<Arguments> documents() {
        return Stream.of(
                Arguments.of("outlined chapters", PdfFixtures.outlined(4, 6), SplitConfig.defaults()),
                Arguments.of("chapters of uneven length", PdfFixtures.chapters(1, 9, 2, 13), SplitConfig.defaults()),
                Arguments.of("no outline", PdfFixtures.plain(23), SplitConfig.defaults()),
                Arguments.of("no text", PdfFixtures.blank(12), SplitConfig.defaults()),
                Arguments.of("hostile titles", PdfFixtures.hostileTitles(), SplitConfig.defaults()),
                Arguments.of("headings only", PdfFixtures.headingsOnly(), SplitConfig.defaults()),
                Arguments.of("two columns", PdfFixtures.twoColumn(), SplitConfig.defaults()),
                Arguments.of("many headings", PdfFixtures.manyHeadings(9, 3), SplitConfig.defaults()),
                Arguments.of("named destinations", PdfFixtures.namedDestinations(), SplitConfig.defaults()),
                Arguments.of("outline cut into small units",
                        PdfFixtures.outlined(3, 8), new SplitConfig(SplitStrategy.OUTLINE, 3, 400, 50, 200)),
                Arguments.of("page windows", PdfFixtures.plain(17), new SplitConfig(SplitStrategy.PAGES, 4, 0, 0, 0)),
                Arguments.of("headings asked for", PdfFixtures.headingsOnly(),
                        new SplitConfig(SplitStrategy.HEADINGS, 0, 0, 0, 0)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documents")
    @DisplayName("the manifest equals the array overload's, at every window size")
    void sameManifest(String name, byte[] pdf, SplitConfig split) {
        Path file = PdfFixtures.writeTo(dir.resolve("doc.pdf"), pdf);
        DocumentManifest expected = extractor.manifest(pdf, "ref", split, null);

        for (int windowPages : new int[]{1, 2, 7, 50}) {
            assertEquals(expected, extractor.manifest(file, "ref", split, null, reading, windowPages),
                    name + " in windows of " + windowPages);
        }
    }

    @Test
    @DisplayName("pages past the measured ones are averaged as in the array overload")
    void measuredPagesCapped() {
        byte[] pdf = PdfFixtures.outlined(5, 7);
        Path file = PdfFixtures.writeTo(dir.resolve("doc.pdf"), pdf);
        var limits = new ExtractionLimits(200_000, 11, 5_000, 64, 64L * 1024 * 1024);

        DocumentManifest expected = extractor.manifest(pdf, "ref", null, limits);
        assertTrue(expected.warnings().stream().anyMatch(warning -> warning.startsWith("Only the first 11 of 35")),
                expected.warnings().toString());
        for (int windowPages : new int[]{1, 4, 11, 50}) {
            assertEquals(expected, extractor.manifest(file, "ref", null, limits, reading, windowPages),
                    "windows of " + windowPages);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documents")
    @DisplayName("every unit's blocks equal the array overload's, at every window size")
    void sameBlocks(String name, byte[] pdf, SplitConfig split) {
        Path file = PdfFixtures.writeTo(dir.resolve("doc.pdf"), pdf);
        DocumentManifest manifest = extractor.manifest(pdf, "ref", split, null);

        for (DocumentUnit unit : manifest.units()) {
            List<Block> expected = extractor.blocks(pdf, unit, null, null);
            for (int windowPages : new int[]{1, 3, 50}) {
                assertEquals(expected, extractor.blocks(file, unit, null, null, reading, windowPages),
                        name + ", unit " + unit.id() + " in windows of " + windowPages);
            }
        }
    }

    @Test
    @DisplayName("the character allowance carries across windows: the read stops where the array read stops")
    void characterAllowanceAcrossWindows() {
        byte[] pdf = PdfFixtures.plain(12);
        Path file = PdfFixtures.writeTo(dir.resolve("doc.pdf"), pdf);
        var unit = new DocumentUnit("u1", null, 1, null, 1, 12, 0, 0, 0, 0);
        var limits = new ExtractionLimits(3_000, 500, 5_000, 64, 64L * 1024 * 1024);

        List<Block> expected = extractor.blocks(pdf, unit, null, limits);
        assertTrue(lastText(expected).startsWith("[The text of this part stops here"), lastText(expected));
        for (int windowPages : new int[]{1, 2, 5}) {
            assertEquals(expected, extractor.blocks(file, unit, null, limits, reading, windowPages),
                    "windows of " + windowPages);
        }
    }

    @Test
    @DisplayName("the page cap carries across windows")
    void pageCapAcrossWindows() {
        byte[] pdf = PdfFixtures.plain(12);
        Path file = PdfFixtures.writeTo(dir.resolve("doc.pdf"), pdf);
        var unit = new DocumentUnit("u1", null, 1, null, 2, 12, 0, 0, 0, 0);
        var limits = new ExtractionLimits(200_000, 5, 5_000, 64, 64L * 1024 * 1024);

        List<Block> expected = extractor.blocks(pdf, unit, null, limits);
        assertTrue(lastText(expected).startsWith("[Only the first 5 pages"), lastText(expected));
        assertEquals(expected, extractor.blocks(file, unit, null, limits, reading, 2));
    }

    @Test
    @DisplayName("a unit past the last page belongs to another file")
    void unitOfAnotherFile() {
        Path file = PdfFixtures.writeTo(dir.resolve("doc.pdf"), PdfFixtures.plain(3));
        var unit = new DocumentUnit("u1", null, 1, null, 2, 9, 0, 0, 0, 0);

        var refused = assertThrows(UnreadableDocumentException.class,
                () -> extractor.blocks(file, unit, null, null, reading, 50));
        assertTrue(refused.getMessage().contains("belongs to another file"), refused.getMessage());
    }

    @Test
    @DisplayName("the decode budget is per window: a document too heavy for one budget is read in windows")
    void decodeBudgetPerWindow() {
        // 30 pages decoding 256 KB each — 7.5 MB in all — against a 2 MB budget.
        byte[] pdf = PdfFixtures.heavyPages(30, 256 * 1024);
        Path file = PdfFixtures.writeTo(dir.resolve("heavy.pdf"), pdf);
        var limits = new ExtractionLimits(200_000, 500, 5_000, 64, 1024 * 1024);
        var tight = PdfWindowReader.Options.of(reading.scratchDir(), limits, null);

        var refused = assertThrows(UnreadableDocumentException.class, () -> extractor.manifest(pdf, "ref", null, limits));
        assertTrue(refused.getMessage().contains("expands to more than"), refused.getMessage());

        // What the array overload finds when the budget is not in the way.
        var roomy = new ExtractionLimits(200_000, 500, 5_000, 64, 64L * 1024 * 1024);
        assertEquals(extractor.manifest(pdf, "ref", null, roomy), extractor.manifest(file, "ref", null, limits, tight, 2));
        assertThrows(UnreadableDocumentException.class,
                () -> extractor.manifest(file, "ref", null, limits, tight, 30), "one window of all 30 pages");
    }

    @Test
    @DisplayName("a compression bomb is refused by the file pre-scan before PDFBox opens it")
    void preScan() {
        Path file = PdfFixtures.writeTo(dir.resolve("bomb.pdf"), PdfFixtures.flateBomb(8 * 1024 * 1024));
        var limits = new ExtractionLimits(200_000, 500, 5_000, 64, 1024 * 1024);

        var refused = assertThrows(UnreadableDocumentException.class,
                () -> extractor.manifest(file, "ref", null, limits, PdfWindowReader.Options.of(reading.scratchDir(),
                        ExtractionLimits.defaults(), null), 50));
        assertTrue(refused.getMessage().contains("compressed stream that expands"), refused.getMessage());
    }

    @Test
    @DisplayName("a window deadline that cannot be met refuses the read (the pre-scan is the first to see it)")
    void windowDeadline() {
        Path file = PdfFixtures.writeTo(dir.resolve("doc.pdf"), PdfFixtures.plain(3));

        var refused = assertThrows(UnreadableDocumentException.class, () -> extractor.manifest(file, "ref", null,
                null, reading.withWindowDeadline(Duration.ofNanos(1)), 50));
        assertEquals(PdfWindowReader.TOO_LONG, refused.getMessage());
    }

    @Test
    @DisplayName("blocks can skip the pre-scan a manifest already ran; the decode budget still holds")
    void blocksWithoutPreScan() {
        Path bomb = PdfFixtures.writeTo(dir.resolve("bomb.pdf"), PdfFixtures.flateBomb(8 * 1024 * 1024));
        var limits = new ExtractionLimits(200_000, 500, 5_000, 64, 1024 * 1024);
        var tight = PdfWindowReader.Options.of(reading.scratchDir(), limits, null);
        var unit = new DocumentUnit("u1", null, 1, null, 1, 1, 0, 0, 0, 0);

        var scanned = assertThrows(UnreadableDocumentException.class,
                () -> extractor.blocks(bomb, unit, null, limits, tight, 50));
        assertTrue(scanned.getMessage().contains("compressed stream that expands"), scanned.getMessage());
        var budgeted = assertThrows(UnreadableDocumentException.class,
                () -> extractor.blocks(bomb, unit, null, limits, tight, 50, false));
        assertTrue(budgeted.getMessage().contains("one window of pages"), budgeted.getMessage());
        assertThrows(UnreadableDocumentException.class, () -> PdfStructureExtractor.preScan(bomb, limits, tight));
    }

    @Test
    @DisplayName("a page tree listing a page twice is refused as in the array overload")
    void duplicatePage() {
        byte[] pdf = PdfFixtures.duplicatePageInTree();
        Path file = PdfFixtures.writeTo(dir.resolve("doc.pdf"), pdf);

        var expected = assertThrows(UnreadableDocumentException.class, () -> extractor.manifest(pdf, "ref", null, null));
        var refused = assertThrows(UnreadableDocumentException.class,
                () -> extractor.manifest(file, "ref", null, null, reading, 50));
        assertEquals(expected.getMessage(), refused.getMessage());
    }

    @Test
    @DisplayName("window size must be at least one page")
    void windowSize() {
        Path file = PdfFixtures.writeTo(dir.resolve("doc.pdf"), PdfFixtures.plain(1));

        assertThrows(IllegalArgumentException.class, () -> extractor.manifest(file, "ref", null, null, reading, 0));
    }

    private static String lastText(List<Block> blocks) {
        return ((TextBlock) blocks.getLast()).text();
    }
}
