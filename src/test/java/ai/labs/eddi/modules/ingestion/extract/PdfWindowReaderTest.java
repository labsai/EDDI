/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PdfWindowReader — one window of a PDF file at a time")
class PdfWindowReaderTest {

    @TempDir
    Path dir;

    private Path scratch;
    private PdfWindowReader reader;

    @BeforeEach
    void setUp() throws IOException {
        scratch = Files.createDirectories(dir.resolve("scratch"));
        reader = new PdfWindowReader(PdfWindowReader.Options.of(scratch, ExtractionLimits.defaults(), null));
    }

    @Test
    @DisplayName("an operation reads the window's pages and gets plain data out")
    void readsAWindow() {
        Path pdf = PdfFixtures.writeTo(dir.resolve("plain.pdf"), PdfFixtures.plain(6));

        String text = reader.withWindow(pdf, 3, 4, (document, window) -> text(document, window));

        assertTrue(text.contains("Page 3 line 1:"), text);
        assertTrue(text.contains("Page 4 line 15:"), text);
        assertFalse(text.contains("Page 2 line"), text);
        assertFalse(text.contains("Page 5 line"), text);
    }

    @Test
    @DisplayName("the document is closed when the window ends, and so is the file")
    void closesTheDocument() throws IOException {
        Path pdf = PdfFixtures.writeTo(dir.resolve("plain.pdf"), PdfFixtures.plain(2));
        var seen = new AtomicReference<PDDocument>();

        reader.withWindow(pdf, 1, 1, (document, window) -> {
            seen.set(document);
            return null;
        });

        assertTrue(seen.get().getDocument().isClosed());
        Files.delete(pdf); // fails on Windows while a handle is open
        try (var left = Files.list(scratch)) {
            assertEquals(0, left.count(), "no scratch file outlives the window");
        }
    }

    @Test
    @DisplayName("the document's resource cache keeps nothing")
    void noResourceCache() {
        Path pdf = PdfFixtures.writeTo(dir.resolve("plain.pdf"), PdfFixtures.plain(2));

        Object cache = reader.withWindow(pdf, 1, 2, (document, window) -> {
            text(document, window);
            return document.getResourceCache();
        });

        assertInstanceOf(PdfWindowReader.NoResourceCache.class, cache);
    }

    @Test
    @DisplayName("the decode budget is per window: a document too heavy as a whole is read window by window")
    void budgetPerWindow() {
        // 40 pages decoding 256 KB each — 10 MB in all — against a 2 MB window budget.
        Path pdf = PdfFixtures.writeTo(dir.resolve("heavy.pdf"), PdfFixtures.heavyPages(40, 256 * 1024));
        var limits = new ExtractionLimits(200_000, 500, 5_000, 64, 1024 * 1024);
        var windowed = new PdfWindowReader(PdfWindowReader.Options.of(scratch, limits, null));

        for (int first = 1; first <= 40; first += 2) {
            int from = first;
            String text = windowed.withWindow(pdf, from, from + 1, (document, window) -> text(document, window));
            assertTrue(text.contains("Page " + from + " line 1:"), "window from page " + from);
        }
        var refused = assertThrows(UnreadableDocumentException.class,
                () -> windowed.withWindow(pdf, 1, 40, (document, window) -> text(document, window)));
        assertTrue(refused.getMessage().contains("one window of pages"), refused.getMessage());
    }

    @Test
    @DisplayName("an over-budget window is refused even when the operation swallows the overrun")
    void budgetCheckedAfterTheOperation() {
        Path pdf = PdfFixtures.writeTo(dir.resolve("heavy.pdf"), PdfFixtures.heavyPages(20, 256 * 1024));
        var limits = new ExtractionLimits(200_000, 500, 5_000, 64, 1024 * 1024);
        var windowed = new PdfWindowReader(PdfWindowReader.Options.of(scratch, limits, null));

        assertThrows(UnreadableDocumentException.class, () -> windowed.withWindow(pdf, 1, 20, (document, window) -> {
            try {
                return text(document, window);
            } catch (RuntimeException swallowed) {
                return "carried on";
            }
        }));
    }

