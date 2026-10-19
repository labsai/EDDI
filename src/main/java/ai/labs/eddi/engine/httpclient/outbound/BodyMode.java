/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * How a response body is read, and the most of it that may be read. Every mode
 * but {@link #discard()} carries a cap enforced <em>while</em> reading: an
 * oversized body is never buffered whole and then measured.
 *
 * @param <T>
 *            what {@link OutboundResponse#body()} holds
 */
public final class BodyMode<T> {

    /** What to do when the body reaches the cap or the read budget runs out. */
    public enum OnOverflow {
        /**
         * Keep what was read and mark the response
         * {@link OutboundResponse#truncated()}. For a page or a search result, where a
         * prefix is still useful.
         */
        TRUNCATE,
        /**
         * Throw {@link BodyTooLargeException} (cap) or {@link OutboundTimeoutException}
         * (budget). For a body that is useless unless whole, such as JSON.
         */
        FAIL
    }

    enum Kind {
        STRING, BYTES, FILE, STREAM, DISCARD
    }

    private final Kind kind;
    private final long maxBytes;
    private final OnOverflow onOverflow;
    private final Path target;
    private final Duration readBudget;

    private BodyMode(Kind kind, long maxBytes, OnOverflow onOverflow, Path target, Duration readBudget) {
        this.kind = kind;
        this.maxBytes = maxBytes;
        this.onOverflow = onOverflow;
        this.target = target;
        this.readBudget = readBudget;
    }

    /**
     * The body as text, decoded with the {@code charset} of its
     * {@code Content-Type}, UTF-8 when it names none.
     */
    public static BodyMode<String> string(long maxBytes, OnOverflow onOverflow) {
        return new BodyMode<>(Kind.STRING, positive(maxBytes), Objects.requireNonNull(onOverflow), null, null);
    }

    public static BodyMode<byte[]> bytes(long maxBytes, OnOverflow onOverflow) {
        return new BodyMode<>(Kind.BYTES, positive(maxBytes), Objects.requireNonNull(onOverflow), null, null);
    }

    /**
     * The body written to {@code target}, which is replaced. Over the cap the call
     * fails and the partial file is deleted.
     */
    public static BodyMode<Path> file(Path target, long maxBytes) {
        return new BodyMode<>(Kind.FILE, positive(maxBytes), OnOverflow.FAIL, Objects.requireNonNull(target), null);
    }

    /**
     * The body as a stream, for a caller that processes it incrementally. The
     * caller must close it. Reading past {@code maxBytes} throws
     * {@link BodyTooLargeException}; once {@code readBudget} has elapsed the stream
     * is closed and further reads throw {@link OutboundTimeoutException}.
     *
     * @param maxBytes
     *            the cap; non-positive means none, leaving the caller to bound the
     *            read itself
     * @param readBudget
     *            the time the whole read may take, from when the headers arrived;
     *            {@code null} means none, leaving the caller to bound it
     */
    public static BodyMode<InputStream> stream(long maxBytes, Duration readBudget) {
        return new BodyMode<>(Kind.STREAM, maxBytes, OnOverflow.FAIL, null, readBudget);
    }

    /** No body: the connection is closed without reading it. */
    public static BodyMode<Void> discard() {
        return new BodyMode<>(Kind.DISCARD, 0, OnOverflow.FAIL, null, null);
    }

    Kind kind() {
        return kind;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public OnOverflow onOverflow() {
        return onOverflow;
    }

    Path target() {
        return target;
    }

    Duration readBudget() {
        return readBudget;
    }

    private static long positive(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive, was " + maxBytes);
        }
        return maxBytes;
    }
}
