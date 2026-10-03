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
    private static final byte[] PDF_HEADER = {0x25, 0x50, 0x44, 0x46, 0x2D};
    private static final int PDF_HEADER_SEARCH_WINDOW = 1024;

    /**
     * Whether a PDF header ({@code %PDF-} plus a version digit) starts within the
     * first {@link #PDF_HEADER_SEARCH_WINDOW} bytes <em>where a PDF header can
     * stand</em>: at offset 0, after nothing but a byte-order mark and whitespace,
     * or at the start of a line (a print-job prefix ends in a newline). Anywhere
     * else it is text that mentions a PDF — a Markdown note reading "files start
     * with %PDF-1.7" was refused as a mislabelled PDF.
     */
    private static boolean hasPdfHeader(byte[] bytes) {
        int lastStart = Math.min(PDF_HEADER_SEARCH_WINDOW, bytes.length) - PDF_HEADER.length - 1;
        for (int start = 0; start <= lastStart; start++) {
            if (matchesAt(bytes, start, PDF_HEADER) && isAsciiDigit(bytes[start + PDF_HEADER.length])
                    && pdfHeaderMayStartAt(bytes, start)) {
                return true;
            }
        }
        return false;
    }

    private static boolean pdfHeaderMayStartAt(byte[] bytes, int start) {
        if (start == 0) {
            return true;
        }
        byte previous = bytes[start - 1];
        if (previous == '\n' || previous == '\r') {
            return true;
        }
        // Only a BOM and/or whitespace before it.
        int index = startsWith(bytes, 0xEF, 0xBB, 0xBF) ? 3 : 0;
        while (index < start && (bytes[index] == ' ' || bytes[index] == '\t')) {
            index++;
        }
        return index == start;
    }

    private static boolean matchesAt(byte[] bytes, int offset, byte[] needle) {
        if (offset + needle.length > bytes.length) {
            return false;
        }
        for (int i = 0; i < needle.length; i++) {
            if (bytes[offset + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDigit(byte value) {
        return value >= '0' && value <= '9';
    }

    /**
     * A BMP is "BM", a 4-byte file size, four reserved bytes that are always zero,
     * a 4-byte pixel offset, then a DIB header whose size field names one of the
     * known header versions. Matching "BM" alone refused every CSV whose first
     * column was {@code BMI} as a mislabelled bitmap.
     */
    private static boolean isBmp(byte[] bytes) {
        if (bytes.length < 18 || !startsWith(bytes, 0x42, 0x4D)) {
            return false;
        }
        if (bytes[6] != 0 || bytes[7] != 0 || bytes[8] != 0 || bytes[9] != 0) {
            return false;
        }
        int dibHeaderSize = (bytes[14] & 0xFF) | (bytes[15] & 0xFF) << 8 | (bytes[16] & 0xFF) << 16 | (bytes[17] & 0xFF) << 24;
        return BMP_DIB_HEADER_SIZES.contains(dibHeaderSize);
    }

    /** BITMAPCOREHEADER, OS/2 v2, BITMAPINFOHEADER, v2, v3, v4 and v5. */
    private static final Set<Integer> BMP_DIB_HEADER_SIZES = Set.of(12, 16, 40, 52, 56, 64, 108, 124);

    /** Bytes inspected by {@link #looksLikeText}. */
    static final int TEXT_SNIFF_WINDOW = 8192;

    /**
     * Whether the content reads as text: no NUL byte and almost no other control
     * characters in the first {@link #TEXT_SNIFF_WINDOW} bytes (a UTF-16 byte-order
     * mark counts as text). Every binary format this class recognises carries NUL
     * bytes or dense control bytes in its header, so real images, archives and
     * audio never qualify.
     */
    public static boolean looksLikeText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        if (startsWith(bytes, 0xFE, 0xFF) || startsWith(bytes, 0xFF, 0xFE)) {
            return true;
        }
        int window = Math.min(bytes.length, TEXT_SNIFF_WINDOW);
        int controls = 0;
        for (int i = 0; i < window; i++) {
            int value = bytes[i] & 0xFF;
            if (value == 0) {
                return false;
            }
            if (value < 0x20 && value != '\t' && value != '\n' && value != '\r' && value != '\f' && value != 0x1B) {
                controls++;
            }
        }
        // A stray control byte in a hand-edited file is tolerated; a binary header is
        // not.
        return controls * 100 <= window;
    }

    /**
     * Whether a declared type is a text format: {@code text/*}, a JSON or XML type
     * (including {@code +json}/{@code +xml} suffixes), YAML, CSV or JavaScript.
     */
    public static boolean isTextual(String declaredMime) {
        String mime = normalize(declaredMime);
        return mime.startsWith("text/") || mime.endsWith("+json") || mime.endsWith("+xml") || TEXTUAL_APPLICATION_TYPES.contains(mime);
    }

    private static final Set<String> TEXTUAL_APPLICATION_TYPES = Set.of(
            "application/json",
            "application/x-ndjson",
            "application/xml",
            "application/yaml",
            "application/x-yaml",
            "application/csv",
            "application/javascript",
            "application/x-javascript",
            "application/sql");

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
        // BMP: 42 4D plus a plausible header (see isBmp)
        if (isBmp(bytes)) {
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
        // PDF: %PDF- — readers accept the header anywhere in the first 1024 bytes, and
        // real generators emit leading bytes (BOM, print-job prefixes). Offset 0 only
        // would refuse those as "mislabelled" now that PDFs require a signature.
        if (hasPdfHeader(bytes)) {
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
     * Normalize a MIME type to its canonical form: strip parameters (e.g.,
     * {@code ; charset=utf-8}), trim whitespace, and lowercase.
     *
     * @param mime
     *            the MIME type string (may include parameters)
     * @return the normalized base MIME type, or {@code "application/octet-stream"}
     *         if null/blank
     */
    public static String normalize(String mime) {
        if (mime == null || mime.isBlank()) {
            return "application/octet-stream";
        }
        return mime.split(";")[0].trim().toLowerCase();
    }

    /**
     * Validate an upload: the declared type must be compatible with the content.
     * <p>
     * Text declared as text is always compatible. Signatures are short prefixes, so
     * a text file can begin with one by accident — a CSV whose first column is
     * {@code BMI}, notes that open with {@code ID3} or {@code GIF8}, Markdown with
     * a line reading {@code %PDF-1.7} — and refusing it as a "mislabelled image" is
     * a false positive. Content that reads as text and is declared as text is
     * stored as text and never forwarded to a model as an image or PDF, so
     * accepting it widens nothing. Everything else falls through to
     * {@link #isCompatible(String, String)}.
     *
     * @param declaredMime
     *            the MIME type declared by the client
     * @param bytes
     *            the content
     * @return true if compatible
     */
    public static boolean isCompatibleContent(String declaredMime, byte[] bytes) {
        if (declaredMime != null && isTextual(declaredMime) && looksLikeText(bytes)) {
            return true;
        }
        return isCompatible(declaredMime, detectMime(bytes));
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
            // Unrecognised content may carry any declared type — EXCEPT a type this
            // class can recognise by its signature. Content that claims to be a PNG
            // but has no PNG signature is not an unknown file, it is a mislabelled
            // one, and allowing it through made the check pass exactly the uploads it
            // exists to stop (plain text declared image/png was stored with
            // forwardableInline=true and handed to a vision model).
            String declared = normalize(declaredMime);
            if (SIGNATURE_REQUIRED.contains(declared)) {
                LOGGER.debugf("MIME mismatch: declared='%s' carries no matching signature", declared);
                return false;
            }
            return true;
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

    /**
     * Declared types whose content this class can always recognise. A file declared
     * as one of these must carry the signature: undetectable content is rejected
     * rather than waved through as "unknown". Kept to the formats that are sent to
     * a model inline (images, PDF), where a mislabelled upload does real harm; the
     * audio/video/office signatures above stay lenient so an unusual but legitimate
     * container variant is not refused.
     */
    static final Set<String> SIGNATURE_REQUIRED = Set.of(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
            "application/pdf");

    /** ZIP-based MIME types that share the PK\x03\x04 signature */
    private static final Set<String> MIME_ZIP_SUBTYPES = Set.of(
            "application/zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/java-archive",
            "application/epub+zip");
}