    @Test
    @DisplayName("a large file whose cross-reference table needs rebuilding is refused, not repaired")
    void brokenIndexAboveTheThreshold() {
        Path pdf = PdfFixtures.brokenXrefTo(dir.resolve("broken.pdf"), 3);
        var strict = new PdfWindowReader(reader.options().withLenientParseMaxBytes(16));

        var refused = assertThrows(UnreadableDocumentException.class,
                () -> strict.withWindow(pdf, 1, 1, (document, window) -> text(document, window)));
        assertEquals(PdfWindowReader.BROKEN_INDEX, refused.getMessage());
    }

    @Test
    @DisplayName("below the threshold the same file is repaired as on the upload path")
    void brokenIndexBelowTheThreshold() {
        Path pdf = PdfFixtures.brokenXrefTo(dir.resolve("broken.pdf"), 3);

        String text = reader.withWindow(pdf, 2, 2, (document, window) -> text(document, window));

        assertTrue(text.contains("Page 2 line 1:"), text);
    }

    @Test
    @DisplayName("an intact large file is parsed strictly without complaint")
    void strictParseOfAnIntactFile() {
        Path pdf = PdfFixtures.writeTo(dir.resolve("plain.pdf"), PdfFixtures.plain(3));
        var strict = new PdfWindowReader(reader.options().withLenientParseMaxBytes(16));

        String text = strict.withWindow(pdf, 1, 1, (document, window) -> text(document, window));

        assertTrue(text.contains("Page 1 line 1:"), text);
    }

    @Test
    @DisplayName("a strictly parsed file declaring more cross-reference entries than allowed is refused first")
    void xrefEntriesBounded() {
        // A hand-written file: a classic table. And one PDFBox saved, whatever form it
        // chose. Both have a handful of objects; the cap is set below that.
        Path table = PdfFixtures.writeTo(dir.resolve("table.pdf"), PdfFixtures.deepPageTree(3));
        Path saved = PdfFixtures.writeTo(dir.resolve("saved.pdf"), PdfFixtures.plain(3));
        var capped = new PdfWindowReader(reader.options().withLenientParseMaxBytes(16).withCaps(2, 1_000));
        var roomy = new PdfWindowReader(reader.options().withLenientParseMaxBytes(16));

        for (Path pdf : new Path[]{table, saved}) {
            var refused = assertThrows(UnreadableDocumentException.class,
                    () -> capped.withWindow(pdf, 1, 1, (document, window) -> text(document, window)), pdf.toString());
            assertTrue(refused.getMessage().contains("declares more than 2 objects"), refused.getMessage());
            roomy.withWindow(pdf, 1, 1, (document, window) -> text(document, window));
        }
    }

    @Test
    @DisplayName("a classic table's declared entries are counted exactly")
    void xrefSubsectionsSummed() {
        Path table = PdfFixtures.writeTo(dir.resolve("table.pdf"), PdfFixtures.deepPageTree(3));
        long entries = PdfFixtures.deepPageTreeObjects(3) + 1;

        PdfXrefBound.requireWithin(table, entries);
        assertThrows(UnreadableDocumentException.class, () -> PdfXrefBound.requireWithin(table, entries - 1));
    }

    @Test
    @DisplayName("more pages than allowed are refused while the page tree is walked")
    void pageCap() {
        Path pdf = PdfFixtures.writeTo(dir.resolve("plain.pdf"), PdfFixtures.plain(5));
        var capped = new PdfWindowReader(reader.options().withCaps(PdfWindowReader.DEFAULT_MAX_XREF_ENTRIES, 4));

        var refused = assertThrows(UnreadableDocumentException.class,
                () -> capped.withWindow(pdf, 1, 1, (document, window) -> text(document, window)));
        assertTrue(refused.getMessage().contains("more than 4 pages"), refused.getMessage());
        reader.withWindow(pdf, 1, 1, (document, window) -> text(document, window));
    }

