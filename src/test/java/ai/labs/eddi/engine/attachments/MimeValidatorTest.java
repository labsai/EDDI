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
        @DisplayName("Should detect MP3 with ID3 tag")
        void testDetectMp3() {
            byte[] mp3 = new byte[]{0x49, 0x44, 0x33, 0x04};
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
        @DisplayName("Should accept ZIP subtypes")
        void testZipSubtypes() {
            assertTrue(MimeValidator.isCompatible(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/zip"));
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
    }
}
