/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code filters} block shared by every file-shaped source type — what a
 * connector skips before it downloads anything.
 *
 * <p>
 * One shape for all of them, so an operator who learned it for one provider
 * knows it for the next, and the Manager renders one form. Every field is
 * optional; an absent block, or an absent field, filters nothing.
 *
 * <p>
 * Config model only in this version: no source type reads it yet. A connector
 * that adopts it applies {@link #validate} at save time.
 *
 * <pre>
 * "filters": {
 *   "formats": ["document", "pdf"],
 *   "excludeNamePatterns": ["~$*", "*.tmp"],
 *   "includePaths": ["/Handbook/", "/Policies/"],
 *   "maxFileBytes": 26214400
 * }
 * </pre>
 */
public class SourceFilters {

    /** Format classes to ingest; empty or absent means every readable format. */
    private List<FormatClass> formats;

    /** Name globs to skip ({@code *} matches within a name). */
    private List<String> excludeNamePatterns;

    /**
     * Path prefixes to restrict to, within the source; empty or absent means the
     * whole source.
     */
    private List<String> includePaths;

    /** Files larger than this are skipped without being downloaded. */
    private Long maxFileBytes;

    /**
     * A family of formats, as an operator thinks of them rather than as MIME types
     * enumerate them. Stored lower-case.
     */
    public enum FormatClass {
        /** Word processing: DOCX, ODT, Google Docs, RTF. */
        DOCUMENT,
        /** XLSX, ODS, CSV, Google Sheets. */
        SPREADSHEET,
        /** PPTX, ODP, Google Slides. */
        PRESENTATION, PDF,
        /** Plain text and Markdown. */
        TEXT, HTML,
        /** Mail messages and threads. */
        EMAIL;

        @JsonValue
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * @throws IllegalArgumentException
         *             naming the accepted values
         */
        @JsonCreator
        public static FormatClass fromId(String id) {
            if (id != null) {
                for (FormatClass value : values()) {
                    if (value.id().equalsIgnoreCase(id.trim())) {
                        return value;
                    }
                }
            }
            List<String> accepted = new ArrayList<>();
            for (FormatClass value : values()) {
                accepted.add(value.id());
            }
            throw new IllegalArgumentException("Unknown format '" + id + "'. Accepted: " + accepted);
        }
    }

    /**
     * @throws IllegalArgumentException
     *             naming the field and the source
     */
    public void validate(String sourceName) {
        if (formats != null && formats.contains(null)) {
            throw new IllegalArgumentException("filters.formats of ingestion source '" + sourceName
                    + "' contains an empty entry");
        }
        requireNoBlank(excludeNamePatterns, "filters.excludeNamePatterns", sourceName);
        requireNoBlank(includePaths, "filters.includePaths", sourceName);
        if (maxFileBytes != null && maxFileBytes <= 0) {
            throw new IllegalArgumentException("filters.maxFileBytes of ingestion source '" + sourceName
                    + "' must be positive");
        }
    }

    private static void requireNoBlank(List<String> values, String field, String sourceName) {
        if (values != null && values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(field + " of ingestion source '" + sourceName
                    + "' contains an empty entry");
        }
    }

    /** Whether a format class passes {@link #formats}. */
    public boolean acceptsFormat(FormatClass format) {
        return formats == null || formats.isEmpty() || formats.contains(format);
    }

    public List<FormatClass> getFormats() {
        return formats;
    }

    public void setFormats(List<FormatClass> formats) {
        this.formats = formats;
    }

    public List<String> getExcludeNamePatterns() {
        return excludeNamePatterns;
    }

    public void setExcludeNamePatterns(List<String> excludeNamePatterns) {
        this.excludeNamePatterns = excludeNamePatterns;
    }

    public List<String> getIncludePaths() {
        return includePaths;
    }

    public void setIncludePaths(List<String> includePaths) {
        this.includePaths = includePaths;
    }

    public Long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(Long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }
}
