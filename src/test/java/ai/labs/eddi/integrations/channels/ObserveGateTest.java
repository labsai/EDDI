/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.ObserveConfig;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The gate that lets a passive observer answer a message it was not addressed
 * in — and, far more often, stops it.
 */
@DisplayName("ObserveGate")
class ObserveGateTest {

    private static final String CHANNEL = "C123";
    private static final Instant T0 = Instant.parse("2026-06-01T12:00:00Z");

    private final ConcurrentHashMap<String, Object> cacheMap = new ConcurrentHashMap<>();
    /**
     * Run inside every cache read. Empty by default; the concurrency test uses it
     * to hold two threads inside their read until both have taken one, which is the
     * interleaving a read-then-book implementation loses to.
     */
    private final AtomicReference<Runnable> readBarrier = new AtomicReference<>(() -> {
    });
    private MutableClock clock;
    private ObserveGate gate;

    /** A clock the test moves, so cooldowns and day rollovers are assertable. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setUp() {
        cacheMap.clear();
        readBarrier.set(() -> {
        });
        ICache mockCache = mock(ICache.class);
        when(mockCache.get(any())).thenAnswer(inv -> {
            // Captured BEFORE the barrier, deliberately. Held first, the barrier
            // releases both threads but does not pin what either of them read:
            // one could finish its whole reservation before the other's `get`
            // ran, and a read-then-book implementation would then grant exactly
            // one too and pass. Measured at 4 runs in 40.
            Object value = cacheMap.get(inv.getArgument(0));
            readBarrier.get().run();
            return value;
        });
        when(mockCache.putIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> cacheMap.putIfAbsent(inv.getArgument(0), inv.getArgument(1)));
        when(mockCache.replace(anyString(), any(), any(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> cacheMap.replace(inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2)));

        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString())).thenReturn(mockCache);

        clock = new MutableClock(T0);
        gate = new ObserveGate(cacheFactory, new SimpleMeterRegistry(), clock);
    }

    private static ChannelTarget observer(String name, ObserveConfig config) {
        var target = new ChannelTarget();
        target.setName(name);
        target.setTargetId("agent-" + name);
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setObserveMode(true);
        target.setObserveConfig(config);
        return target;
    }

    private static ObserveConfig config() {
        return new ObserveConfig();
    }

    /**
     * Drives the production decision path. {@code reserve} BOOKS the reply it
     * grants, so a granted call here is also the reply the next assertion is
     * limited by — which is the point: the rate-limit tests used to drive a
     * read-only `evaluate` plus a separate `recordResponse`, and a regression
     * confined to the reservation loop left every one of them green.
     */
    private ObserveGate.Verdict reserve(ChannelTarget target, String text) {
        return gate.reserve("slack", CHANNEL, target, text, List.of());
    }

    // ─── Triggers ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("trigger matching")
    class Triggers {

        @Test
        @DisplayName("a keyword matches case-insensitively, anywhere in the message")
        void keywordMatches() {
            var config = config();
            config.setTriggerKeywords(List.of("incident"));
            var target = observer("ops", config);

            assertTrue(reserve(target, "we have an INCIDENT in prod").respond());
            assertEquals(ObserveGate.Reason.NO_TRIGGER,
                    reserve(target, "lunch plans?").reason());
        }

        @Test
        @DisplayName("no keywords and no MIME types means watch everything")
        void watchesEverythingWhenUnconfigured() {
            // Documented on the model, and the reason the cooldown and both caps
            // are not optional.
            assertTrue(reserve(observer("all", config()), "anything at all").respond());
        }

        @Test
        @DisplayName("a MIME type matches, ignoring the charset the platform appends")
        void mimeTypeMatches() {
            var config = config();
            config.setTriggerMimeTypes(List.of("application/pdf"));
            // Reserving books a reply, and these are two calls against one target.
            config.setCooldownSeconds(0);
            var target = observer("docs", config);

            assertTrue(gate.reserve("slack", CHANNEL, target, "", List.of("application/pdf; charset=binary"))
                    .respond());
            assertEquals(ObserveGate.Reason.NO_TRIGGER,
                    gate.reserve("slack", CHANNEL, target, "", List.of("image/png")).reason());
        }

        @Test
        @DisplayName("either a keyword or a MIME type is enough when both are configured")
        void keywordOrMimeType() {
            var config = config();
            config.setTriggerKeywords(List.of("review"));
            config.setTriggerMimeTypes(List.of("application/pdf"));
            config.setCooldownSeconds(0);
            var target = observer("docs", config);

            assertTrue(reserve(target, "please review this").respond());
            assertTrue(gate.reserve("slack", CHANNEL, target, "here", List.of("application/pdf"))
                    .respond());
            assertFalse(gate.reserve("slack", CHANNEL, target, "here", List.of("image/png"))
                    .respond());
        }

        @Test
        @DisplayName("a target that is not an observer never matches")
        void nonObserverNeverMatches() {
            var target = observer("ops", config());
            target.setObserveMode(false);
            assertEquals(ObserveGate.Reason.NOT_OBSERVING, reserve(target, "anything").reason());
        }
    }

