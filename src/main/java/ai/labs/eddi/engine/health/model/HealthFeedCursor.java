/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Where the outcome evaluation's incremental feed stands, persisted between
 * passes. Both positions are a pair in the feed's order (a conversation's
 * newest capture, then its id): {@code (at, null)} means everything at
 * {@code at} is still to do, {@code (at, id)} everything after that pair.
 *
 * @param watermark
 *            the forward feed: everything before ({@code watermark},
 *            {@code afterId}) has been read once — never {@code null}
 * @param afterId
 *            the last conversation read at {@code watermark}, or {@code null}
 * @param replayAt
 *            where the re-read of the overlap behind the watermark resumes (a
 *            pass ran out of its share of it, or a failure is retried from
 *            there), or {@code null}: the next pass starts it afresh at the
 *            watermark minus the overlap
 * @param replayAfterId
 *            the last conversation re-read at {@code replayAt}, or {@code null}
 */
public record HealthFeedCursor(Instant watermark, String afterId, Instant replayAt, String replayAfterId) {

    public HealthFeedCursor {
        Objects.requireNonNull(watermark, "watermark");
        if (replayAt == null) {
            replayAfterId = null;
        }
    }

    /** Only a watermark: nothing read at it yet, no replay pending. */
    public static HealthFeedCursor at(Instant watermark) {
        return new HealthFeedCursor(watermark, null, null, null);
    }
}
