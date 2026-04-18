package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for trigger matching, default fallback, help detection, colon
 * requirement, and thread target locking in {@link ChannelTargetRouter}.
 * <p>
 * These tests exercise the {@code resolveFromIntegration} logic directly
 * without needing a running database or agent deployment infrastructure.
 */
class ChannelTargetRouterTest {

    private ChannelTargetRouter router;
    private ChannelIntegrationConfiguration integration;

    @BeforeEach
    void setUp() {
        // Router is instantiated with null dependencies — we only test the
        // matching logic (resolveFromIntegration) which doesn't hit any stores.
        router = new ChannelTargetRouter(null, null, null, null, null);

        integration = new ChannelIntegrationConfiguration();
        integration.setName("Test Hub");
        integration.setChannelType("slack");
        integration.setDefaultTargetName("architect");

        var architect = new ChannelTarget();
        architect.setName("architect");
        architect.setTriggers(List.of("architect", "arch"));
        architect.setType(ChannelTarget.TargetType.AGENT);
        architect.setTargetId("agent-arch-id");

        var security = new ChannelTarget();
        security.setName("security");
        security.setTriggers(List.of("security", "sec", "infosec"));
        security.setType(ChannelTarget.TargetType.AGENT);
        security.setTargetId("agent-sec-id");

        var review = new ChannelTarget();
        review.setName("review");
        review.setTriggers(List.of("review", "review-panel"));
        review.setType(ChannelTarget.TargetType.GROUP);
        review.setTargetId("group-review-id");

        integration.setTargets(List.of(architect, security, review));
    }

    // ─── Colon-required trigger matching ───────────────────────────────────────

    @Nested
    @DisplayName("Trigger matching (colon required)")
    class TriggerMatching {

        @Test
        @DisplayName("architect: question → matches architect target")
        void matchesExplicitTriggerWithColon() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    "architect: how do I deploy?");

