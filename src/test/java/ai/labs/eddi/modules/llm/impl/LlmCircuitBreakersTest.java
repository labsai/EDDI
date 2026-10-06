/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.FailureClass;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.Trigger;
import ai.labs.eddi.modules.llm.impl.LlmCircuitBreakers.Failure;
import ai.labs.eddi.modules.llm.impl.LlmCircuitBreakers.Key;
import ai.labs.eddi.modules.llm.impl.LlmCircuitBreakers.State;
import ai.labs.eddi.modules.llm.impl.LlmCircuitBreakers.Ticket;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LlmCircuitBreakers (R8)")
class LlmCircuitBreakersTest {

    private static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final Key MODEL = new Key(AGENT, 1, "openai", "gpt-small");

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private LlmCircuitBreakers breakers;

    @BeforeEach
    void setUp() {
        breakers = new LlmCircuitBreakers(meters, now::get);
    }

    private static CircuitBreakerConfig config(int window, int threshold, long coolDownMs) {
        var c = new CircuitBreakerConfig();
        c.setEnabled(true);
        c.setWindow(window);
        c.setThreshold(threshold);
        c.setCoolDownMs(coolDownMs);
        return c;
    }

    private static CircuitBreakerConfig defaults() {
        var c = new CircuitBreakerConfig();
        c.setEnabled(true);
        return c;
    }

    private void fail(Key key, CircuitBreakerConfig cfg, Failure failure) {
        Ticket t = breakers.acquire(key, cfg);
        assertTrue(t.allowed(), "the breaker must still let this turn through");
        t.failure(failure, "sample");
    }

    private void succeed(Key key, CircuitBreakerConfig cfg) {
        Ticket t = breakers.acquire(key, cfg);
        assertTrue(t.allowed());
        t.success();
    }

    @Test
    @DisplayName("defaults: 8 of 10, 60 s, and disabled unless asked for")
    void defaultsAndDisabled() {
        var c = new CircuitBreakerConfig();
        assertFalse(c.isEnabled());
        assertEquals(10, c.effectiveWindow());
        assertEquals(8, c.effectiveThreshold());
        assertEquals(60_000L, c.effectiveCoolDownMs());

        assertSame(Ticket.DISABLED, breakers.acquire(MODEL, null));
        assertSame(Ticket.DISABLED, breakers.acquire(MODEL, c));
        for (int i = 0; i < 50; i++) {
            breakers.acquire(MODEL, c).failure(Failure.AUTH, "x");
        }
        assertEquals(State.CLOSED, breakers.stateOf(MODEL));
    }

    @Test
    @DisplayName("out-of-range settings are clamped, never rejected")
    void settingsAreClamped() {
        var c = config(0, 99, -5);
        assertEquals(1, c.effectiveWindow());
        assertEquals(1, c.effectiveThreshold());
        assertEquals(0L, c.effectiveCoolDownMs());
        c = config(5_000, 5_000, Long.MAX_VALUE);
        assertEquals(CircuitBreakerConfig.MAX_WINDOW, c.effectiveWindow());
        assertEquals(CircuitBreakerConfig.MAX_WINDOW, c.effectiveThreshold());
        assertEquals(CircuitBreakerConfig.MAX_COOL_DOWN_MS, c.effectiveCoolDownMs());
    }

    @Test
    @DisplayName("trips when 8 of the last 10 turns failed with the same class, not at 7")
    void tripsAfterNOfM() {
        var cfg = defaults();
        for (int i = 0; i < 7; i++) {
            fail(MODEL, cfg, Failure.INVALID_OUTPUT);
        }
        assertEquals(State.CLOSED, breakers.stateOf(MODEL));
        fail(MODEL, cfg, Failure.INVALID_OUTPUT);
        assertEquals(State.OPEN, breakers.stateOf(MODEL));
        assertFalse(breakers.acquire(MODEL, cfg).allowed());
    }

    @Test
    @DisplayName("successes in the window keep it closed: 7 failures + 3 successes never trip")
    void successesDilute() {
        var cfg = defaults();
        for (int i = 0; i < 3; i++) {
            succeed(MODEL, cfg);
        }
        for (int i = 0; i < 7; i++) {
            fail(MODEL, cfg, Failure.BAD_REQUEST);
        }
        assertEquals(State.CLOSED, breakers.stateOf(MODEL));
        // the window slides: the next failure pushes one success out, 8 of 10 now
        fail(MODEL, cfg, Failure.BAD_REQUEST);
        assertEquals(State.OPEN, breakers.stateOf(MODEL));
    }

