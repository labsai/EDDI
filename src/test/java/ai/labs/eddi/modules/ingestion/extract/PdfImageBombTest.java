/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.extract.ImageDecodeGuard.Refusal;
import ai.labs.eddi.modules.ingestion.extract.ImageNormalizer.Reason;
import ai.labs.eddi.modules.ingestion.extract.ImageNormalizer.Refused;
import ai.labs.eddi.modules.ingestion.extract.ImageNormalizer.Result;
import ai.labs.eddi.modules.ingestion.extract.ImageNormalizerTest.Decoder;
import ai.labs.eddi.testing.ChildJvm;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("PDF image bombs — refused before allocation, inside the budget, within the heap")
class PdfImageBombTest {

    private static Result normalizeFirst(byte[] pdf, Decoder decoder, ImageLimits limits, long maxDecodedBytes)
            throws IOException {
        var normalizer = new ImageNormalizer(decoder::decode);
        return ImageNormalizerTest.normalizeAll(pdf, maxDecodedBytes,
                placement -> normalizer.normalize(placement, limits, Instant.now().plusSeconds(60))).getFirst();
    }

    @Nested
    @DisplayName("declared sizes, checked before anything is allocated")
    class Declared {

        @Test
        @DisplayName("a declared 100,000 × 100,000 image is refused on its pixel count, never decoded")
        void hugeDeclaredImage() throws IOException {
            Decoder decoder = mock(Decoder.class);

            Result result = normalizeFirst(PdfFixtures.imageBombDeclared(100_000, 100_000), decoder, null,
                    ExtractionLimits.defaults().maxDecodedBytes());

            assertEquals(Reason.TOO_MANY_PIXELS, assertInstanceOf(Refused.class, result).reason());
            verify(decoder, never()).decode(any(), anyInt());
        }

        @Test
        @DisplayName("one pixel over the operator's cap is refused; at the cap it is decoded")
        void pixelCap() throws IOException {
            var limits = ImageLimits.defaults().withMaxPixels(10_000);
            byte[] over = PdfFixtures.imageBombDeclared(101, 100);
            byte[] at = PdfFixtures.singleImage("/Width 100 /Height 100 /BitsPerComponent 8 /ColorSpace /DeviceRGB",
                    PdfFixtures.deflate(new byte[30_000]), "/FlateDecode");

            assertEquals(Reason.TOO_MANY_PIXELS, assertInstanceOf(Refused.class,
                    ImageNormalizerTest.normalizeAll(over, limits).getFirst()).reason());
            assertInstanceOf(ImageNormalizer.Encoded.class, ImageNormalizerTest.normalizeAll(at, limits).getFirst());
        }

        @Test
        @DisplayName("an image within the pixel cap but past what the budget has left is refused, never decoded")
        void overBudget() throws IOException {
            Decoder decoder = mock(Decoder.class);

            // 6,000 × 6,000 RGB: 36 MP, under the cap; 108 MB of samples against a 32 MB
            // budget
            Result result = normalizeFirst(PdfFixtures.imageBombDeclared(6_000, 6_000), decoder, null, 32L << 20);

            assertEquals(Reason.OVER_BUDGET, assertInstanceOf(Refused.class, result).reason());
            verify(decoder, never()).decode(any(), anyInt());
        }

        @Test
        @DisplayName("a soft mask declaring 100,000 × 100,000 refuses its small image")
        void maskBomb() throws IOException {
            Decoder decoder = mock(Decoder.class);

            Result result = normalizeFirst(PdfFixtures.imageBombMask(100_000, 100_000), decoder, null,
                    ExtractionLimits.defaults().maxDecodedBytes());

            assertEquals(Reason.TOO_MANY_PIXELS, assertInstanceOf(Refused.class, result).reason());
            verify(decoder, never()).decode(any(), anyInt());
        }

        @Test
        @DisplayName("sizes that would overflow a long are refused, never wrapped")
        void overflow() throws IOException {
            Decoder decoder = mock(Decoder.class);
            byte[] pdf = PdfFixtures.singleImage("/Width 2147483647 /Height 2147483647 /BitsPerComponent 16"
                    + " /ColorSpace [/DeviceN [/a /b /c /d /e /f /g /h /i /j /k /l /m /n /o /p /q /r /s /t /u /v /w /x"
                    + " /y /z /aa /bb /cc /dd /ee /ff] /DeviceGray null]", PdfFixtures.deflate(new byte[10]),
                    "/FlateDecode");

            Result result = normalizeFirst(pdf, decoder, ImageLimits.defaults().withMaxPixels(Long.MAX_VALUE),
                    ExtractionLimits.defaults().maxDecodedBytes());

            assertEquals(Reason.OVER_BUDGET, assertInstanceOf(Refused.class, result).reason());
            verify(decoder, never()).decode(any(), anyInt());
        }