    @Test
    @DisplayName("a large file that is not a PDF is not reported as a damaged index")
    void largeNonPdf() throws IOException {
        Path file = Files.writeString(dir.resolve("notes.pdf"), "x".repeat(4_096), StandardCharsets.US_ASCII);
        var strict = new PdfWindowReader(reader.options().withLenientParseMaxBytes(16));

        var refused = assertThrows(UnreadableDocumentException.class,
                () -> strict.withWindow(file, 1, 1, (document, window) -> text(document, window)));
        assertEquals(PdfWindowReader.NOT_A_PDF, refused.getMessage());
    }

    @Test
    @DisplayName("the window deadline applies")
    void deadline() {
        Path pdf = PdfFixtures.writeTo(dir.resolve("plain.pdf"), PdfFixtures.plain(3));
        var hurried = new PdfWindowReader(reader.options().withWindowDeadline(Duration.ofMillis(1)));

        // The clock may not move between the window's start and the reader's first
        // check, so the operation lets the deadline pass and asks the window.
        var refused = assertThrows(UnreadableDocumentException.class,
                () -> hurried.withWindow(pdf, 1, 3, (document, window) -> {
                    sleep(Duration.ofMillis(20));
                    window.requireBeforeDeadline();
                    return text(document, window);
                }));
        assertEquals(PdfWindowReader.TOO_LONG, refused.getMessage());
    }

    @Test
    @DisplayName("the page tree is checked before the operation can iterate it")
    void pageTreeChecked() {
        Path pdf = PdfFixtures.writeTo(dir.resolve("deep.pdf"),
                PdfFixtures.deepPageTree(PdfStructureExtractor.MAX_PAGE_TREE_DEPTH + 10));

        var refused = assertThrows(UnreadableDocumentException.class,
                () -> reader.withWindow(pdf, 1, 1, (document, window) -> text(document, window)));
        assertTrue(refused.getMessage().contains("nested more than"), refused.getMessage());
    }

    @Test
    @DisplayName("an encrypted file is refused with the encryption message")
    void encrypted() throws IOException {
        Path pdf = PdfFixtures.writeTo(dir.resolve("encrypted.pdf"), encrypted(PdfFixtures.plain(1)));

        var refused = assertThrows(UnreadableDocumentException.class,
                () -> reader.withWindow(pdf, 1, 1, (document, window) -> text(document, window)));
        assertEquals(PdfWindowReader.ENCRYPTED, refused.getMessage());
    }

    @Test
    @DisplayName("a file that is not a PDF is refused, and its handle released")
    void notAPdf() throws IOException {
        Path file = Files.writeString(dir.resolve("notes.pdf"), "just some text", StandardCharsets.US_ASCII);

        var refused = assertThrows(UnreadableDocumentException.class,
                () -> reader.withWindow(file, 1, 1, (document, window) -> text(document, window)));
        assertEquals(PdfWindowReader.NOT_A_PDF, refused.getMessage());
        Files.delete(file);
    }

    @Test
    @DisplayName("a page range must be a range")
    void range() {
        Path pdf = PdfFixtures.writeTo(dir.resolve("plain.pdf"), PdfFixtures.plain(1));

        assertThrows(IllegalArgumentException.class, () -> reader.withWindow(pdf, 0, 1, (d, w) -> null));
        assertThrows(IllegalArgumentException.class, () -> reader.withWindow(pdf, 3, 2, (d, w) -> null));
    }

    @Test
    @DisplayName("options refuse what cannot work")
    void options() {
        assertThrows(IllegalArgumentException.class, () -> PdfWindowReader.Options.of(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> reader.options().withWindowDeadline(Duration.ZERO));
        assertEquals(PdfWindowReader.DEFAULT_WINDOW_DEADLINE, reader.options().windowDeadline());
        assertEquals(ExtractionLimits.defaults().maxDecodedBytes(), reader.options().maxDecodedBytes());
    }

    /**
     * Reads text as every production read does: page content charged to the budget.
     */
    private static String text(PDDocument document, PdfWindowReader.Window window) throws IOException {
        var stripper = new BoundedTextStripper(window.deadline());
        stripper.allowGlyphs(1_000_000);
        stripper.setStartPage(window.firstPage());
        stripper.setEndPage(window.lastPage());
        return stripper.getText(document);
    }

    private static byte[] encrypted(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            var policy = new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            var out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