            assertNotNull(result);
            assertEquals("architect", result.target().getName());
            assertEquals("how do I deploy?", result.strippedMessage());
            assertEquals("agent-arch-id", result.target().getTargetId());
        }

        @Test
        @DisplayName("sec: is this safe → matches security via alias")
        void matchesTriggerAlias() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    "sec: is this endpoint safe?");

            assertNotNull(result);
            assertEquals("security", result.target().getName());
            assertEquals("is this endpoint safe?", result.strippedMessage());
        }

        @Test
        @DisplayName("review: should we migrate → matches group target")
        void matchesGroupTarget() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    "review: should we migrate to gRPC?");

            assertNotNull(result);
            assertEquals("review", result.target().getName());
            assertEquals(ChannelTarget.TargetType.GROUP, result.target().getType());
            assertEquals("group-review-id", result.target().getTargetId());
        }

        @Test
        @DisplayName("ARCHITECT: question → case-insensitive match")
        void matchesCaseInsensitive() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    "ARCHITECT: deploy question");

            assertNotNull(result);
            assertEquals("architect", result.target().getName());
        }

        @Test
        @DisplayName("arch: question → matches via short alias")
        void matchesShortAlias() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    "arch: question about patterns");

            assertNotNull(result);
            assertEquals("architect", result.target().getName());
        }

        @Test
        @DisplayName("review-panel: question → matches hyphenated trigger")
        void matchesHyphenatedTrigger() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    "review-panel: evaluate this design");

            assertNotNull(result);
            assertEquals("review", result.target().getName());
        }
    }

    // ─── Colon-required: no match without colon ────────────────────────────────

    @Nested
    @DisplayName("No colon → falls through to default")
    class NoColonFallthrough {

        @Test
        @DisplayName("architect how do I deploy → no colon, falls to default")
        void noColonFallsToDefault() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    "architect how do I deploy?");

            assertNotNull(result);
            // Falls through to default target (architect in this case, so same target but
            // the important thing is the full message is preserved)
            assertEquals("architect", result.target().getName());
            assertEquals("architect how do I deploy?", result.strippedMessage());
        }

        @Test
        @DisplayName("architect diagrams are useful → no false positive without colon")
        void noFalsePositiveWithoutColon() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    "architect diagrams are useful");

            assertNotNull(result);
            assertEquals("architect", result.target().getName());
            // Full message preserved — not stripped
            assertEquals("architect diagrams are useful", result.strippedMessage());
        }

        @Test
        @DisplayName("plain question → routes to default target")
        void plainQuestionDefaultTarget() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    "how do I deploy to production?");

            assertNotNull(result);
            assertEquals("architect", result.target().getName());
            assertEquals("how do I deploy to production?", result.strippedMessage());
        }
    }

    // ─── Unknown trigger with colon ────────────────────────────────────────────

    @Nested
    @DisplayName("Unknown trigger keyword")
    class UnknownTrigger {

        @Test
        @DisplayName("unknown: question → falls to default with full message")
        void unknownTriggerFallsToDefault() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    "unknown: some question");

            assertNotNull(result);
            assertEquals("architect", result.target().getName());
            // Full message including "unknown:" preserved
            assertEquals("unknown: some question", result.strippedMessage());
        }
    }

    // ─── Help detection ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Help detection")
    class HelpDetection {

        @Test
        @DisplayName("help → returns null (signal for help)")
        void helpReturnsNull() {
            assertNull(router.resolveFromIntegration(integration, "help"));
        }

        @Test
        @DisplayName("HELP → case-insensitive help")
        void helpCaseInsensitive() {
            assertNull(router.resolveFromIntegration(integration, "HELP"));
        }

        @Test
        @DisplayName("  help  → trimmed help")
        void helpTrimmed() {
            assertNull(router.resolveFromIntegration(integration, "  help  "));
        }

        @Test
        @DisplayName("empty message → returns null")
        void emptyMessageReturnsNull() {
            assertNull(router.resolveFromIntegration(integration, ""));
        }

        @Test
        @DisplayName("null message → returns null")
        void nullMessageReturnsNull() {
            assertNull(router.resolveFromIntegration(integration, null));
        }

        @Test
        @DisplayName("blank message → returns null")
        void blankMessageReturnsNull() {
            assertNull(router.resolveFromIntegration(integration, "   "));
        }
    }

    // ─── Thread target locking ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Thread target locking")
    class ThreadTargetLocking {

        @Test
        @DisplayName("locked target is returned for thread")
        void lockedTargetReturned() {
            var target = new ChannelTarget();
            target.setName("architect");
            target.setTargetId("agent-arch-id");

            router.lockThreadTarget("1713400000.123456", target);

            ResolvedTarget result = router.resolveThreadTarget("slack", "C07TEST",
                    "1713400000.123456");

            assertNotNull(result);
            assertEquals("architect", result.target().getName());
        }

        @Test
        @DisplayName("unlocked thread returns null")
        void unlockedThreadReturnsNull() {
            ResolvedTarget result = router.resolveThreadTarget("slack", "C07TEST",
                    "9999999999.999999");

            assertNull(result);
        }

        @Test
        @DisplayName("different threads have independent locks")
        void independentThreadLocks() {
            var arch = new ChannelTarget();
            arch.setName("architect");

            var sec = new ChannelTarget();
            sec.setName("security");

            router.lockThreadTarget("thread-1", arch);
            router.lockThreadTarget("thread-2", sec);

            assertEquals("architect",
                    router.resolveThreadTarget("slack", "C07TEST", "thread-1")
                            .target().getName());
            assertEquals("security",
                    router.resolveThreadTarget("slack", "C07TEST", "thread-2")
                            .target().getName());
        }
    }

    // ─── Edge cases ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("message with multiple colons → only first colon counts")
        void multipleColons() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    "architect: what about http://example.com?");

            assertNotNull(result);
            assertEquals("architect", result.target().getName());
            assertEquals("what about http://example.com?", result.strippedMessage());
        }

        @Test
        @DisplayName("trigger with leading/trailing spaces → trimmed")
        void triggerWithSpaces() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    "  architect  : how do I deploy?");

            assertNotNull(result);
            assertEquals("architect", result.target().getName());
        }

        @Test
        @DisplayName("colon at start of message → no trigger, default")
        void colonAtStart() {
            ResolvedTarget result = router.resolveFromIntegration(integration,
                    ": some question");

            assertNotNull(result);
            assertEquals("architect", result.target().getName());
            assertEquals(": some question", result.strippedMessage());
        }

        @Test
        @DisplayName("single-target integration → always resolves to that target")
        void singleTarget() {
            var simpleIntegration = new ChannelIntegrationConfiguration();
            simpleIntegration.setDefaultTargetName("support");

            var support = new ChannelTarget();
            support.setName("support");
            support.setTriggers(List.of("support"));
            support.setType(ChannelTarget.TargetType.AGENT);
            support.setTargetId("agent-support-id");

            simpleIntegration.setTargets(List.of(support));

            ResolvedTarget result = router.resolveFromIntegration(simpleIntegration,
                    "I need help with my order");

            assertNotNull(result);
            assertEquals("support", result.target().getName());
            assertEquals("I need help with my order", result.strippedMessage());
        }
    }
}