        @Test
        @DisplayName("missing, negative, fractional-overflow and non-numeric dimensions are refused")
        void badDimensions() throws IOException {
            for (String entries : List.of("/Height 10 /BitsPerComponent 8 /ColorSpace /DeviceGray",
                    "/Width -5 /Height 10 /BitsPerComponent 8 /ColorSpace /DeviceGray",
                    "/Width 10 /Height 99999999999 /BitsPerComponent 8 /ColorSpace /DeviceGray",
                    "/Width (ten) /Height 10 /BitsPerComponent 8 /ColorSpace /DeviceGray",
                    "/Width 10 /Height 10 /BitsPerComponent 64 /ColorSpace /DeviceGray")) {
                Decoder decoder = mock(Decoder.class);
                byte[] pdf = PdfFixtures.singleImage(entries, PdfFixtures.deflate(new byte[100]), "/FlateDecode");

                Result result = normalizeFirst(pdf, decoder, null, ExtractionLimits.defaults().maxDecodedBytes());

                assertEquals(Reason.BAD_DIMENSIONS, assertInstanceOf(Refused.class, result).reason(), entries);
                verify(decoder, never()).decode(any(), anyInt());
            }
        }
    }

    @Nested
    @DisplayName("the guard's arithmetic")
    class Guard {

        private static COSDictionary image(long width, long height, int bits, Object colorSpace) {
            var dictionary = new COSDictionary();
            dictionary.setLong(COSName.WIDTH, width);
            dictionary.setLong(COSName.HEIGHT, height);
            dictionary.setInt(COSName.BITS_PER_COMPONENT, bits);
            if (colorSpace instanceof COSName name) {
                dictionary.setItem(COSName.COLORSPACE, name);
            } else if (colorSpace instanceof COSArray array) {
                dictionary.setItem(COSName.COLORSPACE, array);
            }
            return dictionary;
        }

        @Test
        @DisplayName("components come from the colour space, bounded; unknown means four")
        void components() {
            assertEquals(1, ImageDecodeGuard.components(COSName.DEVICEGRAY, null, true));
            assertEquals(3, ImageDecodeGuard.components(COSName.DEVICERGB, null, true));
            assertEquals(4, ImageDecodeGuard.components(COSName.DEVICECMYK, null, true));
            assertEquals(4, ImageDecodeGuard.components(COSName.getPDFName("Unheard"), null, true));
            assertEquals(4, ImageDecodeGuard.components(null, null, true));
            assertEquals(4, ImageDecodeGuard.components(new COSString("RGB"), null, true));
            var indexed = new COSArray();
            indexed.add(COSName.INDEXED);
            indexed.add(COSName.DEVICERGB);
            assertEquals(1, ImageDecodeGuard.components(indexed, null, true));
            var deviceN = new COSArray();
            deviceN.add(COSName.DEVICEN);
            var names = new COSArray();
            for (int i = 0; i < 100; i++) {
                names.add(COSName.getPDFName("c" + i));
            }
            deviceN.add(names);
            assertEquals(ImageDecodeGuard.TOO_MANY, ImageDecodeGuard.components(deviceN, null, true));
        }

        @Test
        @DisplayName("a stencil mask is one bit of one component, whatever else it declares")
        void stencil() {
            var dictionary = image(1_000, 1_000, 8, COSName.DEVICECMYK);
            dictionary.setBoolean(COSName.IMAGE_MASK, true);

            var declared = ImageDecodeGuard.declared(dictionary, false, null).orElseThrow();

            assertEquals(1, declared.components());
            assertEquals(1, declared.bitsPerComponent());
            assertEquals(125_000, declared.decodedBytes());
        }

