/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.files;

import ai.labs.eddi.modules.ingestion.ContentHashes;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The identity of an uploaded file.
 *
 * <p>
 * Derived from the name rather than generated, so that re-uploading a corrected
 * {@code handbook.pdf} replaces the old one everywhere at once — the blob, the
 * ingestion state row and the vectors all key on this id. A generated id would
 * make the second upload a second document, and retrieval would then answer
 * from both the old handbook and the new one with no way to tell which.
 */
public final class IngestedFileIds {

    /** Long enough that a collision needs intent, short enough to read in a log. */
    private static final int ID_LENGTH = 32;

    private IngestedFileIds() {
    }

    /**
     * The id for a file name.
     *
     * <p>
     * The name is normalised first, because the same file dragged from a Mac and
     * from Windows can carry the same letters in different Unicode forms, and an
     * operator who sees one {@code résumé.pdf} in the list should not have two.
     */
    public static String forFileName(String fileName) {
        String normalized = Normalizer.normalize(sanitize(fileName), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        return ContentHashes.sha256(normalized).substring(0, ID_LENGTH);
    }

    /**
     * The name as it will be shown and stored: the last path segment, with control
     * characters removed.
     *
     * <p>
     * Browsers send a bare name, but a multipart part's filename is client-supplied
     * text: it can carry a path, a {@code ..}, or a newline that would split a log
     * line into two. Nothing here builds a filesystem path out of it, so this is
     * about what is displayed and matched, not about escaping a directory.
     */
    public static String sanitize(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file";
        }
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .strip();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "file";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