    @Test
    @DisplayName("old failures slide out of the window")
    void windowSlides() {
        var cfg = config(4, 3, 60_000);
        fail(MODEL, cfg, Failure.MODEL_NOT_FOUND);
        fail(MODEL, cfg, Failure.MODEL_NOT_FOUND);
        succeed(MODEL, cfg);
        succeed(MODEL, cfg);
        succeed(MODEL, cfg);
        // the two failures have slid out of the last 4; one more is 1 of 4
        fail(MODEL, cfg, Failure.MODEL_NOT_FOUND);
        assertEquals(State.CLOSED, breakers.stateOf(MODEL));
    }

    @Test
    @DisplayName("the threshold counts one class: 4 bad requests + 4 invalid outputs do not trip 8-of-10")
    void classesAreNotPooled() {
        var cfg = defaults();
        for (int i = 0; i < 4; i++) {
            fail(MODEL, cfg, Failure.BAD_REQUEST);
            fail(MODEL, cfg, Failure.INVALID_OUTPUT);
        }
        assertEquals(State.CLOSED, breakers.stateOf(MODEL));
    }

    @Test
    @DisplayName("AUTH and QUOTA_EXHAUSTED trip on the first occurrence")
    void authAndQuotaTripImmediately() {
        var cfg = defaults();
        fail(MODEL, cfg, Failure.AUTH);
        assertEquals(State.OPEN, breakers.stateOf(MODEL));

        var other = new Key(AGENT, 1, "openai", "other");
        fail(other, cfg, Failure.QUOTA_EXHAUSTED);
        assertEquals(State.OPEN, breakers.stateOf(other));
    }

    @Test
    @DisplayName("only AUTH, QUOTA, BAD_REQUEST and MODEL_NOT_FOUND are the breaker's business")
    void onlyPermanentClassesCount() {
        assertEquals(Failure.AUTH, Failure.of(FailureClass.AUTH));
        assertEquals(Failure.QUOTA_EXHAUSTED, Failure.of(FailureClass.QUOTA_EXHAUSTED));
        assertEquals(Failure.BAD_REQUEST, Failure.of(FailureClass.BAD_REQUEST));
        assertEquals(Failure.MODEL_NOT_FOUND, Failure.of(FailureClass.MODEL_NOT_FOUND));
        for (FailureClass transientClass : new FailureClass[]{FailureClass.TRANSIENT, FailureClass.RATE_LIMITED, FailureClass.TIMEOUT,
                FailureClass.CONTEXT_TOO_LONG, FailureClass.UNKNOWN}) {
            assertNull(Failure.of(transientClass), transientClass + " must not be counted");
        }
    }

    @Test
    @DisplayName("released turns (transient failures, pauses) neither fill nor drain the window")
    void releasedTurnsAreNotRecorded() {
        var cfg = defaults();
        for (int i = 0; i < 7; i++) {
            fail(MODEL, cfg, Failure.INVALID_OUTPUT);
        }
        for (int i = 0; i < 100; i++) {
            breakers.acquire(MODEL, cfg).release();
        }
        assertEquals(State.CLOSED, breakers.stateOf(MODEL));
        assertEquals(7, breakers.tally(MODEL).get(Failure.INVALID_OUTPUT));
        fail(MODEL, cfg, Failure.INVALID_OUTPUT);
        assertEquals(State.OPEN, breakers.stateOf(MODEL), "100 released turns must not have pushed the failures out");
    }

    @Test
    @DisplayName("open: denied with EDDI's own reason until the cool-down ends; the denial names class and time left")
    void openDeniesUntilCoolDown() {
        var cfg = config(10, 8, 60_000);
        fail(MODEL, cfg, Failure.AUTH);

        now.addAndGet(20_000);
        Ticket denied = breakers.acquire(MODEL, cfg);
        assertFalse(denied.allowed());
        assertEquals(Failure.AUTH, denied.denial().failure());
        assertEquals(40_000L, denied.denial().retryInMs());
        assertEquals("sample", denied.denial().reason());
        assertTrue(denied.denial().message(MODEL).contains("gpt-small"));
        assertEquals(1.0, meters.find("eddi.llm.circuit.skipped").tag("class", "AUTH").counter().count());
    }