        @Test
        @DisplayName("decoded samples plus rasters must fit what the budget has left")
        void cost() {
            // 1,000 × 1,000 RGB at subsampling 1: 3 MB of samples + 7 MB of rasters
            var dictionary = image(1_000, 1_000, 8, COSName.DEVICERGB);

            assertEquals(Optional.empty(), ImageDecodeGuard.check(dictionary, false, List.of(), null, 40_000_000, 10_000_000, 1));
            assertEquals(Optional.of(Refusal.OVER_BUDGET),
                    ImageDecodeGuard.check(dictionary, false, List.of(), null, 40_000_000, 9_999_999, 1));
            // Subsampled by 2 the rasters are a quarter: 3 MB + 1.75 MB
            assertEquals(Optional.empty(), ImageDecodeGuard.check(dictionary, false, List.of(), null, 40_000_000, 4_750_000, 2));
        }

        @Test
        @DisplayName("a named colour space is looked up once in the resources")
        void namedColourSpace() {
            var colourSpaces = new COSDictionary();
            colourSpaces.setItem(COSName.getPDFName("CS0"), COSName.DEVICEGRAY);
            colourSpaces.setItem(COSName.getPDFName("Loop"), COSName.getPDFName("Loop"));
            var resources = new COSDictionary();
            resources.setItem(COSName.COLORSPACE, colourSpaces);
            var pdResources = new PDResources(resources);

            assertEquals(1, ImageDecodeGuard.components(COSName.getPDFName("CS0"), pdResources, true));
            assertEquals(4, ImageDecodeGuard.components(COSName.getPDFName("Loop"), pdResources, true));
            assertEquals(4, ImageDecodeGuard.components(COSInteger.ONE, pdResources, true));
        }
    }

    @Nested
    @DisplayName("decompression inside an image stream")
    class Flate {

        @Test
        @DisplayName("a Flate bomb inside an image passes the raw pre-scan and is refused by the decode budget")
        void flateBombInImage() throws IOException {
            // 1,000 × 1,000 gray declares 1 MB; the stream inflates to 64 MB, budget 16 MB
            byte[] pdf = PdfFixtures.imageBombFlate(1_000, 1_000, 64L << 20);

            // The pre-scan skips image streams: this is the gap only the budget closes
            PdfStreamBudget.requireWithin(pdf, 8L << 20, Instant.now().plusSeconds(60));

            try (PDDocument document = Loader.loadPDF(pdf); var budget = PdfDecodeBudget.open(16L << 20)) {
                var placement = PdfImageCollector.collect(document, 1, 1, null, Instant.now().plusSeconds(60)).images()
                        .getFirst();

                assertThrows(PdfDecodeBudget.BudgetExceeded.class,
                        () -> new ImageNormalizer().normalize(placement, null, Instant.now().plusSeconds(60)));
                assertTrue(budget.exceeded());
            }
        }

        @Test
        @DisplayName("the same image inflating to what it declares decodes within the budget")
        void honestImage() throws IOException {
            byte[] pdf = PdfFixtures.imageBombFlate(1_000, 1_000, 1_000_000);

            try (PDDocument document = Loader.loadPDF(pdf); var budget = PdfDecodeBudget.open(16L << 20)) {
                var placement = PdfImageCollector.collect(document, 1, 1, null, Instant.now().plusSeconds(60)).images()
                        .getFirst();

                assertInstanceOf(ImageNormalizer.Encoded.class,
                        new ImageNormalizer().normalize(placement, null, Instant.now().plusSeconds(60)));
                assertFalse(budget.exceeded());
            }
        }
    }

    @Nested
    @DisplayName("the size PDFBox allocates from, wherever it reads it")
    class WhatPdfBoxReads {

        private static final long BUDGET = ExtractionLimits.defaults().maxDecodedBytes();

        @Test
        @DisplayName("an inline image is sized from /W and /H, as PDFBox reads it, not from /Width and /Height")
        void inlineAbbreviationsFirst() throws IOException {
            Decoder decoder = mock(Decoder.class);
            byte[] pdf = PdfFixtures.inlineRaw("/Width 1 /Height 1 /W 20000 /H 20000 /CS /G /BPC 8", new byte[16]);

            Result result = normalizeFirst(pdf, decoder, null, BUDGET);

            assertEquals(Reason.TOO_MANY_PIXELS, assertInstanceOf(Refused.class, result).reason());
            verify(decoder, never()).decode(any(), anyInt());
        }

        @Test
        @DisplayName("an inline image's /F is its filter, read before /Filter: a JPEG 2000 behind it is refused")
        void inlineFilterAbbreviationFirst() throws IOException {
            Decoder decoder = mock(Decoder.class);
            byte[] pdf = PdfFixtures.inlineRaw("/W 4 /H 4 /CS /G /BPC 8 /Filter /FlateDecode /F /JPXDecode",
                    new byte[16]);

            Result result = normalizeFirst(pdf, decoder, null, BUDGET);

            assertEquals(Reason.UNSUPPORTED_FORMAT, assertInstanceOf(Refused.class, result).reason());
            verify(decoder, never()).decode(any(), anyInt());
        }

