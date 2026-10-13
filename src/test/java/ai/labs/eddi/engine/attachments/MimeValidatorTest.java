/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MimeValidator Tests")
class MimeValidatorTest {

    @Nested
    @DisplayName("Magic Byte Detection")
    class DetectionTests {

        @Test
        @DisplayName("Should detect JPEG")
        void testDetectJpeg() {
            byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
            assertEquals("image/jpeg", MimeValidator.detectMime(jpeg));
        }

        @Test
        @DisplayName("Should detect PNG")
        void testDetectPng() {
            byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            assertEquals("image/png", MimeValidator.detectMime(png));
        }

        @Test
        @DisplayName("Should detect GIF")
        void testDetectGif() {
            byte[] gif = new byte[]{0x47, 0x49, 0x46, 0x38, 0x39, 0x61};
            assertEquals("image/gif", MimeValidator.detectMime(gif));
        }

        @Test
        @DisplayName("a PDF header after leading bytes (BOM, print-job prefix) is still a PDF, within the first 1024 bytes")
        void testDetectPdfWithLeadingBytes() {
            byte[] prefixed = "﻿%!PS-Adobe job prefix\n%PDF-1.7\n".getBytes(StandardCharsets.UTF_8);
            assertEquals("application/pdf", MimeValidator.detectMime(prefixed));
            assertEquals("application/pdf", MimeValidator.detectMime("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII)));

            byte[] tooLate = new byte[1100];
            System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, tooLate, 1030, 5);
            assertEquals("application/octet-stream", MimeValidator.detectMime(tooLate));
        }

        @Test
        @DisplayName("Should detect BMP")
        void testDetectBmp() {
            assertEquals("image/bmp", MimeValidator.detectMime(bmpHeader()));
        }

        @Test
        @DisplayName("Should detect WebP")
        void testDetectWebP() {
            byte[] webp = new byte[]{0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50};
            assertEquals("image/webp", MimeValidator.detectMime(webp));
        }

        @Test
        @DisplayName("Should detect TIFF little-endian")
        void testDetectTiffLE() {
            byte[] tiff = new byte[]{0x49, 0x49, 0x2A, 0x00};
            assertEquals("image/tiff", MimeValidator.detectMime(tiff));
        }

        @Test
        @DisplayName("Should detect TIFF big-endian")
        void testDetectTiffBE() {
            byte[] tiff = new byte[]{0x4D, 0x4D, 0x00, 0x2A};
            assertEquals("image/tiff", MimeValidator.detectMime(tiff));
        }

        @Test
        @DisplayName("Should detect PDF")
        void testDetectPdf() {
            byte[] pdf = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E};
            assertEquals("application/pdf", MimeValidator.detectMime(pdf));
        }

        @Test
        @DisplayName("Should detect ZIP")
        void testDetectZip() {
            byte[] zip = new byte[]{0x50, 0x4B, 0x03, 0x04};
            assertEquals("application/zip", MimeValidator.detectMime(zip));
        }

        @Test
        @DisplayName("Should detect MP4")
        void testDetectMp4() {
            byte[] mp4 = new byte[]{0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70};
            assertEquals("video/mp4", MimeValidator.detectMime(mp4));
        }

        @Test
        @DisplayName("Should detect WAV")
        void testDetectWav() {
            byte[] wav = new byte[]{0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45};
            assertEquals("audio/wav", MimeValidator.detectMime(wav));
        }

        @Test
        @DisplayName("Should detect MP3 with ID3 tag")
        void testDetectMp3Id3() {
            byte[] mp3 = new byte[]{0x49, 0x44, 0x33, 0x04};
            assertEquals("audio/mpeg", MimeValidator.detectMime(mp3));
        }

        @Test
        @DisplayName("Should detect MP3 frame sync FF FB")
        void testDetectMp3FrameSyncFB() {
            byte[] mp3 = new byte[]{(byte) 0xFF, (byte) 0xFB, 0x00, 0x00};
            assertEquals("audio/mpeg", MimeValidator.detectMime(mp3));
        }

