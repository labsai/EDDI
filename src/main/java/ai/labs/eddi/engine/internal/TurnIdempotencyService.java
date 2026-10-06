/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Idempotent turns: the same {@code Idempotency-Key} on the same conversation
 * is one turn, however many times the request arrives.
 *
 * <p>
 * A caller whose own timeout is shorter than a turn gives up and sends the
 * request again. Without this the second request meets {@code 409 IN_PROGRESS},
 * and the first turn's answer — stored, but never seen — is lost. With a key it
 * is served instead:
 * </p>
 * <ol>
 * <li>the same key while the turn <b>runs</b>: wait for it (up to the caller's
 * budget) and answer with its result;</li>
 * <li>the same key shortly after it <b>completed</b> (a grace window on this
 * node, then the persisted step): the stored result, without a second run;</li>
 * <li>a different key, or none: an ordinary turn.</li>
 * </ol>
 *
 * <h2>Where the state lives</h2>
 * <ul>
 * <li><b>Persisted</b> — the key is stored on the turn's step
 * ({@value #STEP_KEY}), so a restart, or another node, still recognises
 * "already done" for as long as the step is among the conversation's latest and
 * the TTL has not passed. The stored result is the step itself, which is
 * persisted anyway.</li>
 * <li><b>In memory, this node only</b> — a map of in-flight keys to the future
 * of their turn, so a duplicate can <em>wait</em>, plus a short-lived copy of
 * the finished snapshot that bridges the moment between "the answer was handed
 * over" and "the step was persisted". Behind a NATS coordinator with several
 * nodes, a duplicate that lands on a different node than the running turn
 * cannot wait for it (and meets today's {@code 409}); once the turn has
 * completed the persisted lookup works on every node.</li>
 * </ul>
 *
 * <p>
 * Keys are scoped to the conversation — the same key on two conversations is
 * two different turns. A turn that <em>failed</em> is never remembered:
 * retrying a failed request with the same key must run it again, or the retry
 * the error invites would be useless.
 * </p>
 */
@ApplicationScoped
public class TurnIdempotencyService {

    private static final Logger LOGGER = Logger.getLogger(TurnIdempotencyService.class);

    /** The step-data key under which a completed turn records its key. */
    public static final String STEP_KEY = "idempotency:key";

    /** Longest accepted key; longer is a 400. */
    public static final int MAX_KEY_LENGTH = 128;

    /** How many of the conversation's latest steps a persisted lookup inspects. */
    static final int MAX_STEPS_SCANNED = 50;

    /** How long a finished snapshot stays on this node to bridge the persist. */
    private static final Duration RECENT_GRACE = Duration.ofSeconds(30);

    private static final int MAX_TRACKED = 10_000;
    private static final int MAX_RECENT = 500;

    /** What a finished turn handed its caller. */
    public record Outcome(SimpleConversationMemorySnapshot snapshot, boolean skipped) {
    }

    /** How a request relates to turns already seen with its key. */
    public enum Kind {
        /** First sight: run the turn, then {@code complete} or {@code abandon}. */
        OWNER,
        /** The turn is running: wait on {@link Admission#future()}. */
        WAIT,
        /** The turn finished: answer with {@link Admission#outcome()}. */
        REPLAY
    }

    /**
     * @param kind
     *            what the caller should do
     * @param future
     *            the running turn's result (OWNER: the one to complete; WAIT: the
     *            one to wait on)
     * @param outcome
     *            the stored result, for REPLAY
     */
    public record Admission(Kind kind, CompletableFuture<Outcome> future, Outcome outcome) {
    }

    /**
     * Looks the key up in the persisted conversation; empty when no turn holds it.
     */
    @FunctionalInterface
    public interface PersistedLookup {
        Outcome find() throws Exception;
    }

    private final Cache<String, CompletableFuture<Outcome>> inFlight;
    private final Cache<String, Outcome> recent;
    private final long ttlMs;
    private final long maxWaitMs;
    private final LongSupplier clock;
    private final MeterRegistry meterRegistry;

    @Inject
    public TurnIdempotencyService(
            @ConfigProperty(name = "eddi.turns.idempotency.ttl-seconds", defaultValue = "600") long ttlSeconds,
            @ConfigProperty(name = "systemRuntime.agentTimeoutInSeconds") int agentTimeoutSeconds,
            MeterRegistry meterRegistry) {
        this(ttlSeconds, agentTimeoutSeconds, meterRegistry, System::currentTimeMillis);
    }

    TurnIdempotencyService(long ttlSeconds, int agentTimeoutSeconds, MeterRegistry meterRegistry, LongSupplier clock) {
        this.meterRegistry = meterRegistry;
        this.ttlMs = Math.max(0, ttlSeconds) * 1000L;
        this.maxWaitMs = Math.max(1, agentTimeoutSeconds) * 1000L;
        this.clock = clock;
        // An owner whose turn never reaches a handler (the watchdog abandons it) would
        // otherwise leave its entry behind forever; the watchdog fires at the agent
        // timeout, so an entry older than that is dead.
        this.inFlight = Caffeine.newBuilder().maximumSize(MAX_TRACKED)
                .expireAfterWrite(Duration.ofMillis(maxWaitMs + 30_000L)).build();
        this.recent = Caffeine.newBuilder().maximumSize(MAX_RECENT).expireAfterWrite(RECENT_GRACE).build();
    }

    /** Whether the feature is on: a TTL of zero switches it off. */
    public boolean isEnabled() {
        return ttlMs > 0;
    }

    /**
     * Longest a duplicate waits for the running turn: the caller's own budget (the
     * {@code X-EDDI-Turn-Deadline-Ms} header) if it gave one, never more than the
     * agent timeout — the running turn is itself bounded by it.
     */
    public long waitBudgetMs(Long requestedDeadlineMs) {
        return requestedDeadlineMs != null && requestedDeadlineMs > 0 ? Math.min(requestedDeadlineMs, maxWaitMs) : maxWaitMs;
    }

    /**
     * Decides what to do with a request that carries {@code key}.
     * <p>
     * The order matters: in flight, then the on-node grace copy, then the persisted
     * step, and only then registration as the owner. A finishing turn fills the
     * grace copy <em>before</em> it leaves the in-flight map, so a duplicate that
     * misses the second always finds the first.
     */
    public Admission admit(String conversationId, String key, PersistedLookup persisted) throws Exception {
        String id = scoped(conversationId, key);

        CompletableFuture<Outcome> running = inFlight.getIfPresent(id);
        if (running != null) {
            return new Admission(Kind.WAIT, running, null);
        }
        Outcome finished = recent.getIfPresent(id);
        if (finished != null) {
            return new Admission(Kind.REPLAY, null, finished);
        }
        Outcome stored = persisted.find();
        if (stored != null) {
            return new Admission(Kind.REPLAY, null, stored);
        }

        CompletableFuture<Outcome> mine = new CompletableFuture<>();
        CompletableFuture<Outcome> raced = inFlight.asMap().putIfAbsent(id, mine);
        return raced != null ? new Admission(Kind.WAIT, raced, null) : new Admission(Kind.OWNER, mine, null);
    }

    /** Receives the result a request is answered with. */
    public interface SnapshotSink {
        void done(SimpleConversationMemorySnapshot snapshot);

        /** The turn was dropped without consuming the input (409 on REST). */
        void skipped(SimpleConversationMemorySnapshot snapshot);
    }

    /** The conversation as it stands now, converted for the caller. */
    @FunctionalInterface
    public interface CurrentSnapshot {
        SimpleConversationMemorySnapshot get() throws Exception;
    }

    /** Runs the request again from the top. */
    @FunctionalInterface
    public interface Retry {
        void run() throws Exception;
    }

    /**
     * Admits a keyed request and, unless it is the one to run the turn, answers it.
     * <p>
     * A duplicate of a <b>running</b> turn is answered when that turn finishes —
     * asynchronously, so no request thread is parked for the length of a model call
     * — or, once {@code requestedDeadlineMs} (capped at the agent timeout) has
     * passed, with the conversation as it then stands (an {@code IN_PROGRESS}
     * snapshot, i.e. today's {@code 409 retry shortly}). If the running turn is
     * refused before it ever ran, the duplicate becomes the owner and runs it
     * itself via {@code retry}.
     *
     * @return {@code true} if the caller owns the key and must now run the turn and
     *         report it through {@link #complete} / {@link #abandon}; {@code false}
     *         if the request has been (or will be) answered through {@code sink}
     */
    public boolean admitOrServe(String conversationId, String key, Long requestedDeadlineMs, PersistedLookup lookup, SnapshotSink sink,
                                CurrentSnapshot current, Retry retry)
            throws Exception {
        Admission admission = admit(conversationId, key, lookup);
        switch (admission.kind()) {
            case OWNER -> {
                return true;
            }
            case REPLAY -> {
                count("replayed");
                deliver(sink, admission.outcome());
                return false;
            }
            default -> {
                admission.future().copy().orTimeout(waitBudgetMs(requestedDeadlineMs), TimeUnit.MILLISECONDS).whenComplete((outcome, failure) -> {
                    try {
                        if (failure == null) {
                            count("waited");
                            deliver(sink, outcome);
                        } else if (failure instanceof TimeoutException) {
                            count("wait_timeout");
                            sink.skipped(current.get());
                        } else {
                            // The running turn was refused before it ran: nothing was
                            // consumed, so this request is simply the first one now.
                            retry.run();
                        }
                    } catch (Exception e) {
                        LOGGER.warnf("Could not answer a duplicate of turn %s: %s", conversationId, e.getMessage());
                    }
                });
                return false;
            }
        }
    }

    private static void deliver(SnapshotSink sink, Outcome outcome) {
        if (outcome.skipped()) {
            sink.skipped(outcome.snapshot());
        } else {
            sink.done(outcome.snapshot());
        }
    }

    private void count(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.turn.idempotency", "outcome", outcome).increment();
        }
    }

    /**
     * The owner's turn finished: wake the waiters and, for a result worth
     * replaying, keep it for the grace window.
     */
    public void complete(String conversationId, String key, Outcome outcome) {
        String id = scoped(conversationId, key);
        if (!outcome.skipped() && isReplayable(outcome.snapshot())) {
            recent.put(id, outcome);
        }
        CompletableFuture<Outcome> mine = inFlight.asMap().remove(id);
        if (mine != null) {
            mine.complete(outcome);
        }
    }

    /**
     * The owner's turn never produced a result (it was refused before running):
     * release the key so the next request can run it, and tell the waiters.
     */
    public void abandon(String conversationId, String key, Throwable cause) {
        CompletableFuture<Outcome> mine = inFlight.asMap().remove(scoped(conversationId, key));
        if (mine != null) {
            mine.completeExceptionally(cause);
        }
    }

    /**
     * A result is replayable when the turn settled. A failed or interrupted turn is
     * not: the caller is being told to retry it, and a replay would hand the same
     * failure back for ten minutes.
     */
    public static boolean isReplayable(SimpleConversationMemorySnapshot snapshot) {
        ConversationState state = snapshot == null ? null : snapshot.getConversationState();
        return state == ConversationState.READY || state == ConversationState.ENDED || state == ConversationState.AWAITING_HUMAN;
    }

    /**
     * The index of the newest of the conversation's last steps that recorded
     * {@code key} no longer than the TTL ago, or {@code -1}.
     */
    public int findStoredStep(ConversationMemorySnapshot snapshot, String key) {
        List<ConversationStepSnapshot> steps = snapshot.getConversationSteps();
        if (steps == null || ttlMs <= 0) {
            return -1;
        }
        long now = clock.getAsLong();
        for (int i = steps.size() - 1, scanned = 0; i >= 0 && scanned < MAX_STEPS_SCANNED; i--, scanned++) {
            Date recordedAt = recordedAt(steps.get(i), key);
            if (recordedAt != null) {
                // The newest holder decides: an expired one means a new turn, and the
                // older ones are older still.
                return now - recordedAt.getTime() <= ttlMs ? i : -1;
            }
        }
        return -1;
    }

    private static Date recordedAt(ConversationStepSnapshot step, String key) {
        if (step == null || step.getWorkflows() == null) {
            return null;
        }
        for (WorkflowRunSnapshot workflow : step.getWorkflows()) {
            if (workflow == null || workflow.getLifecycleTasks() == null) {
                continue;
            }
            for (ResultSnapshot result : workflow.getLifecycleTasks()) {
                if (result != null && STEP_KEY.equals(result.getKey()) && key.equals(result.getResult())) {
                    return result.getTimestamp() != null ? result.getTimestamp() : new Date(0);
                }
            }
        }
        return null;
    }

    private static String scoped(String conversationId, String key) {
        // Keys are printable ASCII with no control characters, so the newline cannot
        // occur in either part and the pair cannot collide.
        return conversationId + '\n' + key;
    }

    /**
     * Checks a caller-supplied key: 1 to {@value #MAX_KEY_LENGTH} printable ASCII
     * characters (no spaces at the ends, no control characters). Returns it, or
     * {@code null} when {@code raw} is {@code null}.
     *
     * @throws InvalidIdempotencyKeyException
     *             when the key is blank, too long or not printable
     */
    public static String validateKey(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank()) {
            throw new InvalidIdempotencyKeyException("The idempotency key must not be blank");
        }
        if (raw.length() > MAX_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException("The idempotency key is longer than " + MAX_KEY_LENGTH + " characters");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                throw new InvalidIdempotencyKeyException("The idempotency key may only contain printable ASCII characters");
            }
        }
        return raw.trim();
    }

    /** A malformed {@code Idempotency-Key}; the REST layer answers 400. */
    public static class InvalidIdempotencyKeyException extends RuntimeException {
        public InvalidIdempotencyKeyException(String message) {
            super(message);
        }
    }

    /** Test hook: how many turns are currently tracked as running. */
    int inFlightCount() {
        ConcurrentMap<String, CompletableFuture<Outcome>> map = inFlight.asMap();
        return map.size();
    }
}
