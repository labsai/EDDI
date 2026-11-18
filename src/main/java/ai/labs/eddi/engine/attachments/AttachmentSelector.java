/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.memory.model.Attachment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one share, read or forward refers to: a stored file, or a part of it.
 * <p>
 * {@code pages} ({@code "<from>-<to>"}, 1-based, inclusive) is
 * <strong>authoritative</strong>. {@code unit} is only the label the pages came
 * from (a chapter id such as {@code u3}) and {@code split} the hash of the
 * split configuration that label belongs to — a unit id means nothing outside
 * that configuration, so a receiver renders by {@code pages}, never by
 * {@code unit}. All three {@code null} is the whole file.
 * <p>
 * A selector travels in the existing {@link Attachment#getMetadata()} map under
 * the {@code META_*} keys below; no new field is added to the attachment model.
 * It is always built by server code from an entry of a conversation's
 * {@link ConversationListing} — a model names a file, it never supplies a
 * {@code storageRef}.
 *
 * @param storageRef
 *            the stored blob
 * @param pages
 *            the page range, or {@code null} for the whole file
 * @param unit
 *            the unit label the pages came from, or {@code null}
 * @param split
 *            the split-configuration hash {@code unit} belongs to, or
 *            {@code null}
 * @since 6.7.0
 */
public record AttachmentSelector(String storageRef, String pages, String unit, String split) {

    /** Metadata key of {@link #pages}. */
    public static final String META_PAGES = "doc.pages";
    /** Metadata key of {@link #unit}. */
    public static final String META_UNIT = "doc.unit";
    /** Metadata key of {@link #split}. */
    public static final String META_SPLIT = "doc.split";
    /** Metadata key marking an attachment as a derived view of a root blob. */
    public static final String META_DERIVED = "doc.derived";
    /** Metadata key of the root blob a derived view belongs to. */
    public static final String META_ROOT = "doc.root";

    /** The whole of the file {@code storageRef}. */
    public static AttachmentSelector wholeFile(String storageRef) {
        return new AttachmentSelector(storageRef, null, null, null);
    }

    /** Whether this selector names the whole file rather than a part of it. */
    public boolean isWholeFile() {
        return pages == null && unit == null && split == null;
    }

    /**
     * The selector's part as attachment metadata — empty for a whole file. The
     * {@code storageRef} is not included: it has its own field on the attachment.
     */
    public Map<String, String> toMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (pages != null) {
            metadata.put(META_PAGES, pages);
        }
        if (unit != null) {
            metadata.put(META_UNIT, unit);
        }
        if (split != null) {
            metadata.put(META_SPLIT, split);
        }
        return metadata;
    }

    /**
     * The selector an attachment carries: its {@code storageRef} plus any part in
     * its metadata.
     *
     * @return the selector, or {@code null} when the attachment is {@code null} or
     *         is not blob-backed (no {@code storageRef})
     */
    public static AttachmentSelector fromAttachment(Attachment attachment) {
        if (attachment == null || attachment.getStorageRef() == null || attachment.getStorageRef().isBlank()) {
            return null;
        }
        Map<String, String> metadata = attachment.getMetadata() != null ? attachment.getMetadata() : Map.of();
        return new AttachmentSelector(attachment.getStorageRef(), metadata.get(META_PAGES), metadata.get(META_UNIT),
                metadata.get(META_SPLIT));
    }
}