        @Test
        @DisplayName("a mask is applied at the larger width and the larger height: 1,568 × 1 masked by 1 × 1,000,000 is refused")
        void maskScaling() throws IOException {
            Decoder decoder = mock(Decoder.class);

            Result result = normalizeFirst(PdfFixtures.imageBombMask(1568, 1, 1, 1_000_000), decoder, null, BUDGET);

            assertEquals(Reason.TOO_MANY_PIXELS, assertInstanceOf(Refused.class, result).reason());
            verify(decoder, never()).decode(any(), anyInt());
        }

        @Test
        @DisplayName("a page of operands and no operator is charged to the budget before it is parsed")
        void pageContentIsCharged() throws IOException {
            byte[] pdf = PdfFixtures.operandFlood(64L << 20);

            try (PDDocument document = Loader.loadPDF(pdf); var budget = PdfDecodeBudget.open(16L << 20)) {
                // assertTimeout, not ...Preemptively: the budget is armed on this thread only
                assertTimeout(Duration.ofSeconds(60), () -> assertThrows(PdfDecodeBudget.BudgetExceeded.class,
                        () -> PdfImageCollector.collect(document, 1, 1, null, Instant.now().plusSeconds(60))));
                assertTrue(budget.exceeded());
            }
        }

        @Test
        @DisplayName("an unfiltered form is charged to the budget on every draw, since every draw parses it again")
        void unfilteredFormIsChargedPerDraw() throws IOException {
            // 1,000 draws of a 100 KB form: 100 MB parsed against a 16 MB budget
            byte[] pdf = PdfFixtures.formRepeated(1_000, 100_000);

            try (PDDocument document = Loader.loadPDF(pdf); var budget = PdfDecodeBudget.open(16L << 20)) {
                assertThrows(PdfDecodeBudget.BudgetExceeded.class,
                        () -> PdfImageCollector.collect(document, 1, 1, null, Instant.now().plusSeconds(60)));
                assertTrue(budget.exceeded());
            }
        }

        @Test
        @DisplayName("a JPEG whose frame header is larger than its dictionary is refused, XObject or inline")
        void jpegFrameDisagrees() throws IOException {
            byte[] lying = PdfFixtures.patchFrame(PdfFixtures.photoJpeg(), 0xC0, 8, 20_000, 20_000);
            for (byte[] pdf : List.of(
                    PdfFixtures.singleImage("/Width 64 /Height 48 /BitsPerComponent 8 /ColorSpace /DeviceRGB", lying,
                            "/DCTDecode"),
                    PdfFixtures.inlineRaw("/W 64 /H 48 /CS /RGB /BPC 8 /F /DCT", lying))) {
                Decoder decoder = mock(Decoder.class);

                Result result = normalizeFirst(pdf, decoder, null, BUDGET);

                assertEquals(Reason.BAD_DIMENSIONS, assertInstanceOf(Refused.class, result).reason());
                verify(decoder, never()).decode(any(), anyInt());
            }
        }

        @Test
        @DisplayName("an inline JPEG is decoded at full size, so its frame is checked against the budget unsubsampled")
        void inlineJpegAtFullSize() throws IOException {
            Decoder decoder = mock(Decoder.class);
            byte[] large = PdfFixtures.patchFrame(PdfFixtures.photoJpeg(), 0xC0, 8, 4_000, 4_000);
            byte[] pdf = PdfFixtures.inlineRaw("/W 4000 /H 4000 /CS /RGB /BPC 8 /F /DCT", large);

            // 16 MP RGB: subsampled by 2 it would fit (48 + 28 MB), at full size it does
            // not (48 + 112 MB)
            Result result = normalizeFirst(pdf, decoder, null, BUDGET);

            assertEquals(Reason.OVER_BUDGET, assertInstanceOf(Refused.class, result).reason());
            verify(decoder, never()).decode(any(), anyInt());
        }

