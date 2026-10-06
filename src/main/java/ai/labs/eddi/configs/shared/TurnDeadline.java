/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import org.jboss.logging.Logger;

import java.time.Clock;

/**
 * The wall-clock budget of one conversation turn: an absolute deadline plus the
 * reserve kept back for producing a fallback answer and persisting the turn.
 * <p>
 * Every layer that can spend time on a turn (model retries, cascade steps, HTTP
 * calls and their retries) asks this object how much is left instead of
 * applying its own configured timeouts in isolation, so a turn ends inside the
 * caller's time budget however many retry layers are stacked.
 * <p>
 * Immutable and thread-safe. It is carried on the conversation memory as a
 * <em>transient</em> value (never persisted) — it describes this request, not
 * the conversation.
 * <p>
 * <b>Where the budget comes from.</b> The agent's {@code turnDeadlineMs} and
 * the caller's {@code X-EDDI-Turn-Deadline-Ms} header; see
 * {@link #resolveBudgetMs(Long, Long)}.
 *
 * @since 6.6.0
 */
public final class TurnDeadline {
    private static final Logger LOGGER = Logger.getLogger(TurnDeadline.class);

    /** Request header a caller sets to tell the engine how long it will wait. */
    public static final String HEADER = "X-EDDI-Turn-Deadline-Ms";

    /** Reserve kept for the fallback path and persistence when none is set. */
    public static final long DEFAULT_RESERVE_MS = 1500L;

    /**
     * Shortest model attempt worth starting. A call that cannot be given this long
     * would only burn budget and time out.
     */
    public static final long MIN_ATTEMPT_MS = 3000L;

    /**
     * Engine ceiling on a requested budget: a header alone can enable a deadline,
     * but not an unreasonable one.
     */
    public static final long MAX_BUDGET_MS = 600_000L;

    /** Ceiling on the reserve, so it can never swallow a normal budget. */
    public static final long MAX_RESERVE_MS = 60_000L;

    private final Clock clock;
    private final long deadlineEpochMs;
    private final long reserveMs;

    private TurnDeadline(Clock clock, long deadlineEpochMs, long reserveMs) {
        this.clock = clock;
        this.deadlineEpochMs = deadlineEpochMs;
        this.reserveMs = reserveMs;
    }

    /**
     * @param clock
     *            time source (a mutable clock in tests)
     * @param startEpochMs
     *            when the turn started — the request's arrival
     * @param budgetMs
     *            how long the turn may take from {@code startEpochMs}
     * @param reserveMs
     *            kept back for the fallback answer; {@code null} or negative means
     *            {@link #DEFAULT_RESERVE_MS}
     */
    public static TurnDeadline of(Clock clock, long startEpochMs, long budgetMs, Long reserveMs) {
        long reserve = reserveMs == null || reserveMs < 0 ? DEFAULT_RESERVE_MS : Math.min(reserveMs, MAX_RESERVE_MS);
        return new TurnDeadline(clock, startEpochMs + budgetMs, reserve);
    }

    /**
     * The effective turn budget: the agent's configured value capped by the
     * caller's header, or either alone. {@code null} means no deadline (today's
     * behaviour).
     * <p>
     * <b>Decision:</b> a header alone <em>does</em> enable a deadline when the
     * agent configures none — the caller knows how long it will wait, the agent
     * designer may not. It is capped at {@link #MAX_BUDGET_MS}. When the agent does
     * configure a value, the header can only shorten it.
     *
     * @param configuredMs
     *            the agent's {@code turnDeadlineMs} (null/non-positive = unset)
     * @param requestedMs
     *            the parsed header value (null/non-positive = absent)
     */
    public static Long resolveBudgetMs(Long configuredMs, Long requestedMs) {
        Long configured = configuredMs != null && configuredMs > 0 ? Math.min(configuredMs, MAX_BUDGET_MS) : null;
        Long requested = requestedMs != null && requestedMs > 0 ? Math.min(requestedMs, MAX_BUDGET_MS) : null;
        if (configured == null) {
            return requested;
        }
        return requested == null ? configured : Long.valueOf(Math.min(configured, requested));
    }

    /**
     * The deadline for a turn, or {@code null} when neither the agent nor the
     * caller asks for one.
     *
     * @param arrivalEpochMs
     *            when the request arrived — queue time counts against the caller's
     *            patience, so the clock starts here rather than when the turn
     *            starts
     */
    public static TurnDeadline forTurn(Clock clock, long arrivalEpochMs, Long configuredMs, Long reserveMs, Long requestedMs) {
        Long budget = resolveBudgetMs(configuredMs, requestedMs);
        return budget == null ? null : of(clock, arrivalEpochMs, budget, reserveMs);
    }

    /**
     * Parses the {@value #HEADER} value. Anything that is not a positive whole
     * number of milliseconds is ignored (a request never fails over this hint),
     * with a debug line.
     *
     * @return the milliseconds, or {@code null} when absent or unusable
     */
    public static Long parseHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // handled below
        }
        LOGGER.debugf("Ignoring unusable %s header value (expected a positive number of milliseconds)", HEADER);
        return null;
    }

    /** Milliseconds until the deadline; negative once it has passed. */
    public long remainingMs() {
        return deadlineEpochMs - clock.millis();
    }

    /** What is left once the reserve is set aside; negative when it is not. */
    public long remainingAfterReserveMs() {
        return remainingMs() - reserveMs;
    }

    public long reserveMs() {
        return reserveMs;
    }

    public boolean isExpired() {
        return remainingMs() <= 0;
    }

    /**
     * Whether an attempt needing at least {@code minAttemptMs} fits, with the
     * reserve left over.
     */
    public boolean canFit(long minAttemptMs) {
        return remainingAfterReserveMs() >= minAttemptMs;
    }

    @Override
    public String toString() {
        return "TurnDeadline{remainingMs=" + remainingMs() + ", reserveMs=" + reserveMs + "}";
    }
}
