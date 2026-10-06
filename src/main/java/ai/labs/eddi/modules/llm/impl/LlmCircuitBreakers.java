/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.FailureClass;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.Trigger;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CircuitBreakerConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * R8: the circuit breakers of LLM models, one per (agent, agent version,
 * provider, model).
 *
 * <h2>Why a new class</h2> The two existing breakers
 * ({@code McpToolProviderManager}, {@code A2AToolProviderManager}) are private
 * fields of those managers: a consecutive-failure counter per server URL with
 * no half-open state, no probe and no per-class accounting. An LLM model needs
 * "N of the last M turns failed the same way" and a single concurrent probe, so
 * those two could not be generalised without rewriting both; this class is the
 * reusable one for LLM models.
 *
 * <h2>What counts</h2> Only failures that will fail the same way again:
 * {@link Failure#INVALID_OUTPUT} (still unusable after the re-asks),
 * {@link Failure#BAD_REQUEST} and {@link Failure#MODEL_NOT_FOUND} trip the
 * breaker when {@code threshold} of the last {@code window} counted turns share
 * the class; {@link Failure#AUTH} and {@link Failure#QUOTA_EXHAUSTED} trip it
 * at once. Transient, timeout and rate-limit failures, and everything else, are
 * <em>not recorded</em> — they neither fill nor drain the window.
 *
 * <h2>States</h2> {@code CLOSED} (every turn is let through), {@code OPEN}
 * (turns are denied for the cool-down) and {@code HALF_OPEN} (after the
 * cool-down exactly one probe turn is let through; its success closes the
 * breaker, its failure re-opens it, a probe that ends in an uncounted outcome
 * is released so the next turn probes). A probe whose result never arrives is
 * abandoned after one more cool-down.
 *
 * <h2>Use</h2> {@link #acquire} hands out a {@link Ticket}; the caller runs the
 * model and settles the ticket exactly once ({@link Ticket#success()},
 * {@link Ticket#failure}, {@link Ticket#release()}). A disabled breaker returns
 * {@link Ticket#DISABLED}, whose methods do nothing — callers need no
 * {@code if (enabled)}.
 *
 * <h2>Scope</h2> In memory and per node, bounded ({@value #MAX_BREAKERS}
 * breakers) and expiring when idle for {@link #IDLE_EXPIRY}. A node restart or
 * an idle expiry resets a breaker to closed — the worst case is one more window
 * of failures before it trips again.
 */
@ApplicationScoped
public class LlmCircuitBreakers {

    private static final Logger LOGGER = Logger.getLogger(LlmCircuitBreakers.class);

    static final int MAX_BREAKERS = 10_000;
    static final Duration IDLE_EXPIRY = Duration.ofHours(1);

    /** What one counted turn ended in. */
    public enum Failure {
        /** The reply was still invalid JSON or off-shape after the re-asks. */
        INVALID_OUTPUT(false), BAD_REQUEST(false), MODEL_NOT_FOUND(false),
        /** Trips at once. */
        AUTH(true),
        /** Trips at once. */
        QUOTA_EXHAUSTED(true);

        private final boolean immediate;

        Failure(boolean immediate) {
            this.immediate = immediate;
        }

        /** Whether one occurrence trips the breaker. */
        public boolean isImmediate() {
            return immediate;
        }

        /**
         * The counted failure for a classified one, or null when it is not the
         * breaker's business (transient, timeout, rate limit, context too long,
         * unknown): those are retried or escalated, never counted.
         */
        public static Failure of(FailureClass failureClass) {
            if (failureClass == null) {
                return null;
            }
            return switch (failureClass) {
                case AUTH -> AUTH;
                case QUOTA_EXHAUSTED -> QUOTA_EXHAUSTED;
                case BAD_REQUEST -> BAD_REQUEST;
                case MODEL_NOT_FOUND -> MODEL_NOT_FOUND;
                default -> null;
            };
        }

        /** The failure class the user-facing error carries. */
        public String label() {
            return name();
        }
    }

    /** The breaker's state, as exposed to the metric and the logs. */
    public enum State {
        CLOSED("closed"), OPEN("open"), HALF_OPEN("half_open");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** What {@link #acquire} decided. */
    public enum Permit {
        ALLOW, PROBE, DENY
    }

    /** A breaker's identity. The model is the name it was built with. */
    public record Key(String agentId, Integer agentVersion, String provider, String model) {
        String describe() {
            return "agent=" + agentId + " version=" + agentVersion + " provider=" + provider + " model=" + model;
        }
    }

    /** The tunables of one acquire; clamped by {@link CircuitBreakerConfig}. */
    public record Settings(int window, int threshold, long coolDownMs) {
        public static Settings from(CircuitBreakerConfig config) {
            return new Settings(config.effectiveWindow(), config.effectiveThreshold(), config.effectiveCoolDownMs());
        }
    }

    /**
     * Why a turn was denied, for the {@code LifecycleException} and the trace.
     * EDDI-generated text only.
     */
    public record Denial(Failure failure, String reason, long retryInMs) {
        public String message(Key key) {
            return "The circuit for model '" + key.model() + "' (" + key.provider() + ") is open after repeated " + failure.label()
                    + " failures (" + reason + "); it is skipped for another " + retryInMs + " ms";
        }
    }

    /** One counted turn; a null failure is a success. */
    private record Entry(Failure failure) {
    }

    /** One breaker's mutable state; every method is synchronised on it. */
    private static final class Breaker {
        private final Key key;
        private final ArrayDeque<Entry> window = new ArrayDeque<>();
        private State state = State.CLOSED;
        private long openedAt;
        private long probeStartedAt = -1;
        private Failure tripClass;
        private String tripReason;

        Breaker(Key key) {
            this.key = key;
        }
    }

    private final Cache<Key, Breaker> breakers = Caffeine.newBuilder().maximumSize(MAX_BREAKERS).expireAfterAccess(IDLE_EXPIRY).build();
    private final LongSupplier clock;
    private final MeterRegistry meterRegistry;

    @Inject
    public LlmCircuitBreakers(MeterRegistry meterRegistry) {
        this(meterRegistry, System::currentTimeMillis);
    }

    LlmCircuitBreakers(MeterRegistry meterRegistry, LongSupplier clock) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        if (meterRegistry != null) {
            Gauge.builder("eddi.llm.circuit.open", this, LlmCircuitBreakers::notClosedCount)
                    .description("LLM model circuits currently open or half-open")
                    .register(meterRegistry);
        }
    }

    // ------------------------------------------------------------ acquire

    /**
     * Asks whether the model may be called. Returns {@link Ticket#DISABLED} when
     * {@code config} is null or not enabled.
     */
    public Ticket acquire(Key key, CircuitBreakerConfig config) {
        if (config == null || !config.isEnabled() || key == null) {
            return Ticket.DISABLED;
        }
        Settings settings = Settings.from(config);
        Breaker breaker = breakers.get(key, Breaker::new);
        long now = clock.getAsLong();
        synchronized (breaker) {
            switch (breaker.state) {
                case CLOSED :
                    return new Ticket(this, breaker, settings, Permit.ALLOW, null);
                case OPEN :
                    long waited = now - breaker.openedAt;
                    if (waited < settings.coolDownMs()) {
                        return denied(breaker, settings.coolDownMs() - waited);
                    }
                    transition(breaker, State.HALF_OPEN, breaker.tripClass, breaker.tripReason, settings, now);
                    breaker.probeStartedAt = now;
                    return new Ticket(this, breaker, settings, Permit.PROBE, null);
                default :
                    boolean probing = breaker.probeStartedAt >= 0 && now - breaker.probeStartedAt < settings.coolDownMs();
                    if (probing) {
                        return denied(breaker, settings.coolDownMs() - (now - breaker.probeStartedAt));
                    }
                    breaker.probeStartedAt = now;
                    return new Ticket(this, breaker, settings, Permit.PROBE, null);
            }
        }
    }

    private Ticket denied(Breaker breaker, long retryInMs) {
        count("eddi.llm.circuit.skipped", breaker.tripClass);
        return new Ticket(this, breaker, null, Permit.DENY, new Denial(breaker.tripClass, breaker.tripReason, Math.max(0, retryInMs)));
    }

    /** The state of a model's breaker, CLOSED when it has none. For tests/ops. */
    public State stateOf(Key key) {
        Breaker breaker = breakers.getIfPresent(key);
        if (breaker == null) {
            return State.CLOSED;
        }
        synchronized (breaker) {
            return breaker.state;
        }
    }

    // ------------------------------------------------------------ settle

    private void settle(Breaker breaker, Settings settings, Permit permit, Failure failure, String reason) {
        long now = clock.getAsLong();
        synchronized (breaker) {
            if (permit == Permit.PROBE) {
                if (breaker.state != State.HALF_OPEN) {
                    return;
                }
                breaker.probeStartedAt = -1;
                if (failure == null) {
                    breaker.window.clear();
                    transition(breaker, State.CLOSED, null, null, settings, now);
                } else {
                    breaker.openedAt = now;
                    transition(breaker, State.OPEN, failure, reason, settings, now);
                }
                return;
            }
            // A turn that started before the breaker opened: its late result must not
            // move an open or half-open breaker; only the probe decides those.
            if (breaker.state != State.CLOSED) {
                return;
            }
            addOutcome(breaker, failure, settings.window());
            if (failure == null) {
                return;
            }
            if (failure.isImmediate() || countOf(breaker, failure) >= settings.threshold()) {
                breaker.openedAt = now;
                transition(breaker, State.OPEN, failure, reason, settings, now);
            }
        }
    }

    private static void addOutcome(Breaker breaker, Failure failure, int window) {
        breaker.window.addLast(new Entry(failure));
        while (breaker.window.size() > window) {
            breaker.window.removeFirst();
        }
    }

    private static int countOf(Breaker breaker, Failure failure) {
        int n = 0;
        for (Entry entry : breaker.window) {
            if (entry.failure() == failure) {
                n++;
            }
        }
        return n;
    }

    private void release(Breaker breaker, Permit permit) {
        if (permit != Permit.PROBE) {
            return;
        }
        synchronized (breaker) {
            if (breaker.state == State.HALF_OPEN) {
                breaker.probeStartedAt = -1;
            }
        }
    }

    private void transition(Breaker breaker, State to, Failure failure, String reason, Settings settings, long now) {
        State from = breaker.state;
        breaker.state = to;
        if (to == State.OPEN) {
            breaker.tripClass = failure;
            breaker.tripReason = reason;
            breaker.window.clear();
        } else if (to == State.CLOSED) {
            breaker.tripClass = null;
            breaker.tripReason = null;
        }
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.llm.circuit", "state", to.label(), "class", failure != null ? failure.label() : "none").increment();
        }
        String detail = breaker.key.describe() + " class=" + (failure != null ? failure.label() : "none") + " reason=\"" + reason + "\"";
        if (to == State.OPEN) {
            // The alert: an operator needs to know a model has just been taken out.
            LOGGER.errorf("LLM circuit OPEN (was %s) %s coolDownMs=%d — the model is skipped until the cool-down ends", from.label(), detail,
                    settings.coolDownMs());
        } else if (to == State.HALF_OPEN) {
            LOGGER.infof("LLM circuit HALF_OPEN %s — one probe turn is let through", detail);
        } else {
            LOGGER.infof("LLM circuit CLOSED (was %s) %s — the probe turn succeeded", from.label(), breaker.key.describe());
        }
    }

    private void count(String name, Failure failure) {
        if (meterRegistry != null) {
            meterRegistry.counter(name, "class", failure != null ? failure.label() : "none").increment();
        }
    }

    private double notClosedCount() {
        long n = 0;
        for (Breaker breaker : breakers.asMap().values()) {
            synchronized (breaker) {
                if (breaker.state != State.CLOSED) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Per-class tally of the current window; for tests. */
    Map<Failure, Integer> tally(Key key) {
        Map<Failure, Integer> out = new EnumMap<>(Failure.class);
        Breaker breaker = breakers.getIfPresent(key);
        if (breaker != null) {
            synchronized (breaker) {
                for (Failure f : Failure.values()) {
                    int n = countOf(breaker, f);
                    if (n > 0) {
                        out.put(f, n);
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------ ticket

    /**
     * Permission to call a model, settled exactly once with the turn's outcome
     * (later settles are ignored). {@link #DISABLED} is the no-op ticket.
     */
    public static final class Ticket {
        /** Allows everything, records nothing. */
        public static final Ticket DISABLED = new Ticket(null, null, null, Permit.ALLOW, null);

        private final LlmCircuitBreakers owner;
        private final Breaker breaker;
        private final Settings settings;
        private final Permit permit;
        private final Denial denial;
        private final AtomicBoolean settled = new AtomicBoolean();

        private Ticket(LlmCircuitBreakers owner, Breaker breaker, Settings settings, Permit permit, Denial denial) {
            this.owner = owner;
            this.breaker = breaker;
            this.settings = settings;
            this.permit = permit;
            this.denial = denial;
        }

        /** False when the model must not be called. */
        public boolean allowed() {
            return permit != Permit.DENY;
        }

        /** Why it was denied; null when {@link #allowed()}. */
        public Denial denial() {
            return denial;
        }

        /** Whether this turn is the half-open probe. */
        public boolean isProbe() {
            return permit == Permit.PROBE;
        }

        /** The turn succeeded (a usable reply, whatever its confidence). */
        public void success() {
            settle(null, null);
        }

        /** The turn failed in a way the breaker counts. */
        public void failure(Failure failure, String reason) {
            settle(failure, reason);
        }

        /**
         * The turn ended in something the breaker does not count (a transient error, a
         * pause, a cancel): nothing is recorded, and a probe is handed on.
         */
        public void release() {
            if (breaker != null && permit != Permit.DENY && settled.compareAndSet(false, true)) {
                owner.release(breaker, permit);
            }
        }

        private void settle(Failure failure, String reason) {
            if (breaker != null && permit != Permit.DENY && settled.compareAndSet(false, true)) {
                owner.settle(breaker, settings, permit, failure, reason);
            }
        }

        /**
         * The R5 gate: false when this model's breaker is open or half-open for invalid
         * output, so a same-model re-ask of an invalid-JSON / off-shape reply would
         * only burn the turn's time — the caller escalates at once. The probe turn is
         * among those: it is a single trial, not one with re-asks.
         */
        public boolean reaskAllowed(Trigger trigger) {
            if (breaker == null || (trigger != Trigger.INVALID_JSON && trigger != Trigger.SCHEMA_MISMATCH)) {
                return true;
            }
            synchronized (breaker) {
                return breaker.state == State.CLOSED || breaker.tripClass != Failure.INVALID_OUTPUT;
            }
        }
    }
}