    // ─── Rate limits ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rate limits")
    class RateLimits {

        @Test
        @DisplayName("the cooldown blocks a second reply, and expires")
        void cooldown() {
            var config = config();
            config.setCooldownSeconds(60);
            var target = observer("ops", config);

            assertTrue(reserve(target, "one").respond());

            clock.advance(Duration.ofSeconds(59));
            assertEquals(ObserveGate.Reason.COOLDOWN, reserve(target, "two").reason());

            clock.advance(Duration.ofSeconds(2));
            assertTrue(reserve(target, "three").respond());
        }

        @Test
        @DisplayName("the daily count is a hard stop")
        void dailyResponseCap() {
            var config = config();
            config.setCooldownSeconds(0);
            config.setMaxDailyResponses(2);
            var target = observer("ops", config);

            for (int i = 0; i < 2; i++) {
                assertTrue(reserve(target, "msg").respond());
            }
            assertEquals(ObserveGate.Reason.DAILY_RESPONSE_CAP, reserve(target, "msg").reason());
        }

        @Test
        @DisplayName("the daily budget is a hard stop once spent")
        void dailyCostCap() {
            var config = config();
            config.setCooldownSeconds(0);
            config.setMaxCostPerDay(1.0);
            var target = observer("ops", config);

            assertTrue(reserve(target, "first").respond());
            gate.addCost("slack", CHANNEL, target, 0.75);
            assertTrue(reserve(target, "still under").respond());

            gate.addCost("slack", CHANNEL, target, 0.30);
            assertEquals(ObserveGate.Reason.DAILY_COST_CAP, reserve(target, "over").reason());
        }

        @Test
        @DisplayName("a new UTC day restores the allowance but not the cooldown")
        void dayRollover() {
            var config = config();
            config.setCooldownSeconds(3600);
            config.setMaxDailyResponses(1);
            var target = observer("ops", config);

            assertTrue(reserve(target, "one").respond());
            gate.addCost("slack", CHANNEL, target, 5.0);
            assertFalse(reserve(target, "two").respond());

            // Just past midnight UTC, 12 hours later: the day is new, but the
            // cooldown was set 12 hours ago and has genuinely elapsed.
            clock.advance(Duration.ofHours(12).plusMinutes(1));
            assertTrue(reserve(target, "next day").respond());
        }

        @Test
        @DisplayName("midnight does not license an immediate second reply")
        void cooldownSurvivesMidnight() {
            var config = config();
            config.setCooldownSeconds(3600);
            var target = observer("ops", config);

            // 23:59:30 UTC, then thirty seconds later — a new day, same minute.
            clock.advance(Duration.ofHours(11).plusMinutes(59).plusSeconds(30));
            assertTrue(reserve(target, "late").respond());

            clock.advance(Duration.ofSeconds(31));
            assertEquals(ObserveGate.Reason.COOLDOWN, reserve(target, "just after midnight").reason());
        }

        @Test
        @DisplayName("limits are per channel, so one busy channel does not silence another")
        void limitsArePerChannel() {
            var config = config();
            config.setCooldownSeconds(3600);
            var target = observer("ops", config);

            assertTrue(reserve(target, "here first").respond());
            assertEquals(ObserveGate.Reason.COOLDOWN, reserve(target, "here").reason());
            assertTrue(gate.reserve("slack", "C999", target, "elsewhere", List.of()).respond());
        }

        @Test
        @DisplayName("an observer that did not match is never reported as rate-limited")
        void triggerIsCheckedFirst() {
            // Order matters for the operator reading the metric: a non-match must
            // not look like a cooldown, or a quiet observer reads as a throttled one.
            var config = config();
            config.setCooldownSeconds(3600);
            config.setTriggerKeywords(List.of("incident"));
            var target = observer("ops", config);

            assertTrue(reserve(target, "an incident").respond());
            assertEquals(ObserveGate.Reason.NO_TRIGGER, reserve(target, "lunch?").reason());
        }

        @Test
        @DisplayName("an observer saved without a config still gets the defaults")
        void missingConfigStillGuarded() {
            var target = observer("ops", null);
            assertTrue(reserve(target, "one").respond());
            // The ObserveConfig default cooldown is 60s, so this is inside it.
            clock.advance(Duration.ofSeconds(5));
            assertEquals(ObserveGate.Reason.COOLDOWN, reserve(target, "two").reason());
        }
    }

    // ─── Selection ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("selecting among several observers")
    class Selection {

        @Test
        @DisplayName("the first matching observer answers, in configuration order")
        void firstMatchWins() {
            var first = config();
            first.setTriggerKeywords(List.of("deploy"));
            var second = config();
            second.setTriggerKeywords(List.of("deploy"));

            var match = gate.select("slack", CHANNEL,
                    List.of(observer("a", first), observer("b", second)), "deploy now", List.of());

            assertTrue(match.isPresent());
            assertEquals("a", match.get().target().getName());
        }

