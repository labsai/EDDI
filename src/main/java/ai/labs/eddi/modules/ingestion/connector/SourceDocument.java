/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One document's content, as a connector read it.
 *
 * <p>
 * The connector delivers bytes or markup; turning them into text is the
 * framework's job, chosen by the {@link Content} variant. That keeps format
 * handling — and its limits — in one place for every source.
 *
 * @param documentId
 *            stable across renames and moves
 * @param content
 *            what was read
 * @param versionToken
 *            the provider's version of exactly this content (a revision id, a
 *            hash of the bytes). When present it is the change key the
 *            framework compares and stores; when null, the hash of the
 *            extracted text is. Supply one only if it changes whenever the
 *            content does
 * @param validators
 *            the HTTP validators of the response that delivered this content.
 *            When non-null they replace the stored ones even if the content is
 *            unchanged, so the next conditional request uses the newest. Null
 *            for content that did not come from a conditional-capable response
 * @param truncated
 *            the content hit a size cap and is incomplete; it is logged, then
 *            ingested as it is
 * @param metadata
 *            descriptive metadata under {@link SourceMetadataKeys}. Never null;
 *            unknown keys are allowed. Not written to chunks yet beyond
 *            {@link SourceMetadataKeys#TITLE} and
 *            {@link SourceMetadataKeys#CITATION_URL}
 */
public record SourceDocument(String documentId, Content content, String versionToken, ResponseValidators validators,
        boolean truncated, Map<String, Object> metadata) {

    public SourceDocument {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(content, "content");
        // Insertion order kept, nulls dropped: a connector can put an optional field
        // without checking it first.
        Map<String, Object> copy = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
        }
        metadata = Collections.unmodifiableMap(copy);
    }

    /** The title to show and to store on every chunk; null when there is none. */
    public String title() {
        return stringOf(SourceMetadataKeys.TITLE);
    }

    /** What a human opens from a cited answer; null when there is none. */
    public String citationUrl() {
        return stringOf(SourceMetadataKeys.CITATION_URL);
    }

    /**
     * How to name the document in a log line: its path within the source, else its
     * id.
     */
    public String displayName() {
        String path = stringOf(SourceMetadataKeys.PATH);
        return path != null ? path : documentId;
    }

    private String stringOf(String key) {
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    /** The content of a document, in the form the framework converts it from. */
    public sealed interface Content {

        /**
         * A page a website served. Converted with the page converter (hidden content
         * dropped, links resolved against the citation URL). A page that converts to
         * nothing is navigation, and is skipped rather than failed.
         */
        record HtmlPage(String html) implements Content {
            public HtmlPage {
                Objects.requireNonNull(html, "html");
            }
        }

        /**
         * A file a source stores. Text is extracted by MIME type. A file that yields no
         * text — a scan, an empty document, a format that cannot be read — is a
         * definitive answer: it fails, and any older version's chunks are retired.
         */
        record FileBytes(byte[] bytes, String mimeType) implements Content {
            public FileBytes {
                Objects.requireNonNull(bytes, "bytes");
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof FileBytes that && Arrays.equals(bytes, that.bytes)
                        && Objects.equals(mimeType, that.mimeType);
            }

            @Override
            public int hashCode() {
                return 31 * Arrays.hashCode(bytes) + Objects.hashCode(mimeType);
            }

            @Override
            public String toString() {
                return "FileBytes[" + bytes.length + " bytes, " + mimeType + "]";
            }
        }

        /**
         * Text the provider already extracted (a mail body, an export as plain text).
         * Embedded as it is; blank text is skipped like an empty page.
         */
        record Text(String text) implements Content {
            public Text {
                Objects.requireNonNull(text, "text");
            }
        }
    }

    /** HTTP validators of the response that delivered a document. */
    public record ResponseValidators(String etag, String lastModified) {
    }
}