        @Test
        @DisplayName("a DeviceN of more than 32 colourants is refused, not costed as 32")
        void tooManyColourants() throws IOException {
            Decoder decoder = mock(Decoder.class);
            String names = String.join(" ", IntStream.range(0, 100).mapToObj(i -> "/c" + i).toList());
            byte[] pdf = PdfFixtures.singleImage("/Width 10 /Height 10 /BitsPerComponent 8 /ColorSpace [/DeviceN ["
                    + names + "] /DeviceGray null]", PdfFixtures.deflate(new byte[10_000]), "/FlateDecode");

            Result result = normalizeFirst(pdf, decoder, null, BUDGET);

            assertEquals(Reason.TOO_MANY_COMPONENTS, assertInstanceOf(Refused.class, result).reason());
            verify(decoder, never()).decode(any(), anyInt());
        }

        @Test
        @DisplayName("DeviceGray is counted as the resources' /DefaultGray, which PDFBox uses in its place")
        void defaultColourSpace() {
            var deviceN = new COSArray();
            deviceN.add(COSName.DEVICEN);
            var names = new COSArray();
            for (int i = 0; i < 100; i++) {
                names.add(COSName.getPDFName("c" + i));
            }
            deviceN.add(names);
            var colourSpaces = new COSDictionary();
            colourSpaces.setItem(COSName.DEFAULT_GRAY, deviceN);
            colourSpaces.setItem(COSName.DEFAULT_RGB, COSName.DEVICEGRAY);
            var resources = new COSDictionary();
            resources.setItem(COSName.COLORSPACE, colourSpaces);
            var pdResources = new PDResources(resources);

            assertEquals(ImageDecodeGuard.TOO_MANY, ImageDecodeGuard.components(COSName.DEVICEGRAY, pdResources, true));
            assertEquals(1, ImageDecodeGuard.components(COSName.DEVICERGB, pdResources, true));
            assertEquals(4, ImageDecodeGuard.components(COSName.DEVICECMYK, pdResources, true));
        }

        @Test
        @DisplayName("an XObject reads /Width only: an abbreviated /W does not shrink what is checked")
        void xObjectFullKeysOnly() throws IOException {
            Decoder decoder = mock(Decoder.class);
            byte[] pdf = PdfFixtures.singleImage("/W 1 /H 1 /Width 20000 /Height 20000 /BitsPerComponent 8"
                    + " /ColorSpace /DeviceGray", PdfFixtures.deflate(new byte[100]), "/FlateDecode");

            Result result = normalizeFirst(pdf, decoder, null, BUDGET);

            assertEquals(Reason.TOO_MANY_PIXELS, assertInstanceOf(Refused.class, result).reason());
            verify(decoder, never()).decode(any(), anyInt());
        }

        @Test
        @DisplayName("a CCITT image is sized from /DecodeParms /Columns, as PDFBox sizes its buffer")
        void ccittColumns() throws IOException {
            Decoder decoder = mock(Decoder.class);
            byte[] pdf = PdfFixtures.singleImage("/Width 10 /Height 10000 /BitsPerComponent 1 /ColorSpace /DeviceGray"
                    + " /DecodeParms <</Columns 200000 /K -1>>", new byte[100], "/CCITTFaxDecode");

            Result result = normalizeFirst(pdf, decoder, null, BUDGET);

            assertEquals(Reason.TOO_MANY_PIXELS, assertInstanceOf(Refused.class, result).reason());
            verify(decoder, never()).decode(any(), anyInt());
        }
    }

    @Nested
    @DisplayName("heap, in a JVM of its own")
    class Heap {

        @Test
        @DisplayName("10,000 images are listed and decoded in 256 MB: nothing decoded is kept")
        void manyImages() throws IOException, InterruptedException {
            ChildJvm.Result result = ChildJvm.run(PdfImageHeapProbe.class, List.of("-Xmx256m", "-Djava.awt.headless=true"),
                    List.of(PdfImageHeapProbe.MANY_IMAGES), Duration.ofMinutes(3));

            assertFalse(result.timedOut(), result.output());
            assertEquals(0, result.exitCode(), result.output());
            assertEquals("OK 10000 encoded", result.lastLine(), result.output());
        }

        @Test
        @DisplayName("two million unclosed q operators keep at most 64 saved states")
        void nestedSaves() throws IOException, InterruptedException {
            ChildJvm.Result result = ChildJvm.run(PdfImageHeapProbe.class, List.of("-Xmx256m", "-Djava.awt.headless=true"),
                    List.of(PdfImageHeapProbe.NESTED_SAVES), Duration.ofMinutes(3));

            assertFalse(result.timedOut(), result.output());
            assertEquals(0, result.exitCode(), result.output());
            assertEquals("OK 1 placed", result.lastLine(), result.output());
        }
    }
}