        @Test
        @DisplayName("an observer that does not match is skipped, not counted")
        void nonMatchingIsSkipped() {
            var first = config();
            first.setTriggerKeywords(List.of("never"));
            var second = config();
            second.setTriggerKeywords(List.of("deploy"));

            var match = gate.select("slack", CHANNEL,
                    List.of(observer("a", first), observer("b", second)), "deploy now", List.of());

            assertEquals("b", match.orElseThrow().target().getName());
        }

        @Test
        @DisplayName("a throttled observer suppresses the message rather than passing it on")
        void throttledObserverStopsTheSearch() {
            // Otherwise a second watcher would answer precisely because the first
            // one was rate-limited, which is the opposite of what a cooldown means.
            var first = config();
            first.setCooldownSeconds(3600);
            first.setTriggerKeywords(List.of("deploy"));
            var second = config();
            second.setTriggerKeywords(List.of("deploy"));

            var a = observer("a", first);
            assertTrue(gate.reserve("slack", CHANNEL, a, "deploy now", List.of()).respond());

            var match = gate.select("slack", CHANNEL, List.of(a, observer("b", second)),
                    "deploy now", List.of());
            assertTrue(match.isEmpty());
        }

        @Test
        @DisplayName("selecting books the reply, so a second message sees it")
        void selectReserves() {
            // `select` has to book what it grants, or two messages arriving
            // together are both told there is room for one more.
            var config = config();
            config.setCooldownSeconds(3600);
            var target = observer("ops", config);

            assertTrue(gate.select("slack", CHANNEL, List.of(target), "one", List.of()).isPresent());
            assertTrue(gate.select("slack", CHANNEL, List.of(target), "two", List.of()).isEmpty());
        }

        @Test
        @DisplayName("of two selections that read the same window, only one is granted")
        void concurrentSelectionsCannotBothWin() throws Exception {
            // The race is read-then-book: two callers read "0 replies used" and
            // both conclude there is room. Left to chance it almost never
            // reproduces — the cache double serializes each individual call — so
            // the interleaving is forced: both threads are held inside their
            // first cache read until the other has read too, which is exactly
            // the window the reservation has to close.
            var config = config();
            config.setCooldownSeconds(0);
            config.setMaxDailyResponses(1);
            var target = observer("ops", config);

            var bothHaveRead = new CyclicBarrier(2);
            var firstReads = new AtomicInteger();
            readBarrier.set(() -> {
                if (firstReads.getAndIncrement() < 2) {
                    try {
                        bothHaveRead.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            });

            var granted = new AtomicInteger();
            var threads = new ArrayList<Thread>();
            for (int i = 0; i < 2; i++) {
                Thread thread = new Thread(() -> {
                    if (gate.select("slack", CHANNEL, List.of(target), "go", List.of()).isPresent()) {
                        granted.incrementAndGet();
                    }
                });
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join(10_000);
            }

            assertEquals(1, granted.get(),
                    "both callers read the same window, so exactly one may be granted the only slot");
        }

        @Test
        @DisplayName("no observers means no decision")
        void noCandidates() {
            assertTrue(gate.select("slack", CHANNEL, List.of(), "anything", List.of()).isEmpty());
            assertTrue(gate.select("slack", CHANNEL, null, "anything", List.of()).isEmpty());
        }
    }

    // ─── Accounting ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("accounting")
    class Accounting {

        @Test
        @DisplayName("cost is added without consuming another reply")
        void addCostDoesNotConsumeAReply() {
            // The two are known at different moments: the reply is committed before
            // the turn runs, its cost only exists afterwards.
            var config = config();
            config.setCooldownSeconds(0);
            config.setMaxDailyResponses(2);
            var target = observer("ops", config);

            assertTrue(reserve(target, "first").respond());
            gate.addCost("slack", CHANNEL, target, 0.1);
            gate.addCost("slack", CHANNEL, target, 0.1);

            assertTrue(reserve(target, "second reply still available").respond());
        }

        @Test
        @DisplayName("a nonsense cost is ignored rather than poisoning the window")
        void ignoresNonFiniteCost() {
            var target = observer("ops", config());
            assertTrue(reserve(target, "first").respond());
            gate.addCost("slack", CHANNEL, target, Double.NaN);
            gate.addCost("slack", CHANNEL, target, Double.POSITIVE_INFINITY);
            gate.addCost("slack", CHANNEL, target, -5.0);

            clock.advance(Duration.ofHours(1));
            assertTrue(reserve(target, "budget intact").respond());
        }

        @Test
        @DisplayName("recording against a null target is a no-op, not a crash")
        void nullTargetIsSafe() {
            assertDoesNotThrow(() -> gate.addCost("slack", CHANNEL, null, 1.0));
            assertEquals(ObserveGate.Reason.NOT_OBSERVING,
                    gate.reserve("slack", CHANNEL, null, "x", List.of()).reason());
        }
    }
}
