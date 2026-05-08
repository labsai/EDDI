/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Magic-byte based MIME type detection. Used to validate that the declared MIME
 * type matches the actual file content. No external dependencies required.
 * <p>
 * Supported signatures:
 * <ul>
 * <li>JPEG, PNG, GIF, BMP, WebP, TIFF (images)</li>
 * <li>PDF</li>
 * <li>ZIP (docx, xlsx, etc.)</li>
 * <li>MP3, MP4, OGG, WAV, FLAC (audio/video)</li>
 * </ul>
 *
 * @since 6.0.0
 */
public final class MimeValidator {

    private static final Logger LOGGER = Logger.getLogger(MimeValidator.class);

    private MimeValidator() {
        // utility class
    }

    /**
     * Detect the MIME type from file magic bytes.
     *
     * @param bytes
     *            the file content (at least 12 bytes for reliable detection)
     * @return detected MIME type, or "application/octet-stream" if unknown
     */
    public static String detectMime(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return "application/octet-stream";
        }

        // JPEG: FF D8 FF
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47
        if (startsWith(bytes, 0x89, 0x50, 0x4E, 0x47)) {
            return "image/png";
        }
        // GIF: 47 49 46 38
        if (startsWith(bytes, 0x47, 0x49, 0x46, 0x38)) {
            return "image/gif";
        }
        // BMP: 42 4D
        if (startsWith(bytes, 0x42, 0x4D)) {
            return "image/bmp";
        }
        // WebP: RIFF....WEBP
        if (bytes.length >= 12 && startsWith(bytes, 0x52, 0x49, 0x46, 0x46)
                && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50) {
            return "image/webp";
        }
        // TIFF: 49 49 2A 00 (little-endian) or 4D 4D 00 2A (big-endian)
        if (startsWith(bytes, 0x49, 0x49, 0x2A, 0x00) || startsWith(bytes, 0x4D, 0x4D, 0x00, 0x2A)) {
            return "image/tiff";
        }
        // PDF: 25 50 44 46 (%PDF)
        if (startsWith(bytes, 0x25, 0x50, 0x44, 0x46)) {
            return "application/pdf";
        }
        // ZIP/DOCX/XLSX: 50 4B 03 04
        if (startsWith(bytes, 0x50, 0x4B, 0x03, 0x04)) {
            return "application/zip";
        }
        // MP4: ....ftyp (at offset 4)
        if (bytes.length >= 8 && bytes[4] == 0x66 && bytes[5] == 0x74 && bytes[6] == 0x79 && bytes[7] == 0x70) {
            return "video/mp4";
        }
        // OGG: OggS
        if (startsWith(bytes, 0x4F, 0x67, 0x67, 0x53)) {
            return "audio/ogg";
        }
        // FLAC: fLaC
        if (startsWith(bytes, 0x66, 0x4C, 0x61, 0x43)) {
            return "audio/flac";
        }
        // WAV: RIFF....WAVE
        if (bytes.length >= 12 && startsWith(bytes, 0x52, 0x49, 0x46, 0x46)
                && bytes[8] == 0x57 && bytes[9] == 0x41 && bytes[10] == 0x56 && bytes[11] == 0x45) {
            return "audio/wav";
        }
        // MP3: ID3 tag or frame sync (FF FB, FF F3, FF F2)
        if (startsWith(bytes, 0x49, 0x44, 0x33)
                || (bytes[0] == (byte) 0xFF && (bytes[1] == (byte) 0xFB || bytes[1] == (byte) 0xF3 || bytes[1] == (byte) 0xF2))) {
            return "audio/mpeg";
        }

        return "application/octet-stream";
    }

    /**
     * Validate that the declared MIME type is compatible with the detected one.
     *
     * @param declaredMime
     *            the MIME type declared by the client
     * @param detectedMime
     *            the MIME type detected from file content
     * @return true if compatible
     */
    public static boolean isCompatible(String declaredMime, String detectedMime) {
        if (declaredMime == null || detectedMime == null) {
            LOGGER.warnf("MIME validation skipped — declared='%s', detected='%s'. " +
                    "This is lenient but may mask upload issues.", declaredMime, detectedMime);
            return true; // lenient when detection fails
        }
        if ("application/octet-stream".equals(detectedMime)) {
            return true; // unknown detection — allow declared
        }

        // Normalize
        String declared = declaredMime.split(";")[0].trim().toLowerCase();
        String detected = detectedMime.split(";")[0].trim().toLowerCase();

        // Exact match
        if (declared.equals(detected)) {
            return true;
        }

        // ZIP family (docx, xlsx, etc. are ZIP-based)
        if ("application/zip".equals(detected)) {
            return MIME_ZIP_SUBTYPES.contains(declared);
        }

        LOGGER.debugf("MIME mismatch: declared='%s', detected='%s'", declared, detected);
        return false;
    }

    private static boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /** ZIP-based MIME types that share the PK\x03\x04 signature */
    private static final Set<String> MIME_ZIP_SUBTYPES = Set.of(
            "application/zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/java-archive",
            "application/epub+zip");
}
