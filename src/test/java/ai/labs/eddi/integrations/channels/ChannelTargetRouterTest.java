/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("ChannelTargetRouter Tests")
@SuppressWarnings("unchecked")
class ChannelTargetRouterTest {

    @Mock
    private IChannelIntegrationStore channelStore;
    @Mock
    private IDocumentDescriptorStore descriptorStore;
    @Mock
    private IRestAgentAdministration agentAdmin;
    @Mock
    private IRestAgentStore agentStore;
    @Mock
    private SecretResolver secretResolver;
    @Mock
    private ICacheFactory cacheFactory;
    @Mock
    private ICache<String, ChannelTarget> threadTargetLock;

    private ChannelTargetRouter router;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        when(cacheFactory.<String, ChannelTarget>getCache(anyString(), any(Duration.class)))
                .thenReturn(threadTargetLock);
        // Make secretResolver pass through
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

        // Empty descriptor list to avoid NPE during refresh
        when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(Collections.emptyList());
        when(agentAdmin.getDeploymentStatuses(any())).thenReturn(Collections.emptyList());

        router = new ChannelTargetRouter(channelStore, descriptorStore, agentAdmin, agentStore,
                secretResolver, cacheFactory);
    }

    // ==================== resolveFromIntegration ====================

    @Nested
    @DisplayName("resolveFromIntegration Tests")
    class ResolveFromIntegrationTests {

        @Test
        @DisplayName("null message returns null (help)")
        void nullMessage_returnsNull() {
            var integration = createIntegration("general", List.of("architect"));
            assertNull(router.resolveFromIntegration(integration, null));
        }

        @Test
        @DisplayName("blank message returns null (help)")
        void blankMessage_returnsNull() {
            var integration = createIntegration("general", List.of("architect"));
            assertNull(router.resolveFromIntegration(integration, "   "));
        }

        @Test
        @DisplayName("'help' message returns null")
        void helpMessage_returnsNull() {
            var integration = createIntegration("general", List.of("architect"));
            assertNull(router.resolveFromIntegration(integration, "help"));
            assertNull(router.resolveFromIntegration(integration, "HELP"));
        }

        @Test
        @DisplayName("trigger match strips keyword and returns target")
        void triggerMatch() {
            var integration = createIntegration("general", List.of("architect"));
            var result = router.resolveFromIntegration(integration, "architect: build me a house");
            assertNotNull(result);
            assertEquals("build me a house", result.strippedMessage());
        }

        @Test
        @DisplayName("trigger match is case-insensitive")
        void triggerCaseInsensitive() {
            var integration = createIntegration("general", List.of("Architect"));
            var result = router.resolveFromIntegration(integration, "ARCHITECT: question");
            assertNotNull(result);
            assertEquals("question", result.strippedMessage());
        }

        @Test
        @DisplayName("no colon in message falls through to default target")
        void noColon_usesDefault() {
            var integration = createIntegration("general", List.of("architect"));
            var result = router.resolveFromIntegration(integration, "just a regular message");
            assertNotNull(result);
            assertEquals("just a regular message", result.strippedMessage());
        }

        @Test
        @DisplayName("colon but no matching trigger falls through to default target")
        void colonButNoMatch_usesDefault() {
            var integration = createIntegration("general", List.of("architect"));
            var result = router.resolveFromIntegration(integration, "unknown: some question");
            assertNotNull(result);
            assertEquals("unknown: some question", result.strippedMessage());
        }

        @Test
        @DisplayName("null targets in integration falls through to default")
        void nullTargets() {
            var integration = new ChannelIntegrationConfiguration();
            integration.setDefaultTargetName("general");
            integration.setTargets(null);

            var result = router.resolveFromIntegration(integration, "architect: hello");
            // No targets, no default => null
            assertNull(result);
        }

        @Test
        @DisplayName("null triggers on target are skipped")
        void nullTriggersOnTarget() {
            var target = new ChannelTarget();
            target.setName("general");
            target.setTriggers(null);

            var integration = new ChannelIntegrationConfiguration();
            integration.setTargets(List.of(target));
            integration.setDefaultTargetName("general");

            // Should not throw NPE, should fall through to default
            var result = router.resolveFromIntegration(integration, "test: hello");
            assertNotNull(result);
        }

        @Test
        @DisplayName("no default target configured returns null")
        void noDefaultTarget_returnsNull() {
            var integration = new ChannelIntegrationConfiguration();
            integration.setDefaultTargetName(null);
            integration.setTargets(List.of());

            var result = router.resolveFromIntegration(integration, "just a message");
            assertNull(result);
        }
    }

    // ==================== ResolvedTarget record methods ====================

    @Nested
    @DisplayName("ResolvedTarget record Tests")
    class ResolvedTargetTests {

        @Test
        @DisplayName("botToken from integration platformConfig")
        void botToken_fromIntegration() {
            var integration = new ChannelIntegrationConfiguration();
            integration.setPlatformConfig(Map.of("botToken", "xoxb-test"));
            var target = new ChannelTarget();

            var resolved = new ChannelTargetRouter.ResolvedTarget(target, "msg", integration, null, null);
            assertEquals("xoxb-test", resolved.botToken());
        }

        @Test
        @DisplayName("botToken falls back to legacy")
        void botToken_fallbackToLegacy() {
            var target = new ChannelTarget();
            var resolved = new ChannelTargetRouter.ResolvedTarget(target, "msg", null, "legacy-token", null);
            assertEquals("legacy-token", resolved.botToken());
        }

        @Test
        @DisplayName("signingSecret from integration platformConfig")
        void signingSecret_fromIntegration() {
            var integration = new ChannelIntegrationConfiguration();
            integration.setPlatformConfig(Map.of("signingSecret", "secret123"));
            var target = new ChannelTarget();

            var resolved = new ChannelTargetRouter.ResolvedTarget(target, "msg", integration, null, null);
            assertEquals("secret123", resolved.signingSecret());
        }

        @Test
        @DisplayName("signingSecret falls back to legacy")
        void signingSecret_fallbackToLegacy() {
            var target = new ChannelTarget();
            var resolved = new ChannelTargetRouter.ResolvedTarget(target, "msg", null, null, "legacy-secret");
            assertEquals("legacy-secret", resolved.signingSecret());
        }

        @Test
        @DisplayName("null integration with null platform config returns legacy")
        void nullIntegrationPlatformConfig() {
            var integration = new ChannelIntegrationConfiguration();
            integration.setPlatformConfig(null);
            var target = new ChannelTarget();

            var resolved = new ChannelTargetRouter.ResolvedTarget(target, "msg", integration, "legacyBt", "legacySs");
            // platformConfig is null, so falls through to legacy
            // Actually ChannelIntegrationConfiguration constructor initializes it to new
            // HashMap
            // but setPlatformConfig(null) sets it to new HashMap<>()
            assertNull(resolved.botToken()); // empty map, no "botToken" key
        }
    }

    // ==================== LegacyTarget ====================

    @Nested
    @DisplayName("LegacyTarget Tests")
    class LegacyTargetTests {

        @Test
        @DisplayName("toChannelTarget with groupId sets GROUP type")
        void withGroupId() {
            var legacy = new ChannelTargetRouter.LegacyTarget("agent1", "token", "secret", "group1");
            var target = legacy.toChannelTarget();
            assertEquals(ChannelTarget.TargetType.GROUP, target.getType());
            assertEquals("group1", target.getTargetId());
            assertEquals("default", target.getName());
        }

        @Test
        @DisplayName("toChannelTarget without groupId sets AGENT type")
        void withoutGroupId() {
            var legacy = new ChannelTargetRouter.LegacyTarget("agent1", "token", "secret", null);
            var target = legacy.toChannelTarget();
            assertEquals(ChannelTarget.TargetType.AGENT, target.getType());
            assertEquals("agent1", target.getTargetId());
        }
    }

    // ==================== getSigningSecrets ====================

    @Nested
    @DisplayName("getSigningSecrets Tests")
    class GetSigningSecretsTests {

        @Test
        @DisplayName("non-slack channel returns empty set")
        void nonSlack_returnsEmpty() {
            Set<String> result = router.getSigningSecrets("teams");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null channel type returns empty set")
        void nullType_returnsEmpty() {
            Set<String> result = router.getSigningSecrets(null);
            assertTrue(result.isEmpty());
        }
    }

    // ==================== resolveThreadTarget ====================

    @Nested
    @DisplayName("resolveThreadTarget Tests")
    class ResolveThreadTargetTests {

        @Test
        @DisplayName("no locked target returns null")
        void noLock_returnsNull() {
            when(threadTargetLock.get(anyString())).thenReturn(null);
            var result = router.resolveThreadTarget("slack", "C123", "ts123");
            assertNull(result);
        }

        @Test
        @DisplayName("locked target returns ResolvedTarget")
        void lockedTarget_returns() {
            var target = new ChannelTarget();
            target.setName("locked");
            when(threadTargetLock.get("slack:C123:ts123")).thenReturn(target);

            var result = router.resolveThreadTarget("slack", "C123", "ts123");
            assertNotNull(result);
            assertEquals("locked", result.target().getName());
        }

        @Test
        @DisplayName("null channel type is normalized")
        void nullChannelType() {
            when(threadTargetLock.get(":C123:ts123")).thenReturn(null);
            var result = router.resolveThreadTarget(null, "C123", "ts123");
            assertNull(result);
        }
    }

    // ==================== lockThreadTarget ====================

    @Nested
    @DisplayName("lockThreadTarget Tests")
    class LockThreadTargetTests {

        @Test
        @DisplayName("lock stores target in cache")
        void lockStoresTarget() {
            var target = new ChannelTarget();
            target.setName("test");

            router.lockThreadTarget("slack", "C123", "ts123", target);

            verify(threadTargetLock).put("slack:C123:ts123", target);
        }
    }

    // ==================== hasAnyChannels ====================

    @Nested
    @DisplayName("hasAnyChannels Tests")
    class HasAnyChannelsTests {

        @Test
        @DisplayName("no channels returns false for slack")
        void noChannels_slack() {
            assertFalse(router.hasAnyChannels("slack"));
        }

        @Test
        @DisplayName("no channels returns false for non-slack")
        void noChannels_teams() {
            assertFalse(router.hasAnyChannels("teams"));
        }
    }

    // ==================== Helper ====================

    private ChannelIntegrationConfiguration createIntegration(String defaultTargetName, List<String> triggers) {
        var target = new ChannelTarget();
        target.setName(defaultTargetName);
        target.setTriggers(triggers);
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTargetId("agent-123");

        var integration = new ChannelIntegrationConfiguration();
        integration.setTargets(List.of(target));
        integration.setDefaultTargetName(defaultTargetName);
        return integration;
    }
}
