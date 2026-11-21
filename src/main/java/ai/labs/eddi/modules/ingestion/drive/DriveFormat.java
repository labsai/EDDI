/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import java.util.List;
import java.util.Locale;

/**
 * The Drive file types this source reads, by the names {@code mimeTypes} uses,
 * and how each one becomes bytes the framework's extractors understand.
 *
 * <p>
 * Google's own formats have no bytes of their own and are exported: a Doc as
 * Markdown (HTML as the fallback), a Sheet as XLSX, Slides as plain text. A
 * Sheet is exported as XLSX rather than CSV because the CSV export carries only
 * the first sheet; XLSX carries every sheet, and the spreadsheet extractor
 * renders each as a Markdown table with its header row and a row cap. Every
 * other type is downloaded as it is stored.
 */
enum DriveFormat {

    // @formatter:off
    GOOGLE_DOCS("google-docs", List.of("application/vnd.google-apps.document"), null, "document", "text/markdown"),
    GOOGLE_SHEETS("google-sheets", List.of("application/vnd.google-apps.spreadsheet"), null, "spreadsheet",
            DriveFormat.XLSX_MIME),
    GOOGLE_SLIDES("google-slides", List.of("application/vnd.google-apps.presentation"), null, "presentation",
            "text/plain"),
    PDF("pdf", List.of("application/pdf"), ".pdf", "pdf", null),
    DOCX("docx", List.of(DriveFormat.DOCX_MIME), ".docx", "document", null),
    XLSX("xlsx", List.of(DriveFormat.XLSX_MIME), ".xlsx", "spreadsheet", null),
    PPTX("pptx", List.of(DriveFormat.PPTX_MIME), ".pptx", "presentation", null),
    TEXT("text", List.of("text/plain"), ".txt", "text", null),
    MARKDOWN("markdown", List.of("text/markdown", "text/x-markdown"), ".md", "text", null),
    CSV("csv", List.of("text/csv"), ".csv", "spreadsheet", null),
    HTML("html", List.of("text/html"), ".html", "html", null);
    // @formatter:on

    static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    /** A folder; listed into, never ingested. */
    static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    /** A shortcut; followed when it points at a folder. */
    static final String SHORTCUT_MIME = "application/vnd.google-apps.shortcut";

    private final String key;
    private final List<String> driveMimes;
    private final String extension;
    private final String formatClass;
    private final String exportMime;

    DriveFormat(String key, List<String> driveMimes, String extension, String formatClass, String exportMime) {
        this.key = key;
        this.driveMimes = driveMimes;
        this.extension = extension;
        this.formatClass = formatClass;
        this.exportMime = exportMime;
    }

    /** The format a Drive MIME type is, or null when this source cannot read it. */
    static DriveFormat of(String driveMime) {
        if (driveMime == null) {
            return null;
        }
        String normalised = driveMime.toLowerCase(Locale.ROOT);
        for (DriveFormat format : values()) {
            if (format.driveMimes.contains(normalised)) {
                return format;
            }
        }
        return null;
    }

    /** The name {@code googleDrive.mimeTypes} uses. */
    String key() {
        return key;
    }

    /** A Google-native file, which is exported rather than downloaded. */
    boolean isNative() {
        return exportMime != null;
    }

    /** What a native file is exported as. */
    String exportMime() {
        return exportMime;
    }

    /**
     * The extension a stored file of this format carries — added to a name that
     * lacks it, so the extractor registry tells one text format from another.
     */
    String extension() {
        return extension;
    }

    /**
     * The {@code format} metadata value, as {@code SourceMetadataKeys.FORMAT} names
     * it.
     */
    String formatClass() {
        return formatClass;
    }
}