    @Test
    @DisplayName("half-open lets exactly ONE probe through; a concurrent turn is denied")
    void halfOpenSingleProbe() {
        var cfg = config(10, 8, 60_000);
        fail(MODEL, cfg, Failure.AUTH);
        now.addAndGet(60_000);

        Ticket probe = breakers.acquire(MODEL, cfg);
        assertTrue(probe.allowed());
        assertTrue(probe.isProbe());
        assertEquals(State.HALF_OPEN, breakers.stateOf(MODEL));

        assertFalse(breakers.acquire(MODEL, cfg).allowed(), "only one probe at a time");
        assertFalse(breakers.acquire(MODEL, cfg).allowed());
    }

    @Test
    @DisplayName("a successful probe closes the breaker and clears the window")
    void probeSuccessCloses() {
        var cfg = config(10, 8, 60_000);
        fail(MODEL, cfg, Failure.AUTH);
        now.addAndGet(60_000);

        breakers.acquire(MODEL, cfg).success();

        assertEquals(State.CLOSED, breakers.stateOf(MODEL));
        assertTrue(breakers.acquire(MODEL, cfg).allowed());
        assertTrue(breakers.tally(MODEL).isEmpty(), "old failures must not count towards the next trip");
        assertEquals(1.0, meters.find("eddi.llm.circuit").tag("state", "closed").counter().count());
    }

    @Test
    @DisplayName("a failing probe re-opens it for a whole new cool-down")
    void probeFailureReopens() {
        var cfg = config(10, 8, 60_000);
        fail(MODEL, cfg, Failure.AUTH);
        now.addAndGet(60_000);

        breakers.acquire(MODEL, cfg).failure(Failure.AUTH, "still bad");

        assertEquals(State.OPEN, breakers.stateOf(MODEL));
        now.addAndGet(59_999);
        assertFalse(breakers.acquire(MODEL, cfg).allowed());
        now.addAndGet(1);
        assertTrue(breakers.acquire(MODEL, cfg).isProbe(), "the next probe after the second cool-down");
        assertEquals(2.0, meters.find("eddi.llm.circuit").tag("state", "open").counter().count());
    }

    @Test
    @DisplayName("a probe that ends uncounted (a transient error) is released: the next turn probes")
    void releasedProbeHandsOn() {
        var cfg = config(10, 8, 60_000);
        fail(MODEL, cfg, Failure.AUTH);
        now.addAndGet(60_000);

        breakers.acquire(MODEL, cfg).release();

        assertEquals(State.HALF_OPEN, breakers.stateOf(MODEL));
        assertTrue(breakers.acquire(MODEL, cfg).isProbe());
    }

    @Test
    @DisplayName("a probe whose result never arrives is abandoned after one more cool-down")
    void abandonedProbeExpires() {
        var cfg = config(10, 8, 60_000);
        fail(MODEL, cfg, Failure.AUTH);
        now.addAndGet(60_000);
        assertTrue(breakers.acquire(MODEL, cfg).isProbe());

        now.addAndGet(59_999);
        assertFalse(breakers.acquire(MODEL, cfg).allowed());
        now.addAndGet(1);
        assertTrue(breakers.acquire(MODEL, cfg).isProbe());
    }

    @Test
    @DisplayName("a late result from an abandoned probe cannot close, re-open or release the breaker for its replacement")
    void abandonedProbeCannotDecideForItsReplacement() {
        var cfg = config(10, 8, 60_000);
        fail(MODEL, cfg, Failure.AUTH);
        now.addAndGet(60_000);
        Ticket probeA = breakers.acquire(MODEL, cfg);
        now.addAndGet(60_000);
        Ticket probeB = breakers.acquire(MODEL, cfg);
        assertTrue(probeB.isProbe());

        probeA.success();
        assertEquals(State.HALF_OPEN, breakers.stateOf(MODEL));
        probeA.release();
        assertFalse(breakers.acquire(MODEL, cfg).allowed(), "B is still in flight: no third probe");

        probeB.success();
        assertEquals(State.CLOSED, breakers.stateOf(MODEL));
    }