        @Test
        @DisplayName("Should detect MP3 frame sync FF F3")
        void testDetectMp3FrameSyncF3() {
            byte[] mp3 = new byte[]{(byte) 0xFF, (byte) 0xF3, 0x00, 0x00};
            assertEquals("audio/mpeg", MimeValidator.detectMime(mp3));
        }

        @Test
        @DisplayName("Should detect MP3 frame sync FF F2")
        void testDetectMp3FrameSyncF2() {
            byte[] mp3 = new byte[]{(byte) 0xFF, (byte) 0xF2, 0x00, 0x00};
            assertEquals("audio/mpeg", MimeValidator.detectMime(mp3));
        }

        @Test
        @DisplayName("Should detect OGG")
        void testDetectOgg() {
            byte[] ogg = new byte[]{0x4F, 0x67, 0x67, 0x53};
            assertEquals("audio/ogg", MimeValidator.detectMime(ogg));
        }

        @Test
        @DisplayName("Should detect FLAC")
        void testDetectFlac() {
            byte[] flac = new byte[]{0x66, 0x4C, 0x61, 0x43};
            assertEquals("audio/flac", MimeValidator.detectMime(flac));
        }

        @Test
        @DisplayName("Should return octet-stream for unknown")
        void testDetectUnknown() {
            byte[] unknown = new byte[]{0x00, 0x01, 0x02, 0x03};
            assertEquals("application/octet-stream", MimeValidator.detectMime(unknown));
        }

        @Test
        @DisplayName("Should return octet-stream for null")
        void testDetectNull() {
            assertEquals("application/octet-stream", MimeValidator.detectMime(null));
        }

        @Test
        @DisplayName("Should return octet-stream for too-short bytes")
        void testDetectTooShort() {
            byte[] tiny = new byte[]{0x01, 0x02};
            assertEquals("application/octet-stream", MimeValidator.detectMime(tiny));
        }

        @Test
        @DisplayName("Should return octet-stream for empty array")
        void testDetectEmpty() {
            assertEquals("application/octet-stream", MimeValidator.detectMime(new byte[0]));
        }

        @Test
        @DisplayName("Should not detect WebP with insufficient length")
        void testWebPTooShort() {
            // RIFF header but only 8 bytes — not enough for WebP check
            byte[] riff = new byte[]{0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00};
            // Should fall through to octet-stream (not enough for WebP/WAV check)
            assertEquals("application/octet-stream", MimeValidator.detectMime(riff));
        }
    }

    @Nested
    @DisplayName("Compatibility Check")
    class CompatibilityTests {

        @Test
        @DisplayName("Should accept exact match")
        void testExactMatch() {
            assertTrue(MimeValidator.isCompatible("image/png", "image/png"));
        }

        @Test
        @DisplayName("Should accept when detection returns octet-stream")
        void testUnknownDetection() {
            assertTrue(MimeValidator.isCompatible("application/custom", "application/octet-stream"));
        }

        @Test
        @DisplayName("Should accept text/plain content that has no signature")
        void testPlainTextUndetected() {
            assertTrue(MimeValidator.isCompatible("text/plain", MimeValidator.detectMime("hello world, plain text".getBytes())));
        }

        @ParameterizedTest(name = "rejects signature-less content declared as {0}")
        @ValueSource(strings = {"image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf",
                "IMAGE/PNG; charset=binary"})
        @DisplayName("Should reject undetectable content declared as a signature-bearing type")
        void testSignatureRequired(String declared) {
            byte[] text = "this is not a png at all".getBytes();
            assertEquals("application/octet-stream", MimeValidator.detectMime(text));
            assertFalse(MimeValidator.isCompatible(declared, MimeValidator.detectMime(text)),
                    "plain text must not pass as " + declared);
        }

        @Test
        @DisplayName("Should still accept real content for a signature-bearing type")
        void testSignatureRequiredAcceptsRealContent() {
            byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            assertTrue(MimeValidator.isCompatible("image/png", MimeValidator.detectMime(png)));
        }

