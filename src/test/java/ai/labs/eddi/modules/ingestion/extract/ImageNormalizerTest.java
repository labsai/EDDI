/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.extract.ImageNormalizer.Encoded;
import ai.labs.eddi.modules.ingestion.extract.ImageNormalizer.Reason;
import ai.labs.eddi.modules.ingestion.extract.ImageNormalizer.Refused;
import ai.labs.eddi.modules.ingestion.extract.ImageNormalizer.Result;
import ai.labs.eddi.modules.ingestion.extract.PdfImageCollector.Placement;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("ImageNormalizer — pass-through, re-encode, downscale")
class ImageNormalizerTest {

    private static final ImageNormalizer NORMALIZER = new ImageNormalizer();

    /**
     * Opens the PDF with a budget armed, collects every image of its pages and
     * normalizes each with {@code normalize}.
     */
    static List<Result> normalizeAll(byte[] pdf, long maxDecodedBytes, Function<Placement, Result> normalize)
            throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf); var budget = PdfDecodeBudget.open(maxDecodedBytes)) {
            return PdfImageCollector
                    .collect(document, 1, document.getNumberOfPages(), null, Instant.now().plusSeconds(60)).images()
                    .stream().map(normalize).toList();
        }
    }

    static List<Result> normalizeAll(byte[] pdf, ImageLimits limits) throws IOException {
        return normalizeAll(pdf, ExtractionLimits.defaults().maxDecodedBytes(),
                placement -> NORMALIZER.normalize(placement, limits, Instant.now().plusSeconds(60)));
    }

    private static BufferedImage read(Encoded encoded) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(encoded.bytes()));
    }

    @Nested
    @DisplayName("what comes out")
    class Output {

        @Test
        @DisplayName("an RGB JPEG passes through byte for byte, undecoded")
        void passThrough() throws IOException {
            Decoder decoder = mock(Decoder.class);
            var normalizer = new ImageNormalizer(decoder::decode);

            List<Result> results = normalizeAll(PdfFixtures.withImages(2), ExtractionLimits.defaults().maxDecodedBytes(),
                    placement -> normalizer.normalize(placement, null, Instant.now().plusSeconds(60)));

            var photo = assertInstanceOf(Encoded.class, results.get(1));
            assertTrue(photo.passThrough());
            assertEquals("image/jpeg", photo.mimeType());
            assertArrayEquals(PdfFixtures.photoJpeg(), photo.bytes());
            assertEquals(PdfFixtures.PHOTO_WIDTH, photo.width());
            assertEquals(PdfFixtures.PHOTO_HEIGHT, photo.height());
        }

        @Test
        @DisplayName("a Flate image and an inline image are decoded and re-encoded as PNG")
        void flateAndInline() throws IOException {
            List<Result> results = normalizeAll(PdfFixtures.withImages(2), null);

            var flate = assertInstanceOf(Encoded.class, results.get(2));
            assertEquals("image/png", flate.mimeType());
            assertFalse(flate.passThrough());
            BufferedImage decoded = read(flate);
            assertEquals(32, decoded.getWidth());
            assertEquals(32, decoded.getHeight());

            var inline = assertInstanceOf(Encoded.class, results.get(3));
            assertEquals("image/png", inline.mimeType());
            assertEquals(4, read(inline).getWidth());
            assertEquals(0x80, read(inline).getRGB(1, 1) & 0xFF);
        }

        @Test
        @DisplayName("a CMYK JPEG is decoded, not passed through, and re-encoded as an RGB JPEG")
        void cmyk() throws IOException {
            List<Result> results = normalizeAll(PdfFixtures.withImages(2), null);

            var cmyk = assertInstanceOf(Encoded.class, results.get(6));
            assertFalse(cmyk.passThrough());
            assertEquals("image/jpeg", cmyk.mimeType());
            assertEquals(3, ImageNormalizer.JpegHeader.read(cmyk.bytes()).components());
            assertEquals(40, read(cmyk).getWidth());
        }

        @Test
        @DisplayName("a JPEG declared RGB that is really CMYK is decoded, not passed through")
        void mislabelledJpeg() throws IOException {
            byte[] pdf = PdfFixtures.singleImage("/Width 40 /Height 30 /BitsPerComponent 8 /ColorSpace /DeviceRGB",
                    PdfFixtures.cmykJpeg(), "/DCTDecode");

            Result result = normalizeAll(pdf, null).getFirst();

            if (result instanceof Encoded encoded) {
                assertFalse(encoded.passThrough());
            } else {
                assertEquals(Reason.UNDECODABLE, assertInstanceOf(Refused.class, result).reason());
            }
        }

        @Test
        @DisplayName("a large image is decoded subsampled and scaled to the longest edge")
        void downscale() throws IOException {
            byte[] pdf = PdfFixtures.singleImage("/Width 3000 /Height 1000 /BitsPerComponent 8 /ColorSpace /DeviceGray",
                    PdfFixtures.deflate(new byte[3000 * 1000]), "/FlateDecode");

            var encoded = assertInstanceOf(Encoded.class,
                    normalizeAll(pdf, ImageLimits.defaults().withMaxEdgePx(400)).getFirst());

            assertEquals(400, encoded.width());
            assertEquals(133, encoded.height());
            assertEquals(400, read(encoded).getWidth());
        }

        @Test
        @DisplayName("a JPEG larger than the longest edge is re-encoded, not passed through")
        void largeJpegIsNotPassedThrough() throws IOException {
            var encoded = assertInstanceOf(Encoded.class,
                    normalizeAll(PdfFixtures.withImages(2), ImageLimits.defaults().withMaxEdgePx(32)).get(1));

            assertFalse(encoded.passThrough());
            assertEquals(32, encoded.width());
            assertEquals(24, encoded.height());
        }

        @Test
        @DisplayName("an image that will not fit the byte cap even shrunk is refused, not sent")
        void tooLarge() throws IOException {
            var limits = new ImageLimits(0, 0, 40, 0, 0);

            Result result = normalizeAll(PdfFixtures.withImages(2), limits).get(2);

            assertEquals(Reason.TOO_LARGE, assertInstanceOf(Refused.class, result).reason());
        }
    }

    @Nested
    @DisplayName("what is refused")
    class Refusals {

        @Test
        @DisplayName("JBIG2 and JPEG 2000 are refused before PDFBox is asked anything")
        void unsupportedCodecs() throws IOException {
            for (String filter : List.of("/JBIG2Decode", "/JPXDecode", "[/FlateDecode /JPXDecode]")) {
                Decoder decoder = mock(Decoder.class);
                var normalizer = new ImageNormalizer(decoder::decode);
                byte[] pdf = PdfFixtures.singleImage("/Width 8 /Height 8 /BitsPerComponent 8 /ColorSpace /DeviceGray",
                        new byte[64], filter);

                Result result = normalizeAll(pdf, ExtractionLimits.defaults().maxDecodedBytes(),
                        placement -> normalizer.normalize(placement, null, Instant.now().plusSeconds(60))).getFirst();

                assertEquals(Reason.UNSUPPORTED_FORMAT, assertInstanceOf(Refused.class, result).reason(), filter);
                verify(decoder, never()).decode(any(), anyInt());
            }
        }

        @Test
        @DisplayName("data PDFBox cannot decode costs that image, not the document")
        void undecodable() throws IOException {
            byte[] pdf = PdfFixtures.singleImage("/Width 8 /Height 8 /BitsPerComponent 8 /ColorSpace /DeviceGray",
                    "not a JPEG at all".getBytes(StandardCharsets.US_ASCII), "/DCTDecode");

            Result result = normalizeAll(pdf, null).getFirst();

            assertEquals(Reason.UNDECODABLE, assertInstanceOf(Refused.class, result).reason());
        }

        @Test
        @DisplayName("an inline image over the inline cap is listed without its bytes and refused")
        void inlineTooLarge() throws IOException {
            byte[] pdf = PdfFixtures.inlineImage(PdfImageCollector.MAX_INLINE_BYTES + 1, 1);

            Result result = normalizeAll(pdf, null).getFirst();

            assertEquals(Reason.INLINE_TOO_LARGE, assertInstanceOf(Refused.class, result).reason());
        }

        @Test
        @DisplayName("an inline image well within the inline cap keeps its bytes and is decoded")
        void inlineAtTheCap() throws IOException {
            byte[] pdf = PdfFixtures.inlineImage(PdfImageCollector.MAX_INLINE_BYTES / 1024, 1023);

            assertInstanceOf(Encoded.class, normalizeAll(pdf, null).getFirst());
        }

        @Test
        @DisplayName("without an armed decode budget nothing is decoded")
        void noBudget() throws IOException {
            try (PDDocument document = Loader.loadPDF(PdfFixtures.withImages(2))) {
                Placement photo;
                try (var budget = PdfDecodeBudget.open(1 << 20)) {
                    photo = PdfImageCollector.collect(document, 1, 1, null, Instant.now().plusSeconds(60)).images()
                            .get(2);
                }
                assertThrows(IllegalStateException.class,
                        () -> NORMALIZER.normalize(photo, null, Instant.now().plusSeconds(60)));
            }
        }

        @Test
        @DisplayName("past the deadline the document is refused")
        void deadline() throws IOException {
            try (PDDocument document = Loader.loadPDF(PdfFixtures.withImages(2));
                    var budget = PdfDecodeBudget.open(1 << 20)) {
                Placement flate = PdfImageCollector.collect(document, 1, 1, null, Instant.now().plusSeconds(60))
                        .images().get(2);
                assertThrows(UnreadableDocumentException.class,
                        () -> NORMALIZER.normalize(flate, null, Instant.now().minusSeconds(1)));
            }
        }
    }

    @Nested
    @DisplayName("the JPEG header")
    class Header {

        @Test
        @DisplayName("reads size and components from the frame header")
        void reads() {
            var header = ImageNormalizer.JpegHeader.read(PdfFixtures.photoJpeg());
            assertEquals(new ImageNormalizer.JpegHeader(PdfFixtures.PHOTO_WIDTH, PdfFixtures.PHOTO_HEIGHT, 3), header);
            assertEquals(4, ImageNormalizer.JpegHeader.read(PdfFixtures.cmykJpeg()).components());
        }

        @Test
        @DisplayName("anything that is not a JPEG with a frame reads as nothing, never throws")
        void garbage() {
            assertEquals(null, ImageNormalizer.JpegHeader.read(new byte[0]));
            assertEquals(null, ImageNormalizer.JpegHeader.read(new byte[]{(byte) 0xFF, (byte) 0xD8}));
            assertEquals(null, ImageNormalizer.JpegHeader.read(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
                    (byte) 0xE0, 0, 0}));
            assertEquals(null, ImageNormalizer.JpegHeader.read(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
                    (byte) 0xC0, 0, 8, 8}));
            byte[] truncated = new byte[2000];
            truncated[0] = (byte) 0xFF;
            truncated[1] = (byte) 0xD8;
            truncated[2] = (byte) 0xFF;
            truncated[3] = (byte) 0xE1;
            truncated[4] = (byte) 0xFF;
            truncated[5] = (byte) 0xFF;
            assertEquals(null, ImageNormalizer.JpegHeader.read(truncated));
        }
    }

    /** A mockable shape of the decoder seam. */
    interface Decoder {
        BufferedImage decode(Placement placement, int subsampling) throws IOException;
    }

}