    @Test
    @DisplayName("per model, per agent version and per agent: one breaker opening leaves the others closed")
    void keysAreIsolated() {
        var cfg = defaults();
        fail(MODEL, cfg, Failure.AUTH);

        assertTrue(breakers.acquire(new Key(AGENT, 1, "openai", "gpt-large"), cfg).allowed(), "another model of the same agent");
        assertTrue(breakers.acquire(new Key(AGENT, 2, "openai", "gpt-small"), cfg).allowed(), "another agent version");
        assertTrue(breakers.acquire(new Key("bbbbbbbbbbbbbbbbbbbbbbbb", 1, "openai", "gpt-small"), cfg).allowed(), "another agent");
        assertTrue(breakers.acquire(new Key(AGENT, 1, "anthropic", "gpt-small"), cfg).allowed(), "another provider");
        assertFalse(breakers.acquire(MODEL, cfg).allowed());
    }

    @Test
    @DisplayName("a late result from a turn that started before the breaker opened cannot move an open breaker")
    void lateResultsAreIgnored() {
        var cfg = config(10, 8, 60_000);
        Ticket inFlight = breakers.acquire(MODEL, cfg);
        fail(MODEL, cfg, Failure.AUTH);
        assertEquals(State.OPEN, breakers.stateOf(MODEL));

        inFlight.success();

        assertEquals(State.OPEN, breakers.stateOf(MODEL));
    }

    @Test
    @DisplayName("a ticket settles once; later settles are ignored")
    void ticketSettlesOnce() {
        var cfg = defaults();
        Ticket t = breakers.acquire(MODEL, cfg);
        t.failure(Failure.AUTH, "first");
        t.success();
        t.failure(Failure.BAD_REQUEST, "again");
        assertEquals(State.OPEN, breakers.stateOf(MODEL));
        assertEquals(1.0, meters.find("eddi.llm.circuit").tag("state", "open").counter().count());
    }

    @Test
    @DisplayName("re-ask gate: refused while open or half-open FOR INVALID OUTPUT, open for other triggers and other trip classes")
    void reaskGate() {
        var cfg = config(10, 8, 60_000);
        Ticket closed = breakers.acquire(MODEL, cfg);
        assertTrue(closed.reaskAllowed(Trigger.INVALID_JSON));

        for (int i = 0; i < 8; i++) {
            fail(MODEL, cfg, Failure.INVALID_OUTPUT);
        }
        assertFalse(closed.reaskAllowed(Trigger.INVALID_JSON), "open for invalid output");
        assertFalse(closed.reaskAllowed(Trigger.SCHEMA_MISMATCH));
        assertTrue(closed.reaskAllowed(Trigger.EMPTY), "an empty reply is not an invalid-output trip");
        assertTrue(closed.reaskAllowed(Trigger.TRUNCATION));

        now.addAndGet(60_000);
        Ticket probe = breakers.acquire(MODEL, cfg);
        assertTrue(probe.isProbe());
        assertFalse(probe.reaskAllowed(Trigger.INVALID_JSON), "the probe is one trial, not one with re-asks");

        var authModel = new Key(AGENT, 1, "openai", "auth-model");
        Ticket live = breakers.acquire(authModel, cfg);
        fail(authModel, cfg, Failure.AUTH);
        assertTrue(live.reaskAllowed(Trigger.INVALID_JSON), "opened for AUTH, not for invalid output");

        assertTrue(Ticket.DISABLED.reaskAllowed(Trigger.INVALID_JSON));
    }

    @Test
    @DisplayName("transitions are counted by state and class; the gauge counts circuits not closed")
    void metrics() {
        var cfg = config(10, 8, 60_000);
        fail(MODEL, cfg, Failure.AUTH);
        assertEquals(1.0, meters.find("eddi.llm.circuit").tag("state", "open").tag("class", "AUTH").counter().count());
        assertEquals(1.0, meters.find("eddi.llm.circuit.open").gauge().value());

        now.addAndGet(60_000);
        Ticket probe = breakers.acquire(MODEL, cfg);
        assertEquals(1.0, meters.find("eddi.llm.circuit").tag("state", "half_open").counter().count());
        assertEquals(1.0, meters.find("eddi.llm.circuit.open").gauge().value(), "half-open still counts");

        probe.success();
        assertEquals(0.0, meters.find("eddi.llm.circuit.open").gauge().value());
        assertNotNull(meters.find("eddi.llm.circuit").tag("state", "closed").counter());
    }
}