        @Test
        @DisplayName("Should accept null declared MIME")
        void testNullDeclared() {
            assertTrue(MimeValidator.isCompatible(null, "image/png"));
        }

        @Test
        @DisplayName("Should accept null detected MIME")
        void testNullDetected() {
            assertTrue(MimeValidator.isCompatible("image/png", null));
        }

        @Test
        @DisplayName("Should accept both null")
        void testBothNull() {
            assertTrue(MimeValidator.isCompatible(null, null));
        }

        @Test
        @DisplayName("Should accept ZIP subtypes — DOCX")
        void testZipSubtypeDocx() {
            assertTrue(MimeValidator.isCompatible(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/zip"));
        }

        @Test
        @DisplayName("Should accept ZIP subtypes — XLSX")
        void testZipSubtypeXlsx() {
            assertTrue(MimeValidator.isCompatible(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/zip"));
        }

        @Test
        @DisplayName("Should accept ZIP subtypes — PPTX")
        void testZipSubtypePptx() {
            assertTrue(MimeValidator.isCompatible(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/zip"));
        }

        @Test
        @DisplayName("Should accept ZIP subtypes — JAR")
        void testZipSubtypeJar() {
            assertTrue(MimeValidator.isCompatible("application/java-archive", "application/zip"));
        }

        @Test
        @DisplayName("Should accept ZIP subtypes — EPUB")
        void testZipSubtypeEpub() {
            assertTrue(MimeValidator.isCompatible("application/epub+zip", "application/zip"));
        }

        @Test
        @DisplayName("Should reject non-ZIP subtype against ZIP detection")
        void testZipNonSubtype() {
            assertFalse(MimeValidator.isCompatible("image/png", "application/zip"));
        }

        @Test
        @DisplayName("Should reject MIME mismatch")
        void testMismatch() {
            assertFalse(MimeValidator.isCompatible("image/png", "image/jpeg"));
        }

        @Test
        @DisplayName("Should handle MIME with parameters")
        void testMimeWithParams() {
            assertTrue(MimeValidator.isCompatible("image/png; charset=utf-8", "image/png"));
        }

        @Test
        @DisplayName("Should be case-insensitive")
        void testCaseInsensitive() {
            assertTrue(MimeValidator.isCompatible("IMAGE/PNG", "image/png"));
        }
    }

    @Nested
    @DisplayName("Normalize")
    class NormalizeTests {

        @Test
        @DisplayName("Should strip MIME parameters")
        void testStripParams() {
            assertEquals("image/png", MimeValidator.normalize("image/png; charset=utf-8"));
        }

        @Test
        @DisplayName("Should lowercase")
        void testLowercase() {
            assertEquals("image/jpeg", MimeValidator.normalize("IMAGE/JPEG"));
        }

        @Test
        @DisplayName("Should trim whitespace")
        void testTrim() {
            assertEquals("text/plain", MimeValidator.normalize("  text/plain  "));
        }

        @Test
        @DisplayName("Should strip params and lowercase combined")
        void testCombined() {
            assertEquals("text/html", MimeValidator.normalize("TEXT/HTML; charset=UTF-8"));
        }

        @Test
        @DisplayName("Should return octet-stream for null")
        void testNull() {
            assertEquals("application/octet-stream", MimeValidator.normalize(null));
        }

        @Test
        @DisplayName("Should return octet-stream for blank")
        void testBlank() {
            assertEquals("application/octet-stream", MimeValidator.normalize("   "));
        }

        @Test
        @DisplayName("Should pass through canonical MIME unchanged")
        void testPassthrough() {
            assertEquals("application/json", MimeValidator.normalize("application/json"));
        }
    }

    /**
     * A real 1x1 24-bit BMP header: "BM", size, zero reserved, offset 54, DIB size
     * 40.
     */
    static byte[] bmpHeader() {
        byte[] bmp = new byte[58];
        bmp[0] = 0x42;
        bmp[1] = 0x4D;
        bmp[2] = 58;
        bmp[10] = 54;
        bmp[14] = 40;
        bmp[18] = 1;
        bmp[22] = 1;
        bmp[26] = 1;
        bmp[28] = 24;
        return bmp;
    }

    @Nested
    @DisplayName("Text that starts like a signature (false positives)")
    class FalsePositiveTests {

        private byte[] utf8(String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("a CSV whose first column is BMI is not a bitmap")
        void csvStartingWithBmi() {
            byte[] csv = utf8("BMI,weight,height\n22.5,70,176\n");

            assertEquals("application/octet-stream", MimeValidator.detectMime(csv));
            assertTrue(MimeValidator.isCompatibleContent("text/csv", csv));
        }

        @Test
        @DisplayName("Markdown that mentions %PDF- mid-line is not a PDF")
        void markdownMentioningPdfHeader() {
            byte[] markdown = utf8("# Notes\n\nEvery PDF file starts with `%PDF-1.7` followed by a binary comment.\n");

            assertEquals("application/octet-stream", MimeValidator.detectMime(markdown));
            assertTrue(MimeValidator.isCompatibleContent("text/markdown", markdown));
        }

        @Test
        @DisplayName("text declared as text is accepted even when a line looks like a header")
        void textWithAHeaderLikeLine() {
            byte[] markdown = utf8("Example:\n%PDF-1.7\n");

            // Detection may say PDF (a header at a line start is where print jobs put it)
            // ...
            assertEquals("application/pdf", MimeValidator.detectMime(markdown));
            // ... but readable text declared as text is text.
            assertTrue(MimeValidator.isCompatibleContent("text/markdown", markdown));
            assertTrue(MimeValidator.isCompatibleContent("text/plain; charset=utf-8", markdown));
        }

        @ParameterizedTest
        @ValueSource(strings = {"ID3 tags explained\n", "GIF89a is a format\n", "II*\001 not tiff", "OggS is a container\n"})
        @DisplayName("notes that open with another format's magic are accepted as text")
        void otherMagicPrefixes(String text) {
            assertTrue(MimeValidator.isCompatibleContent("text/plain", utf8(text)));
        }

        @Test
        @DisplayName("a real bitmap declared as CSV is still refused")
        void realBinaryDeclaredAsTextIsRefused() {
            assertFalse(MimeValidator.isCompatibleContent("text/csv", bmpHeader()));
        }

        @Test
        @DisplayName("text declared as an image is still refused")
        void textDeclaredAsImageIsRefused() {
            assertFalse(MimeValidator.isCompatibleContent("image/png", utf8("not a png at all")));
        }

        @Test
        @DisplayName("a real PDF with a print-job prefix is still a PDF")
        void pdfAfterPrintJobPrefix() {
            byte[] pjl = utf8("\033%-12345X@PJL ENTER LANGUAGE=PDF\r\n%PDF-1.4\n");

            assertEquals("application/pdf", MimeValidator.detectMime(pjl));
        }

        @Test
        @DisplayName("a PDF after a byte-order mark is still a PDF")
        void pdfAfterBom() {
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '%', 'P', 'D', 'F', '-', '1', '.', '7'};

            assertEquals("application/pdf", MimeValidator.detectMime(bom));
        }

        @Test
        @DisplayName("looksLikeText: NUL bytes make content binary")
        void nulIsBinary() {
            assertFalse(MimeValidator.looksLikeText(new byte[]{'a', 0, 'b'}));
            assertTrue(MimeValidator.looksLikeText(utf8("plain, text; with ümlauts\tand tabs\r\n")));
            assertFalse(MimeValidator.looksLikeText(new byte[0]));
        }

        @ParameterizedTest
        @ValueSource(strings = {"text/csv", "text/markdown", "application/json", "application/ld+json", "application/x-yaml",
                "image/svg+xml"})
        @DisplayName("textual declared types")
        void textualTypes(String mime) {
            assertTrue(MimeValidator.isTextual(mime));
        }

        @ParameterizedTest
        @ValueSource(strings = {"image/png", "application/pdf", "application/octet-stream", "application/zip"})
        @DisplayName("binary declared types")
        void binaryTypes(String mime) {
            assertFalse(MimeValidator.isTextual(mime));
        }
    }
}
