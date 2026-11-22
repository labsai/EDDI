/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.extract.PdfImageCollector.Collection;
import ai.labs.eddi.modules.ingestion.extract.PdfImageCollector.Placement;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PdfImageCollector — where a page draws its images, without decoding them")
class PdfImageCollectorTest {

    private static final float EPSILON = 0.01f;

    private static Collection collect(byte[] pdf, int first, int last, ImageLimits limits) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf);
                var budget = PdfDecodeBudget.open(ExtractionLimits.defaults().maxDecodedBytes())) {
            return PdfImageCollector.collect(document, first, last, limits, Instant.now().plusSeconds(60));
        }
    }

    private static void assertBox(Placement placement, float x, float y, float width, float height) {
        assertEquals(x, placement.x(), EPSILON, "x of " + placement.imageId());
        assertEquals(y, placement.y(), EPSILON, "y of " + placement.imageId());
        assertEquals(width, placement.width(), EPSILON, "width of " + placement.imageId());
        assertEquals(height, placement.height(), EPSILON, "height of " + placement.imageId());
    }

    @Nested
    @DisplayName("positions and ids")
    class Positions {

        @Test
        @DisplayName("every draw of a page, in drawing order, positioned from the top-left, ids i-<page>-<n>")
        void pageOne() throws IOException {
            List<Placement> images = collect(PdfFixtures.withImages(3), 1, 1, null).images();

            assertEquals(List.of("i-1-1", "i-1-2", "i-1-3", "i-1-4"), images.stream().map(Placement::imageId).toList());
            assertBox(images.get(0), 0, 0, 16, 16); // the logo
            assertBox(images.get(1), 100, 150, 200, 150); // the photo
            assertBox(images.get(2), 50, 600, 100, 100); // the Flate image
            assertBox(images.get(3), 300, 700, 40, 40); // the inline image
            assertTrue(images.stream().allMatch(image -> image.page() == 1));
        }

        @Test
        @DisplayName("XObjects keep their stream, inline images their raw bytes; nothing is decoded")
        void sources() throws IOException {
            List<Placement> images = collect(PdfFixtures.withImages(2), 1, 1, null).images();

            Placement photo = images.get(1);
            assertFalse(photo.inline());
            assertNotNull(photo.stream());
            assertNull(photo.inlineData());
            assertEquals(PdfFixtures.PHOTO_WIDTH, photo.declaredWidth());
            assertEquals(PdfFixtures.PHOTO_HEIGHT, photo.declaredHeight());

            Placement inline = images.get(3);
            assertTrue(inline.inline());
            assertNull(inline.stream());
            // PDFBox keeps the whitespace before EI: 16 samples, then at most one byte
            assertTrue(inline.inlineData().length == 16 || inline.inlineData().length == 17);
            assertEquals(4, inline.declaredWidth());
        }

        @Test
        @DisplayName("an image inside a form takes the form's matrix; the same XObject drawn twice is two placements")
        void formAndSharedLogo() throws IOException {
            Collection collection = collect(PdfFixtures.withImages(3), 1, 3, null);
            List<Placement> pageTwo = collection.images().stream().filter(image -> image.page() == 2).toList();

            assertEquals(List.of("i-2-1", "i-2-2", "i-2-3"), pageTwo.stream().map(Placement::imageId).toList());
            assertBox(pageTwo.get(1), 200, 392, 100, 100); // the photo, through the form's matrix
            assertBox(pageTwo.get(2), 10, 200, 80, 60); // the CMYK JPEG
            List<Placement> logos = collection.images().stream().filter(image -> image.imageId().endsWith("-1"))
                    .toList();
            assertEquals(3, logos.size());
            assertSame(logos.get(0).stream(), logos.get(2).stream());
            assertFalse(collection.truncated());
        }

        @Test
        @DisplayName("only the requested pages are read")
        void range() throws IOException {
            List<Placement> images = collect(PdfFixtures.withImages(3), 3, 3, null).images();

            assertEquals(List.of("i-3-1"), images.stream().map(Placement::imageId).toList());
        }

        @Test
        @DisplayName("a range outside the document is refused")
        void outside() {
            assertThrows(IllegalArgumentException.class, () -> collect(PdfFixtures.withImages(2), 2, 3, null));
            assertThrows(IllegalArgumentException.class, () -> collect(PdfFixtures.withImages(2), 0, 1, null));
            assertThrows(IllegalArgumentException.class, () -> collect(PdfFixtures.withImages(2), 2, 1, null));
        }
    }

    @Nested
    @DisplayName("bounds on the page program")
    class Bounds {

        @Test
        @DisplayName("forms drawing each other, or themselves, are followed once: the image is listed once")
        void formCycle() throws IOException {
            Collection collection = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> collect(PdfFixtures.formCycle(), 1, 1, null));

            assertEquals(1, collection.images().size());
            assertFalse(collection.truncated());
        }

        @Test
        @DisplayName("forms are followed eight deep, not nine")
        void formDepth() throws IOException {
            assertEquals(1, collect(PdfFixtures.formChain(PdfImageCollector.MAX_FORM_DEPTH), 1, 1, null).images()
                    .size());
            assertEquals(0, collect(PdfFixtures.formChain(PdfImageCollector.MAX_FORM_DEPTH + 1), 1, 1, null).images()
                    .size());
        }

        @Test
        @DisplayName("a fan-out of forms asking for 10^8 draws stops at the operator cap and says it was cut")
        void fanOut() throws IOException {
            var limits = new ImageLimits(0, 0, 0, Integer.MAX_VALUE, 100_000);

            Collection collection = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> collect(PdfFixtures.formFanOut(10, 8), 1, 1, limits));

            assertTrue(collection.truncated());
            assertTrue(collection.images().size() < 100_000, "draws: " + collection.images().size());
        }

        @Test
        @DisplayName("past the image cap the collection stops and says it was cut")
        void imageCap() throws IOException {
            var limits = new ImageLimits(0, 0, 0, 5, 0);

            Collection collection = collect(PdfFixtures.imageBombMany(12, 6, 2), 1, 2, limits);

            assertEquals(5, collection.images().size());
            assertTrue(collection.truncated());
        }

        @Test
        @DisplayName("the deadline refuses the document, as every PDF read does")
        void deadline() throws IOException {
            try (PDDocument document = Loader.loadPDF(PdfFixtures.formFanOut(10, 8));
                    var budget = PdfDecodeBudget.open(ExtractionLimits.defaults().maxDecodedBytes())) {
                var error = assertThrows(UnreadableDocumentException.class, () -> PdfImageCollector.collect(document,
                        1, 1, null, Instant.now().minusSeconds(1)));
                assertEquals(BoundedTextStripper.TOO_LONG, error.getMessage());
            }
        }

        @Test
        @DisplayName("q nested past the cap is balanced by its Q: later draws keep their position")
        void deepSaves() throws IOException {
            String content = "q 2 0 0 2 0 0 cm ".repeat(100) + "/Im1 Do " + "Q ".repeat(100)
                    + "q 30 0 0 20 5 7 cm /Im1 Do Q";
            byte[] pdf = PdfFixtures.singleImage("/Width 2 /Height 2 /BitsPerComponent 8 /ColorSpace /DeviceRGB",
                    new byte[12], null);
            byte[] edited = replaceContent(pdf, content);

            List<Placement> images = collect(edited, 1, 1, null).images();

            assertEquals(2, images.size());
            assertBox(images.get(1), 5, 792 - 27, 30, 20);
        }

        @Test
        @DisplayName("past the q cap on the page, a form's own q … Q still restores its own state")
        void formKeepsItsOwnSaves() throws IOException {
            List<Placement> images = collect(PdfFixtures.formAfterSaves(PdfImageCollector.MAX_SAVE_DEPTH + 6), 1, 1,
                    null).images();

            assertEquals(1, images.size());
            assertBox(images.getFirst(), 0, 782, 10, 10);
        }

        @Test
        @DisplayName("a form PDFBox cannot read is skipped; the rest of the page is still listed")
        void malformedForm() throws IOException {
            Collection collection = collect(PdfFixtures.malformedForm(), 1, 1, null);

            assertEquals(1, collection.images().size());
            assertFalse(collection.truncated());
        }

        @Test
        @DisplayName("the deadline is checked while the page runs, not only before it")
        void deadlineDuringTheWork() throws IOException {
            var limits = new ImageLimits(0, 0, 0, Integer.MAX_VALUE, Long.MAX_VALUE);
            try (PDDocument document = Loader.loadPDF(PdfFixtures.formFanOut(10, 8))) {
                Instant deadline = Instant.now().plusMillis(500);

                // Preemptive, so a missing check cannot hang the build; the budget is armed
                // inside, on the thread that runs the collection
                assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertThrows(UnreadableDocumentException.class,
                        () -> collectUnderBudget(document, limits, deadline, ExtractionLimits.defaults().maxDecodedBytes())));
            }
        }

        @Test
        @DisplayName("the deadline is checked on every form drawn, however few operators the forms hold")
        void deadlineOnEveryForm() throws IOException {
            // 1,000 draws of a 400 KB form of comments: 400 MB to parse in fewer operators
            // than
            // one deadline-check interval (1,024)
            try (PDDocument document = Loader.loadPDF(PdfFixtures.formRepeated(1_000, 400_000))) {
                Instant deadline = Instant.now().plusMillis(200);

                assertTimeoutPreemptively(Duration.ofSeconds(60), () -> assertThrows(UnreadableDocumentException.class,
                        () -> collectUnderBudget(document, null, deadline, 1L << 30)));
            }
        }

        private static Collection collectUnderBudget(PDDocument document, ImageLimits limits, Instant deadline,
                                                     long maxDecodedBytes)
                throws IOException {
            try (var budget = PdfDecodeBudget.open(maxDecodedBytes)) {
                return PdfImageCollector.collect(document, 1, 1, limits, deadline);
            }
        }

        @Test
        @DisplayName("one collection keeps 16 MiB of inline images in all; past it a draw is listed without bytes")
        void inlineTotal() throws IOException {
            List<Placement> images = collect(PdfFixtures.inlineImages(17, 1000, 1000), 1, 1, null).images();

            assertEquals(17, images.size());
            assertTrue(images.subList(0, 16).stream().allMatch(image -> image.inlineData() != null));
            assertNull(images.get(16).inlineData());
        }

        /** Swaps the single-image fixture's content stream for another. */
        private static byte[] replaceContent(byte[] pdf, String content) throws IOException {
            try (PDDocument document = Loader.loadPDF(pdf)) {
                var page = document.getPage(0);
                var stream = document.getDocument().createCOSStream();
                try (var out = stream.createRawOutputStream()) {
                    out.write(content.getBytes(StandardCharsets.US_ASCII));
                }
                page.getCOSObject().setItem(COSName.CONTENTS, stream);
                var out = new ByteArrayOutputStream();
                document.save(out);
                return out.toByteArray();
            }
        }
    }
}
