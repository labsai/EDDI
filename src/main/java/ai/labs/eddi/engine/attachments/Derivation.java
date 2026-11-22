/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.util.Locale;

/**
 * Where a derived blob comes from: a stored view of an uploaded file (its
 * <em>root</em>), such as its manifest, the text of a page window, an extracted
 * image or a page-range slice.
 * <p>
 * A derived blob belongs to its root's owner (resolved by the store at write
 * time, never by the caller), is readable by whoever may read the root, and is
 * deleted with the root. A view of a view keeps {@code rootRef} = the original
 * upload and records its direct parent in {@code parentRef}.
 *
 * @param rootRef
 *            the uploaded blob the lineage starts at; the store normalizes it
 *            (a derived ref given here is replaced by its own root)
 * @param parentRef
 *            the direct parent, or {@code null} when it is the root
 * @param kind
 *            what the view is; never {@code null}
 * @param unitId
 *            the producer's cache key within the root and kind (a unit id, a
 *            page range, a summary key), or {@code null}
 * @param page
 *            the 1-based page the view was taken from, or {@code null}
 * @param sha256
 *            hex SHA-256 of the bytes. Computed by the store; a value supplied
 *            by the caller is ignored
 * @since 6.8.0
 */
public record Derivation(String rootRef, String parentRef, Kind kind, String unitId, Integer page, String sha256) {

    /** What a derived blob is. */
    public enum Kind {
        /** A document's structure (units, page ranges, warnings); never bytes. */
        MANIFEST,
        /** One part of a manifest too large for one blob. */
        MANIFEST_PART,
        /** The extracted text of a page window. */
        TEXT_PART,
        /** An image extracted or rendered from the document. */
        IMAGE,
        /** A table extracted from the document. */
        TABLE,
        /** An audio file embedded in the document. */
        AUDIO,
        /** A page-range PDF built for a document-capable model; a rebuildable cache. */
        PDF_SLICE,
        /** A summary of the document or of a unit. */
        SUMMARY,
        /** A description of an image or a unit. */
        DESCRIPTION,
        /** Any other file embedded in the document. */
        EMBEDDED;

        /**
         * The stored name back to a kind; {@code null} for {@code null}.
         *
         * @throws IllegalArgumentException
         *             for a name this release does not know
         */
        public static Kind fromStored(String stored) {
            return stored == null ? null : Kind.valueOf(stored.strip().toUpperCase(Locale.ROOT));
        }
    }

    public Derivation {
        if (kind == null) {
            throw new IllegalArgumentException("A derivation needs a kind");
        }
    }

    /** A view of {@code rootRef} taken from the root itself. */
    public static Derivation of(String rootRef, Kind kind, String unitId) {
        return new Derivation(rootRef, null, kind, unitId, null, null);
    }

    /** The same derivation with a page. */
    public Derivation withPage(Integer page) {
        return new Derivation(rootRef, parentRef, kind, unitId, page, sha256);
    }

    /** The same derivation with a direct parent other than the root. */
    public Derivation withParent(String parentRef) {
        return new Derivation(rootRef, parentRef, kind, unitId, page, sha256);
    }

    /**
     * The derivation as the store records it: its root and parent resolved, its
     * hash computed.
     */
    public Derivation resolved(String resolvedRoot, String resolvedParent, String computedSha256) {
        return new Derivation(resolvedRoot, resolvedParent, kind, unitId, page, computedSha256);
    }
}
