/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.Derivation.Kind;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentDerivedCapException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The per-source cap rule both attachment stores apply to derived blobs, once
 * they hold the root's source lock and have read its lineage. Pure arithmetic,
 * like {@link AttachmentQuotas}.
 * <p>
 * Three budgets per root:
 * <ul>
 * <li><b>media</b> ({@code IMAGE}, {@code TABLE}, {@code AUDIO},
 * {@code EMBEDDED}): at most {@code maxPerSource} blobs and
 * {@code maxTotalBytesPerSource} bytes. These are the only derived bytes that
 * also count toward the owner's byte quota.</li>
 * <li><b>text</b> ({@code MANIFEST}, {@code MANIFEST_PART}, {@code TEXT_PART},
 * {@code SUMMARY}, {@code DESCRIPTION}): at most {@code maxTextBytesPerSource}
 * bytes; no count.</li>
 * <li><b>slices</b> ({@code PDF_SLICE}): a rebuildable cache of at most
 * {@code maxSlicesPerSource} blobs and {@code maxSliceBytesPerSource} bytes;
 * the least recently accessed are evicted to make room instead of
 * refusing.</li>
 * </ul>
 * Text kinds and slices are exempt from owner quotas so that reading a file —
 * possibly by a grantee, possibly driven by an injected prompt — can never use
 * up its owner's upload quota. No derived blob counts toward a file-count
 * quota. A limit of zero or less disables that limit.
 *
 * @since 6.8.0
 */
public final class DerivedCaps {

    /** Default of {@code eddi.attachments.derived.max-per-source}. */
    public static final long DEFAULT_MAX_PER_SOURCE = 200;
    /** Default of {@code eddi.attachments.derived.max-total-bytes-per-source}. */
    public static final long DEFAULT_MAX_TOTAL_BYTES_PER_SOURCE = 52_428_800L;
    /** Default of {@code eddi.attachments.derived.max-slices-per-source}. */
    public static final long DEFAULT_MAX_SLICES_PER_SOURCE = 20;
    /** Default of {@code eddi.attachments.derived.max-slice-bytes-per-source}. */
    public static final long DEFAULT_MAX_SLICE_BYTES_PER_SOURCE = 52_428_800L;
    /** Default of {@code eddi.attachments.derived.max-text-bytes-per-source}. */
    public static final long DEFAULT_MAX_TEXT_BYTES_PER_SOURCE = 209_715_200L;

    /** Which budget a kind draws on. */
    public enum Category {
        MEDIA, TEXT, SLICE
    }

    /** The five limits, as the operator configured them. */
    public record Limits(long maxPerSource, long maxTotalBytesPerSource, long maxSlicesPerSource, long maxSliceBytesPerSource,
            long maxTextBytesPerSource) {

        /** The built-in defaults. */
        public static Limits defaults() {
            return new Limits(DEFAULT_MAX_PER_SOURCE, DEFAULT_MAX_TOTAL_BYTES_PER_SOURCE, DEFAULT_MAX_SLICES_PER_SOURCE,
                    DEFAULT_MAX_SLICE_BYTES_PER_SOURCE, DEFAULT_MAX_TEXT_BYTES_PER_SOURCE);
        }
    }

    /**
     * One derived blob already in the root's lineage.
     *
     * @param lastAccessedAt
     *            when it was last loaded or written; {@code null} counts as the
     *            oldest
     */
    public record Existing(String storageRef, Kind kind, long sizeBytes, Instant lastAccessedAt) {
    }

    private DerivedCaps() {
        // utility class
    }

    /** The budget {@code kind} draws on. */
    public static Category categoryOf(Kind kind) {
        return switch (kind) {
            case IMAGE, TABLE, AUDIO, EMBEDDED -> Category.MEDIA;
            case PDF_SLICE -> Category.SLICE;
            case MANIFEST, MANIFEST_PART, TEXT_PART, SUMMARY, DESCRIPTION -> Category.TEXT;
        };
    }

