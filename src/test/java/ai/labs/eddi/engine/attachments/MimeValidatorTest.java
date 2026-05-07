/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
        @DisplayName("Should detect BMP")
        void testDetectBmp() {
            byte[] bmp = new byte[]{0x42, 0x4D, 0x00, 0x00, 0x00, 0x00};
            assertEquals("image/bmp", MimeValidator.detectMime(bmp));
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
}
