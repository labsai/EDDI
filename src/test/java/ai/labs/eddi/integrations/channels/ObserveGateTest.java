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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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
        ICache mockCache = mock(ICache.class);
        when(mockCache.get(any())).thenAnswer(inv -> cacheMap.get(inv.getArgument(0)));
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

    private ObserveGate.Verdict evaluate(ChannelTarget target, String text) {
        return gate.evaluate("slack", CHANNEL, target, text, List.of());
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

            assertTrue(evaluate(target, "we have an INCIDENT in prod").respond());
            assertEquals(ObserveGate.Reason.NO_TRIGGER,
                    evaluate(target, "lunch plans?").reason());
        }

        @Test
        @DisplayName("no keywords and no MIME types means watch everything")
        void watchesEverythingWhenUnconfigured() {
            // Documented on the model, and the reason the cooldown and both caps
            // are not optional.
            assertTrue(evaluate(observer("all", config()), "anything at all").respond());
        }

        @Test
        @DisplayName("a MIME type matches, ignoring the charset the platform appends")
        void mimeTypeMatches() {
            var config = config();
            config.setTriggerMimeTypes(List.of("application/pdf"));
            var target = observer("docs", config);

            assertTrue(gate.evaluate("slack", CHANNEL, target, "", List.of("application/pdf; charset=binary"))
                    .respond());
            assertEquals(ObserveGate.Reason.NO_TRIGGER,
                    gate.evaluate("slack", CHANNEL, target, "", List.of("image/png")).reason());
        }

        @Test
        @DisplayName("either a keyword or a MIME type is enough when both are configured")
        void keywordOrMimeType() {
            var config = config();
            config.setTriggerKeywords(List.of("review"));
            config.setTriggerMimeTypes(List.of("application/pdf"));
            var target = observer("docs", config);

            assertTrue(evaluate(target, "please review this").respond());
            assertTrue(gate.evaluate("slack", CHANNEL, target, "here", List.of("application/pdf"))
                    .respond());
            assertFalse(gate.evaluate("slack", CHANNEL, target, "here", List.of("image/png"))
                    .respond());
        }

        @Test
        @DisplayName("a target that is not an observer never matches")
        void nonObserverNeverMatches() {
            var target = observer("ops", config());
            target.setObserveMode(false);
            assertEquals(ObserveGate.Reason.NOT_OBSERVING, evaluate(target, "anything").reason());
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

            assertTrue(evaluate(target, "one").respond());
            gate.recordResponse("slack", CHANNEL, target, 0.0);

            clock.advance(Duration.ofSeconds(59));
            assertEquals(ObserveGate.Reason.COOLDOWN, evaluate(target, "two").reason());

            clock.advance(Duration.ofSeconds(2));
            assertTrue(evaluate(target, "three").respond());
        }

        @Test
        @DisplayName("the daily count is a hard stop")
        void dailyResponseCap() {
            var config = config();
            config.setCooldownSeconds(0);
            config.setMaxDailyResponses(2);
            var target = observer("ops", config);

            for (int i = 0; i < 2; i++) {
                assertTrue(evaluate(target, "msg").respond());
                gate.recordResponse("slack", CHANNEL, target, 0.0);
            }
            assertEquals(ObserveGate.Reason.DAILY_RESPONSE_CAP, evaluate(target, "msg").reason());
        }

        @Test
        @DisplayName("the daily budget is a hard stop once spent")
        void dailyCostCap() {
            var config = config();
            config.setCooldownSeconds(0);
            config.setMaxCostPerDay(1.0);
            var target = observer("ops", config);

            gate.recordResponse("slack", CHANNEL, target, 0.0);
            gate.addCost("slack", CHANNEL, target, 0.75);
            assertTrue(evaluate(target, "still under").respond());

            gate.addCost("slack", CHANNEL, target, 0.30);
            assertEquals(ObserveGate.Reason.DAILY_COST_CAP, evaluate(target, "over").reason());
        }

        @Test
        @DisplayName("a new UTC day restores the allowance but not the cooldown")
        void dayRollover() {
            var config = config();
            config.setCooldownSeconds(3600);
            config.setMaxDailyResponses(1);
            var target = observer("ops", config);

            assertTrue(evaluate(target, "one").respond());
            gate.recordResponse("slack", CHANNEL, target, 5.0);
            assertFalse(evaluate(target, "two").respond());

            // Just past midnight UTC, 12 hours later: the day is new, but the
            // cooldown was set 12 hours ago and has genuinely elapsed.
            clock.advance(Duration.ofHours(12).plusMinutes(1));
            assertTrue(evaluate(target, "next day").respond());
        }

        @Test
        @DisplayName("midnight does not license an immediate second reply")
        void cooldownSurvivesMidnight() {
            var config = config();
            config.setCooldownSeconds(3600);
            var target = observer("ops", config);

            // 23:59:30 UTC, then thirty seconds later — a new day, same minute.
            clock.advance(Duration.ofHours(11).plusMinutes(59).plusSeconds(30));
            assertTrue(evaluate(target, "late").respond());
            gate.recordResponse("slack", CHANNEL, target, 0.0);

            clock.advance(Duration.ofSeconds(31));
            assertEquals(ObserveGate.Reason.COOLDOWN, evaluate(target, "just after midnight").reason());
        }

        @Test
        @DisplayName("limits are per channel, so one busy channel does not silence another")
        void limitsArePerChannel() {
            var config = config();
            config.setCooldownSeconds(3600);
            var target = observer("ops", config);

            gate.recordResponse("slack", CHANNEL, target, 0.0);
            assertEquals(ObserveGate.Reason.COOLDOWN, evaluate(target, "here").reason());
            assertTrue(gate.evaluate("slack", "C999", target, "elsewhere", List.of()).respond());
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

            gate.recordResponse("slack", CHANNEL, target, 0.0);
            assertEquals(ObserveGate.Reason.NO_TRIGGER, evaluate(target, "lunch?").reason());
        }

        @Test
        @DisplayName("an observer saved without a config still gets the defaults")
        void missingConfigStillGuarded() {
            var target = observer("ops", null);
            assertTrue(evaluate(target, "one").respond());
            gate.recordResponse("slack", CHANNEL, target, 0.0);
            // The ObserveConfig default cooldown is 60s, so this is inside it.
            clock.advance(Duration.ofSeconds(5));
            assertEquals(ObserveGate.Reason.COOLDOWN, evaluate(target, "two").reason());
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
            gate.recordResponse("slack", CHANNEL, a, 0.0);

            var match = gate.select("slack", CHANNEL, List.of(a, observer("b", second)),
                    "deploy now", List.of());
            assertTrue(match.isEmpty());
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

            gate.recordResponse("slack", CHANNEL, target, 0.0);
            gate.addCost("slack", CHANNEL, target, 0.1);
            gate.addCost("slack", CHANNEL, target, 0.1);

            assertTrue(evaluate(target, "second reply still available").respond());
        }

        @Test
        @DisplayName("a nonsense cost is ignored rather than poisoning the window")
        void ignoresNonFiniteCost() {
            var target = observer("ops", config());
            gate.recordResponse("slack", CHANNEL, target, Double.NaN);
            gate.addCost("slack", CHANNEL, target, Double.POSITIVE_INFINITY);
            gate.addCost("slack", CHANNEL, target, -5.0);

            clock.advance(Duration.ofHours(1));
            assertTrue(evaluate(target, "budget intact").respond());
        }

        @Test
        @DisplayName("recording against a null target is a no-op, not a crash")
        void nullTargetIsSafe() {
            assertDoesNotThrow(() -> gate.recordResponse("slack", CHANNEL, null, 1.0));
            assertDoesNotThrow(() -> gate.addCost("slack", CHANNEL, null, 1.0));
            assertEquals(ObserveGate.Reason.NOT_OBSERVING,
                    gate.evaluate("slack", CHANNEL, null, "x", List.of()).reason());
        }
    }
}
