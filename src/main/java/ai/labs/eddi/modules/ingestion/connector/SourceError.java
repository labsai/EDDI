/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import java.time.Duration;
import java.util.Optional;

/**
 * What went wrong reading a source, classified by what it means for the
 * knowledge base.
 *
 * <p>
 * The one distinction the framework cannot do without is
 * {@link #isDefinitive()}: whether the failure answered the question "is this
 * document still there and readable?". A 404 did; a 503, a rate limit or a
 * revoked credential did not. Counting the second kind as absence deletes
 * documents an outage merely hid — so a connector that cannot tell reports a
 * non-definitive kind.
 */
public sealed interface SourceError {

    /** The classification, for metrics, logs and switch statements. */
    enum Kind {
        TRANSIENT, THROTTLED, AUTH, NOT_FOUND, PERMANENT, CURSOR_INVALID
    }

    /** Human-readable; may reach run history, so never a credential. */
    String reason();

    Kind kind();

    /**
     * Whether the failure is an answer about the document — it is gone, or it can
     * never be read as it is — rather than a failure to look.
     */
    default boolean isDefinitive() {
        return false;
    }

    /** When the provider said to try again, if it did. */
    default Optional<Duration> retryAfter() {
        return Optional.empty();
    }

    /** A failure worth retrying: a timeout, a 5xx, a dropped connection. */
    record Transient(String reason, Duration retryAfterHint) implements SourceError {
        public Transient(String reason) {
            this(reason, null);
        }

        @Override
        public Kind kind() {
            return Kind.TRANSIENT;
        }

        @Override
        public Optional<Duration> retryAfter() {
            return Optional.ofNullable(retryAfterHint);
        }
    }

    /** The provider is rate-limiting: back off, then retry. */
    record Throttled(String reason, Duration retryAfterHint) implements SourceError {
        public Throttled(String reason) {
            this(reason, null);
        }

        @Override
        public Kind kind() {
            return Kind.THROTTLED;
        }

        @Override
        public Optional<Duration> retryAfter() {
            return Optional.ofNullable(retryAfterHint);
        }
    }

    /**
     * The credential was refused (401, 403, a revoked grant). Says nothing about
     * the document: only that it could not be looked at.
     */
    record Auth(String reason) implements SourceError {
        @Override
        public Kind kind() {
            return Kind.AUTH;
        }
    }

    /** The document or partition does not exist (any more). */
    record NotFound(String reason) implements SourceError {
        @Override
        public Kind kind() {
            return Kind.NOT_FOUND;
        }

        @Override
        public boolean isDefinitive() {
            return true;
        }
    }

    /**
     * Will fail the same way every time: a request the provider rejects as
     * malformed, a format that cannot be exported.
     */
    record Permanent(String reason) implements SourceError {
        @Override
        public Kind kind() {
            return Kind.PERMANENT;
        }

        @Override
        public boolean isDefinitive() {
            return true;
        }
    }

    /**
     * The stored cursor is no longer accepted (an expired change token, a changed
     * {@code UIDVALIDITY}). Normal, not an operator problem: the cursor is cleared
     * and the next run lists the partition in full.
     */
    record CursorInvalid(String reason) implements SourceError {
        @Override
        public Kind kind() {
            return Kind.CURSOR_INVALID;
        }
    }
}