    /**
     * Whether a derived blob of this kind counts toward its owner's <em>byte</em>
     * quota. Only media do; no derived blob counts toward a file-count quota.
     */
    public static boolean countsTowardOwnerQuota(Kind kind) {
        return kind != null && categoryOf(kind) == Category.MEDIA;
    }

    /**
     * Checks one more derived blob against the root's caps.
     *
     * @param incoming
     *            the kind about to be stored
     * @param incomingBytes
     *            its size
     * @param lineage
     *            every derived blob the root has now
     * @return the slices to evict before storing it, least recently accessed first;
     *         empty for every other kind
     * @throws AttachmentDerivedCapException
     *             if it does not fit, naming the cap
     */
    public static List<String> check(Kind incoming, long incomingBytes, List<Existing> lineage, Limits limits)
            throws AttachmentDerivedCapException {
        Category category = categoryOf(incoming);
        long count = 0;
        long bytes = 0;
        List<Existing> sameCategory = new ArrayList<>();
        for (Existing existing : lineage) {
            if (existing.kind() != null && categoryOf(existing.kind()) == category) {
                count++;
                bytes += existing.sizeBytes();
                sameCategory.add(existing);
            }
        }
        return switch (category) {
            case MEDIA -> {
                if (limits.maxPerSource() > 0 && count >= limits.maxPerSource()) {
                    throw new AttachmentDerivedCapException("max-per-source",
                            "This file already has %d extracted images, tables, audio and embedded files (limit %d)."
                                    .formatted(count, limits.maxPerSource()));
                }
                requireBytes("max-total-bytes-per-source", bytes, incomingBytes, limits.maxTotalBytesPerSource());
                yield List.of();
            }
            case TEXT -> {
                requireBytes("max-text-bytes-per-source", bytes, incomingBytes, limits.maxTextBytesPerSource());
                yield List.of();
            }
            case SLICE -> evictions(sameCategory, incomingBytes, limits);
        };
    }

    private static void requireBytes(String cap, long used, long incoming, long limit) throws AttachmentDerivedCapException {
        if (limit > 0 && used + incoming > limit) {
            throw new AttachmentDerivedCapException(cap,
                    "Derived files of this file would exceed %s: %d + %d bytes, limit %d.".formatted(cap, used, incoming, limit));
        }
    }

    /** Least recently accessed slices first, until both slice limits hold. */
    private static List<String> evictions(List<Existing> slices, long incomingBytes, Limits limits)
            throws AttachmentDerivedCapException {
        if (limits.maxSliceBytesPerSource() > 0 && incomingBytes > limits.maxSliceBytesPerSource()) {
            throw new AttachmentDerivedCapException("max-slice-bytes-per-source",
                    "A slice of %d bytes exceeds max-slice-bytes-per-source (%d) on its own."
                            .formatted(incomingBytes, limits.maxSliceBytesPerSource()));
        }
        List<Existing> oldestFirst = new ArrayList<>(slices);
        oldestFirst.sort(Comparator.comparing(Existing::lastAccessedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Existing::storageRef, Comparator.nullsFirst(Comparator.naturalOrder())));
        long count = oldestFirst.size();
        long bytes = oldestFirst.stream().mapToLong(Existing::sizeBytes).sum();
        List<String> evict = new ArrayList<>();
        for (Existing slice : oldestFirst) {
            boolean countFits = limits.maxSlicesPerSource() <= 0 || count + 1 <= limits.maxSlicesPerSource();
            boolean bytesFit = limits.maxSliceBytesPerSource() <= 0 || bytes + incomingBytes <= limits.maxSliceBytesPerSource();
            if (countFits && bytesFit) {
                break;
            }
            evict.add(slice.storageRef());
            count--;
            bytes -= slice.sizeBytes();
        }
        return List.copyOf(evict);
    }
}
