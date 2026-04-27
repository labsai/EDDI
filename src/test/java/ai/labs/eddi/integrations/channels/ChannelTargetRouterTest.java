package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.<String, ChannelTarget>getCache(eq("channel-thread-locks"), any(Duration.class)))
                .thenReturn(new MapCache<>());
        router = new ChannelTargetRouter(null, null, null, null, null, cacheFactory);

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

        @Test
        @DisplayName("help: (with colon) → NOT help signal, falls to default with full message")
        void helpWithColonIsNotHelpSignal() {
            // "help:" has a colon — "help" is the candidate trigger. Since "help" is not
            // a configured trigger, it falls through to the default target with the full
            // message preserved (including the colon).
            ResolvedTarget result = router.resolveFromIntegration(integration, "help:");

            assertNotNull(result);
            assertEquals("architect", result.target().getName());
            assertEquals("help:", result.strippedMessage());
        }

        @Test
        @DisplayName("architect: (empty after colon) → matches trigger, empty stripped message")
        void triggerWithEmptyRemainder() {
            ResolvedTarget result = router.resolveFromIntegration(integration, "architect:");

            assertNotNull(result);
            assertEquals("architect", result.target().getName());
            assertEquals("", result.strippedMessage());
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

            router.lockThreadTarget("slack", "C07TEST", "1713400000.123456", target);

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

            router.lockThreadTarget("slack", "C07TEST", "thread-1", arch);
            router.lockThreadTarget("slack", "C07TEST", "thread-2", sec);

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

        @Test
        @DisplayName("integration with no default target → returns null for unmatched message")
        void noDefaultTarget() {
            var cfg = new ChannelIntegrationConfiguration();
            cfg.setDefaultTargetName("nonexistent");
            var t = new ChannelTarget();
            t.setName("only");
            t.setTriggers(List.of("only"));
            t.setTargetId("agent-x");
            cfg.setTargets(List.of(t));

            ResolvedTarget result = router.resolveFromIntegration(cfg, "unmatched message");
            assertNull(result);
        }

        @Test
        @DisplayName("integration with null defaultTargetName → returns null for unmatched message")
        void nullDefaultTargetName() {
            var cfg = new ChannelIntegrationConfiguration();
            cfg.setDefaultTargetName(null);
            var t = new ChannelTarget();
            t.setName("only");
            t.setTriggers(List.of("only"));
            t.setTargetId("agent-x");
            cfg.setTargets(List.of(t));

            ResolvedTarget result = router.resolveFromIntegration(cfg, "unmatched message");
            assertNull(result);
        }

        @Test
        @DisplayName("target with null triggers list → skipped, falls to default")
        void targetWithNullTriggersList() {
            var cfg = new ChannelIntegrationConfiguration();
            cfg.setDefaultTargetName("default-target");

            var noTriggers = new ChannelTarget();
            noTriggers.setName("no-triggers");
            noTriggers.setTriggers(null);
            noTriggers.setTargetId("agent-1");

            var defaultTarget = new ChannelTarget();
            defaultTarget.setName("default-target");
            defaultTarget.setTriggers(List.of("dt"));
            defaultTarget.setTargetId("agent-2");

            cfg.setTargets(List.of(noTriggers, defaultTarget));

            ResolvedTarget result = router.resolveFromIntegration(cfg, "something: message");
            assertNotNull(result);
            assertEquals("default-target", result.target().getName());
        }

        @Test
        @DisplayName("target with null trigger entry → skipped")
        void targetWithNullTriggerEntry() {
            var cfg = new ChannelIntegrationConfiguration();
            cfg.setDefaultTargetName("t1");

            var t1 = new ChannelTarget();
            t1.setName("t1");
            var triggers = new java.util.ArrayList<String>();
            triggers.add(null);
            triggers.add("real");
            t1.setTriggers(triggers);
            t1.setTargetId("agent-1");
            cfg.setTargets(List.of(t1));

            // "real:" should still match even though there's a null in the trigger list
            ResolvedTarget result = router.resolveFromIntegration(cfg, "real: hello");
            assertNotNull(result);
            assertEquals("t1", result.target().getName());
            assertEquals("hello", result.strippedMessage());
        }
    }

    // ─── ResolvedTarget credential resolution ─────────────────────────────────

    @Nested
    @DisplayName("ResolvedTarget credential resolution")
    class ResolvedTargetCredentials {

        @Test
        @DisplayName("botToken from integration platformConfig")
        void botTokenFromIntegration() {
            var cfg = new ChannelIntegrationConfiguration();
            cfg.setPlatformConfig(Map.of("botToken", "xoxb-integration-token"));

            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", cfg, null, null);

            assertEquals("xoxb-integration-token", resolved.botToken());
        }

        @Test
        @DisplayName("signingSecret from integration platformConfig")
        void signingSecretFromIntegration() {
            var cfg = new ChannelIntegrationConfiguration();
            cfg.setPlatformConfig(Map.of("signingSecret", "secret-from-integration"));

            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", cfg, null, null);

            assertEquals("secret-from-integration", resolved.signingSecret());
        }

        @Test
        @DisplayName("botToken falls back to legacy when integration is null")
        void botTokenFallsBackToLegacy() {
            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", null,
                    "xoxb-legacy-token", "legacy-secret");

            assertEquals("xoxb-legacy-token", resolved.botToken());
        }

        @Test
        @DisplayName("signingSecret falls back to legacy when integration is null")
        void signingSecretFallsBackToLegacy() {
            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", null,
                    "xoxb-legacy", "legacy-signing-secret");

            assertEquals("legacy-signing-secret", resolved.signingSecret());
        }

        @Test
        @DisplayName("botToken returns null when integration has no platformConfig")
        void botTokenNullWhenNoPlatformConfig() {
            var cfg = new ChannelIntegrationConfiguration();
            cfg.setPlatformConfig(null);

            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", cfg, null, null);

            assertNull(resolved.botToken());
        }

        @Test
        @DisplayName("signingSecret returns null when integration has no platformConfig")
        void signingSecretNullWhenNoPlatformConfig() {
            var cfg = new ChannelIntegrationConfiguration();
            cfg.setPlatformConfig(null);

            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", cfg, null, null);

            assertNull(resolved.signingSecret());
        }

        @Test
        @DisplayName("botToken returns null when both integration and legacy are null")
        void botTokenNullWhenBothNull() {
            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", null, null, null);

            assertNull(resolved.botToken());
        }
    }

    // ─── LegacyTarget ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("LegacyTarget")
    class LegacyTargetTests {

        @Test
        @DisplayName("toChannelTarget with agentId → AGENT type")
        void toChannelTargetAgent() {
            var legacy = new ChannelTargetRouter.LegacyTarget("agent-123", "token", "secret", null);
            var target = legacy.toChannelTarget();

            assertEquals("default", target.getName());
            assertEquals(ChannelTarget.TargetType.AGENT, target.getType());
            assertEquals("agent-123", target.getTargetId());
        }

        @Test
        @DisplayName("toChannelTarget with groupId → GROUP type")
        void toChannelTargetGroup() {
            var legacy = new ChannelTargetRouter.LegacyTarget("agent-123", "token", "secret", "group-456");
            var target = legacy.toChannelTarget();

            assertEquals("default", target.getName());
            assertEquals(ChannelTarget.TargetType.GROUP, target.getType());
            assertEquals("group-456", target.getTargetId());
        }
    }

    // ─── Thread lock composite key isolation ───────────────────────────────────

    @Nested
    @DisplayName("Thread lock composite key isolation")
    class ThreadLockIsolation {

        @Test
        @DisplayName("same threadTs in different channels → independent locks")
        void crossChannelIsolation() {
            var target1 = new ChannelTarget();
            target1.setName("target-channel-1");

            var target2 = new ChannelTarget();
            target2.setName("target-channel-2");

            // Lock same threadTs but in different channels
            router.lockThreadTarget("slack", "C001", "thread-abc", target1);
            router.lockThreadTarget("slack", "C002", "thread-abc", target2);

            var result1 = router.resolveThreadTarget("slack", "C001", "thread-abc");
            var result2 = router.resolveThreadTarget("slack", "C002", "thread-abc");

            assertNotNull(result1);
            assertNotNull(result2);
            assertEquals("target-channel-1", result1.target().getName());
            assertEquals("target-channel-2", result2.target().getName());
        }

        @Test
        @DisplayName("same threadTs in different platform types → independent locks")
        void crossPlatformIsolation() {
            var slackTarget = new ChannelTarget();
            slackTarget.setName("slack-target");

            var teamsTarget = new ChannelTarget();
            teamsTarget.setName("teams-target");

            router.lockThreadTarget("slack", "C001", "thread-1", slackTarget);
            router.lockThreadTarget("teams", "C001", "thread-1", teamsTarget);

            var slackResult = router.resolveThreadTarget("slack", "C001", "thread-1");
            var teamsResult = router.resolveThreadTarget("teams", "C001", "thread-1");

            assertNotNull(slackResult);
            assertNotNull(teamsResult);
            assertEquals("slack-target", slackResult.target().getName());
            assertEquals("teams-target", teamsResult.target().getName());
        }

        @Test
        @DisplayName("channelType normalization in lock/resolve — SLACK matches slack")
        void channelTypeNormalization() {
            var target = new ChannelTarget();
            target.setName("test-target");

            // Lock with mixed case
            router.lockThreadTarget("SLACK", "C001", "thread-1", target);

            // Resolve with lowercase
            var result = router.resolveThreadTarget("slack", "C001", "thread-1");
            assertNotNull(result);
            assertEquals("test-target", result.target().getName());
        }

        @Test
        @DisplayName("null channelType is handled safely in lockThreadTarget")
        void nullChannelTypeInLock() {
            var target = new ChannelTarget();
            target.setName("test");
            assertDoesNotThrow(() -> router.lockThreadTarget(null, "C001", "t1", target));
        }
    }

    // ─── Locale-safe API normalization ─────────────────────────────────────────

    @Nested
    @DisplayName("Locale-safe channel type normalization")
    class LocaleNormalization {

        @Test
        @DisplayName("getSigningSecrets with mixed case → normalizes to slack")
        void getSigningSecretsCaseInsensitive() {
            // Without integration data loaded, returns empty set for any type
            Set<String> secrets = router.getSigningSecrets("SLACK");
            assertNotNull(secrets);
            assertTrue(secrets.isEmpty());
        }

        @Test
        @DisplayName("getSigningSecrets with null channelType → empty set")
        void getSigningSecretsNull() {
            assertDoesNotThrow(() -> {
                Set<String> secrets = router.getSigningSecrets(null);
                assertTrue(secrets.isEmpty());
            });
        }

        @Test
        @DisplayName("getIntegration with null channelType → empty Optional")
        void getIntegrationNullType() {
            assertDoesNotThrow(() -> {
                assertTrue(router.getIntegration(null, "C001").isEmpty());
            });
        }

        @Test
        @DisplayName("getBotToken with null channelType → null")
        void getBotTokenNullType() {
            assertDoesNotThrow(() -> {
                assertNull(router.getBotToken(null, "C001"));
            });
        }

        @Test
        @DisplayName("hasAnyChannels with null channelType → false")
        void hasAnyChannelsNullType() {
            assertDoesNotThrow(() -> {
                assertFalse(router.hasAnyChannels(null));
            });
        }

        @Test
        @DisplayName("resolveTarget with null channelType → null (no match)")
        void resolveTargetNullType() {
            assertDoesNotThrow(() -> {
                assertNull(router.resolveTarget(null, "C001", "hello"));
            });
        }

        @Test
        @DisplayName("resolveTarget with non-slack type → null (no match)")
        void resolveTargetUnknownType() {
            assertNull(router.resolveTarget("teams", "C001", "hello"));
        }

        @Test
        @DisplayName("hasAnyChannels with unknown non-slack type → false")
        void hasAnyChannelsUnknown() {
            assertFalse(router.hasAnyChannels("teams"));
        }
    }

    // ─── Test helper: simple ConcurrentHashMap-based ICache ─────

    private static class MapCache<K, V> extends ConcurrentHashMap<K, V> implements ICache<K, V> {

        @Override
        public String getCacheName() {
            return "test-cache";
        }

        @Override
        public V put(K key, V value, long lifespan, TimeUnit unit) {
            return put(key, value);
        }

        @Override
        public V putIfAbsent(K key, V value, long lifespan, TimeUnit unit) {
            return putIfAbsent(key, value);
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> map, long lifespan, TimeUnit unit) {
            putAll(map);
        }

        @Override
        public V replace(K key, V value, long lifespan, TimeUnit unit) {
            return replace(key, value);
        }

        @Override
        public boolean replace(K key, V oldValue, V value, long lifespan, TimeUnit unit) {
            return replace(key, oldValue, value);
        }

        @Override
        public V put(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
            return put(key, value);
        }

        @Override
        public V putIfAbsent(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
            return putIfAbsent(key, value);
        }
    }
}
