/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ToolRateLimiter} covering token bucket logic, window
 * resets, custom limits, and informational methods.
 */
class ToolRateLimiterTest {

    private ToolRateLimiter rateLimiter;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        rateLimiter = new ToolRateLimiter();
        registry = new SimpleMeterRegistry();
        rateLimiter.meterRegistry = registry;
        rateLimiter.init();
    }

    @Nested
    @DisplayName("tryAcquire with default limit")
    class DefaultLimit {

        @Test
        @DisplayName("should allow first call")
        void firstCallAllowed() {
            assertTrue(rateLimiter.tryAcquire("testTool"));
        }

        @Test
        @DisplayName("should allow multiple calls within default limit (100)")
        void multipleCallsWithinLimit() {
            for (int i = 0; i < 50; i++) {
                assertTrue(rateLimiter.tryAcquire("testTool"), "Call " + i + " should be allowed");
            }
        }

        @Test
        @DisplayName("should deny call at default limit boundary")
        void denyAtBoundary() {
            // Exhaust default limit (100)
            for (int i = 0; i < 100; i++) {
                assertTrue(rateLimiter.tryAcquire("exhaustTool"));
            }
            // 101st call should be denied
            assertFalse(rateLimiter.tryAcquire("exhaustTool"));
        }
    }

    @Nested
    @DisplayName("tryAcquire with custom limit")
    class CustomLimit {

        @Test
        @DisplayName("should enforce custom lower limit")
        void customLowerLimit() {
            for (int i = 0; i < 5; i++) {
                assertTrue(rateLimiter.tryAcquire("limitedTool", 5));
            }
            assertFalse(rateLimiter.tryAcquire("limitedTool", 5));
        }

        @Test
        @DisplayName("should enforce limit of 1")
        void limitOfOne() {
            assertTrue(rateLimiter.tryAcquire("onceTool", 1));
            assertFalse(rateLimiter.tryAcquire("onceTool", 1));
        }
    }

    @Nested
    @DisplayName("getRemaining")
    class GetRemaining {

        @Test
        @DisplayName("should return default limit for unknown tool")
        void unknownToolReturnsDefault() {
            assertEquals(100, rateLimiter.getRemaining("unknownTool"));
        }

        @Test
        @DisplayName("should decrease after calls")
        void decreasesAfterCalls() {
            rateLimiter.tryAcquire("countTool", 10);
            rateLimiter.tryAcquire("countTool", 10);
            assertEquals(8, rateLimiter.getRemaining("countTool"));
        }
    }

    @Nested
    @DisplayName("getInfo")
    class GetInfo {

        @Test
        @DisplayName("should return default info for unknown tool")
        void unknownToolInfo() {
            var info = rateLimiter.getInfo("newTool");
            assertEquals(100, info.limit);
            assertEquals(100, info.remaining);
            assertTrue(info.resetTimeMs > System.currentTimeMillis());
        }

        @Test
        @DisplayName("should reflect current state for known tool")
        void knownToolInfo() {
            rateLimiter.tryAcquire("knownTool", 5);
            var info = rateLimiter.getInfo("knownTool");
            assertEquals(5, info.limit);
            assertEquals(4, info.remaining);
        }

        @Test
        @DisplayName("toString should contain rate limit info")
        void infoToString() {
            var info = rateLimiter.getInfo("anyTool");
            String str = info.toString();
            assertTrue(str.contains("Rate Limit"));
            assertTrue(str.contains("remaining"));
        }
    }

    /**
     * The limiter used to keep exactly one bucket per tool name, so
     * {@code defaultRateLimit: 100} was a deployment-wide allowance and a single
     * conversation could consume every other user's share of a tool. These cases
     * fail if the conversation dimension is dropped from the bucket key again.
     */
    @Nested
    @DisplayName("per-conversation isolation")
    class PerConversationIsolation {

        @Test
        @DisplayName("one conversation exhausting its limit does not affect another")
        void exhaustedConversationDoesNotStarveOthers() {
            for (int i = 0; i < 5; i++) {
                assertTrue(rateLimiter.tryAcquire("conv-noisy", "searchWeb", 5),
                        "the noisy conversation is still within its own allowance at call " + i);
            }
            assertFalse(rateLimiter.tryAcquire("conv-noisy", "searchWeb", 5),
                    "the noisy conversation has spent its own allowance");

            for (int i = 0; i < 5; i++) {
                assertTrue(rateLimiter.tryAcquire("conv-quiet", "searchWeb", 5),
                        "a second conversation must have its own untouched allowance (call " + i + ")");
            }
        }

        @Test
        @DisplayName("the same conversation shares one bucket across repeated calls")
        void sameConversationSharesOneBucket() {
            assertTrue(rateLimiter.tryAcquire("conv-a", "searchWeb", 2));
            assertTrue(rateLimiter.tryAcquire("conv-a", "searchWeb", 2));
            assertFalse(rateLimiter.tryAcquire("conv-a", "searchWeb", 2),
                    "a per-conversation bucket must still be a bucket");
        }

        @Test
        @DisplayName("different tools in one conversation have independent buckets")
        void toolsAreIndependentWithinAConversation() {
            assertTrue(rateLimiter.tryAcquire("conv-a", "searchWeb", 1));
            assertFalse(rateLimiter.tryAcquire("conv-a", "searchWeb", 1));
            assertTrue(rateLimiter.tryAcquire("conv-a", "readAttachment", 1),
                    "exhausting one tool must not exhaust another");
        }

        @Test
        @DisplayName("a null conversation id falls back to one shared unattributed bucket")
        void nullConversationIdIsAccepted() {
            assertTrue(rateLimiter.tryAcquire(null, "searchWeb", 1));
            assertFalse(rateLimiter.tryAcquire(null, "searchWeb", 1));
            assertTrue(rateLimiter.tryAcquire("conv-a", "searchWeb", 1),
                    "the unattributed bucket must not be shared with a real conversation");
        }

        @Test
        @DisplayName("reset clears the tool's buckets in every conversation")
        void resetClearsEveryScope() {
            assertTrue(rateLimiter.tryAcquire("conv-a", "searchWeb", 1));
            assertTrue(rateLimiter.tryAcquire("conv-b", "searchWeb", 1));
            assertFalse(rateLimiter.tryAcquire("conv-a", "searchWeb", 1));
            assertFalse(rateLimiter.tryAcquire("conv-b", "searchWeb", 1));

            rateLimiter.reset("searchWeb");

            assertTrue(rateLimiter.tryAcquire("conv-a", "searchWeb", 1));
            assertTrue(rateLimiter.tryAcquire("conv-b", "searchWeb", 1));
        }
    }

    /**
     * The optional deployment-wide layer, for protecting a provider quota that the
     * per-conversation limit deliberately does not model.
     */
    @Nested
    @DisplayName("optional global bucket")
    class GlobalBucket {

        @Test
        @DisplayName("is off by default, so conversations never share an allowance")
        void offByDefault() {
            assertFalse(rateLimiter.globalLimitEnabled);
            for (int conversation = 0; conversation < 4; conversation++) {
                assertTrue(rateLimiter.tryAcquire("conv-" + conversation, "searchWeb", 1),
                        "with no global layer each conversation gets its full allowance");
            }
        }

        @Test
        @DisplayName("caps the deployment once enabled, across conversations")
        void capsAcrossConversations() {
            rateLimiter.globalLimitEnabled = true;
            rateLimiter.globalLimit = 3;

            assertTrue(rateLimiter.tryAcquire("conv-1", "searchWeb", 10));
            assertTrue(rateLimiter.tryAcquire("conv-2", "searchWeb", 10));
            assertTrue(rateLimiter.tryAcquire("conv-3", "searchWeb", 10));
            assertFalse(rateLimiter.tryAcquire("conv-4", "searchWeb", 10),
                    "the deployment-wide bucket is spent even though conv-4 has its own allowance");
        }

        @Test
        @DisplayName("a global rejection does not cost the conversation its own quota")
        void globalRejectionRefundsTheConversationToken() {
            rateLimiter.globalLimitEnabled = true;
            rateLimiter.globalLimit = 1;

            assertTrue(rateLimiter.tryAcquire("conv-1", "searchWeb", 2));
            assertFalse(rateLimiter.tryAcquire("conv-2", "searchWeb", 2), "the global bucket is spent");

            // conv-2 must not have been charged for a rejection it did not cause: with the
            // deployment cap lifted it still has both of its own calls.
            rateLimiter.globalLimitEnabled = false;
            assertTrue(rateLimiter.tryAcquire("conv-2", "searchWeb", 2));
            assertTrue(rateLimiter.tryAcquire("conv-2", "searchWeb", 2));
            assertFalse(rateLimiter.tryAcquire("conv-2", "searchWeb", 2));
        }
    }

    /**
     * The gauge used to be registered with no tags at all, so whichever tool
     * happened to be limited first claimed the single series and every other tool
     * silently reported that one bucket's headroom.
     */
    @Nested
    @DisplayName("remaining gauge")
    class RemainingGauge {

        @Test
        @DisplayName("is tagged per tool")
        void taggedPerTool() {
            rateLimiter.tryAcquire("conv-1", "searchWeb", 10);
            rateLimiter.tryAcquire("conv-1", "readAttachment", 4);

            var searchGauge = registry.find("eddi.tool.ratelimit.remaining").tag("tool", "searchWeb").gauge();
            var attachmentGauge = registry.find("eddi.tool.ratelimit.remaining").tag("tool", "readAttachment").gauge();

            assertNotNull(searchGauge, "each tool must have its own tagged series");
            assertNotNull(attachmentGauge, "each tool must have its own tagged series");
            assertEquals(9.0, searchGauge.value());
            assertEquals(3.0, attachmentGauge.value());
        }

        @Test
        @DisplayName("reports the most constrained conversation for that tool")
        void reportsTightestBucket() {
            rateLimiter.tryAcquire("conv-quiet", "searchWeb", 10);
            for (int i = 0; i < 7; i++) {
                rateLimiter.tryAcquire("conv-busy", "searchWeb", 10);
            }

            var gauge = registry.find("eddi.tool.ratelimit.remaining").tag("tool", "searchWeb").gauge();
            assertNotNull(gauge);
            assertEquals(3.0, gauge.value(), "the busy conversation is the one close to being limited");
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset {

        @Test
        @DisplayName("should clear specific tool bucket")
        void resetSpecificTool() {
            // Exhaust the limit
            for (int i = 0; i < 5; i++) {
                rateLimiter.tryAcquire("resetMe", 5);
            }
            assertFalse(rateLimiter.tryAcquire("resetMe", 5));

            // Reset and verify it's available again
            rateLimiter.reset("resetMe");
            assertTrue(rateLimiter.tryAcquire("resetMe", 5));
        }

        @Test
        @DisplayName("resetAll should clear all buckets")
        void resetAll() {
            rateLimiter.tryAcquire("tool1", 1);
            rateLimiter.tryAcquire("tool2", 1);
            assertFalse(rateLimiter.tryAcquire("tool1", 1));
            assertFalse(rateLimiter.tryAcquire("tool2", 1));

            rateLimiter.resetAll();
            assertTrue(rateLimiter.tryAcquire("tool1", 1));
            assertTrue(rateLimiter.tryAcquire("tool2", 1));
        }
    }

    /**
     * Buckets are per (conversation, tool) and the store holds up to 100,000 of
     * them, while the per-tool Micrometer gauge calls {@code getRemaining} on every
     * scrape. Deriving the tightest bucket by iterating the WHOLE store made
     * metrics scraping O(tools × live conversations) and took every bucket's
     * monitor on the way, contending with the live {@code tryAcquire} path.
     * <p>
     * The results are identical either way — only the cost differs — so the
     * per-tool index has to be asserted directly.
     */
    @Nested
    @DisplayName("per-tool reads do not scan the whole bucket store")
    class PerToolIndex {

        @Test
        @DisplayName("a per-tool read examines only that tool's buckets")
        void scanIsProportionalToTheToolNotTheStore() {
            for (int i = 0; i < 500; i++) {
                rateLimiter.tryAcquire("conv-" + i, "noisyNeighbour", 100);
            }
            rateLimiter.tryAcquire("conv-a", "target", 100);
            rateLimiter.tryAcquire("conv-b", "target", 100);

            assertEquals(502, rateLimiter.bucketCount(), "sanity: the store really does hold every bucket");
            assertEquals(2, rateLimiter.scanCandidateCount("target"),
                    "a read for 'target' must not walk the other 500 buckets");
            assertEquals(500, rateLimiter.scanCandidateCount("noisyNeighbour"));
        }

        @Test
        @DisplayName("an unknown tool has nothing to scan")
        void unknownToolScansNothing() {
            rateLimiter.tryAcquire("conv-a", "target", 100);
            assertEquals(0, rateLimiter.scanCandidateCount("neverSeen"));
        }

        @Test
        @DisplayName("the indexed scan still reports the tightest bucket, unaffected by other tools")
        void stillReportsTheTightestBucket() {
            // A different tool, fully exhausted — it must NOT influence 'target'.
            for (int i = 0; i < 5; i++) {
                rateLimiter.tryAcquire("conv-noisy", "otherTool", 5);
            }
            // Two conversations on 'target': one busy (2 left), one idle (9 left).
            for (int i = 0; i < 8; i++) {
                rateLimiter.tryAcquire("conv-busy", "target", 10);
            }
            rateLimiter.tryAcquire("conv-idle", "target", 10);

            assertEquals(2, rateLimiter.getRemaining("target"),
                    "the gauge reports the caller closest to being limited, for THIS tool only");
            assertEquals(0, rateLimiter.getRemaining("otherTool"));
        }

        @Test
        @DisplayName("reset(tool) empties the index so the next read falls back to the default")
        void resetClearsTheIndex() {
            rateLimiter.tryAcquire("conv-a", "target", 10);
            assertEquals(1, rateLimiter.scanCandidateCount("target"));

            rateLimiter.reset("target");

            assertEquals(0, rateLimiter.scanCandidateCount("target"),
                    "a stale index entry would make the next read consult a bucket that no longer exists");
        }

        @Test
        @DisplayName("resetAll empties the index for every tool")
        void resetAllClearsTheIndex() {
            rateLimiter.tryAcquire("conv-a", "toolA", 10);
            rateLimiter.tryAcquire("conv-b", "toolB", 10);

            rateLimiter.resetAll();

            assertEquals(0, rateLimiter.scanCandidateCount("toolA"));
            assertEquals(0, rateLimiter.scanCandidateCount("toolB"));
        }
    }

    /**
     * A refill computed as {@code elapsedNanos * limit / WINDOW_NANOS} in long
     * arithmetic overflows once a bucket has been idle long enough — about 107 days
     * at the default limit of 1000, sooner at a higher one. The wrapped product is
     * negative, so instead of refilling, the bucket's token count is driven below
     * zero and {@code tryAcquire} refuses every subsequent call.
     * <p>
     * The failure mode is the nasty kind: a rate limiter that silently latches shut
     * and denies a tool forever, with no error and nothing in the logs to connect
     * it to elapsed time.
     */
    @Nested
    @DisplayName("refill over a long idle window")
    class LongIdleRefill {

        private static final long NANOS_PER_DAY = 86_400L * 1_000_000_000L;

        @Test
        @DisplayName("a bucket idle past the long-overflow threshold refills instead of latching shut")
        void idleBucketRefillsRatherThanLatchingShut() {
            var bucket = new ToolRateLimiter.RateLimitBucket(1000);

            // Drain it, so a broken refill cannot be masked by a full bucket.
            for (int i = 0; i < 1000; i++) {
                assertTrue(bucket.tryAcquire(), "bucket should start with its full allowance");
            }
            assertFalse(bucket.tryAcquire(), "drained bucket denies until it refills");

            // 200 days idle: past the ~107-day point where elapsedNanos * 1000
            // exceeds Long.MAX_VALUE.
            bucket.backdateLastRefill(200 * NANOS_PER_DAY);

            assertTrue(bucket.tryAcquire(),
                    "after a long idle window the bucket must be full again, not permanently denied");
            assertTrue(bucket.tokenCount() >= 0.0,
                    "token count must never go negative — that is the latched-shut state");
        }

        @Test
        @DisplayName("the refill saturates at the limit rather than overshooting")
        void longIdleRefillIsStillCappedAtLimit() {
            var bucket = new ToolRateLimiter.RateLimitBucket(10);
            assertTrue(bucket.tryAcquire());

            bucket.backdateLastRefill(3650 * NANOS_PER_DAY); // ten years

            assertEquals(10, bucket.getRemaining(),
                    "an arbitrarily long idle period grants the limit, never more");
        }

        @Test
        @DisplayName("a normal short idle window still refills proportionally")
        void shortIdleRefillIsUnchanged() {
            var bucket = new ToolRateLimiter.RateLimitBucket(60);
            for (int i = 0; i < 60; i++) {
                assertTrue(bucket.tryAcquire());
            }
            assertFalse(bucket.tryAcquire());

            // Half a window back should return roughly half the allowance; the
            // guard against the overflow fix accidentally changing normal maths.
            bucket.backdateLastRefill(30_000L * 1_000_000L);

            int remaining = bucket.getRemaining();
            assertTrue(remaining >= 25 && remaining <= 35,
                    "half a window should restore about half the allowance, got " + remaining);
        }
    }
}
