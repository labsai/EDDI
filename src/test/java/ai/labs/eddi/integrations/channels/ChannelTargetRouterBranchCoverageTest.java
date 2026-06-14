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
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.LegacyTarget;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("ChannelTargetRouter — Branch Coverage")
class ChannelTargetRouterBranchCoverageTest {

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
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        openMocks(this);
        doReturn(threadTargetLock).when(cacheFactory).getCache(anyString(), any(Duration.class));

        // Stub stores to return empty so refresh works without errors
        when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of());
        when(agentAdmin.getDeploymentStatuses(any())).thenReturn(List.of());

        router = new ChannelTargetRouter(channelStore, descriptorStore,
                agentAdmin, agentStore, secretResolver, cacheFactory);
    }

    // ─── LegacyTarget record ────────────────────────────────────────────

    @Nested
    @DisplayName("LegacyTarget")
    class LegacyTargetTests {

        @Test
        @DisplayName("toChannelTarget with groupId → GROUP type")
        void withGroupId() {
            var legacy = new LegacyTarget("agent1", "token", "secret", "group1");
            var target = legacy.toChannelTarget();
            assertEquals("default", target.getName());
            assertEquals(ChannelTarget.TargetType.GROUP, target.getType());
            assertEquals("group1", target.getTargetId());
        }

        @Test
        @DisplayName("toChannelTarget without groupId → AGENT type")
        void withoutGroupId() {
            var legacy = new LegacyTarget("agent1", "token", "secret", null);
            var target = legacy.toChannelTarget();
            assertEquals("default", target.getName());
            assertEquals(ChannelTarget.TargetType.AGENT, target.getType());
            assertEquals("agent1", target.getTargetId());
        }
    }

    // ─── ResolvedTarget record ──────────────────────────────────────────

    @Nested
    @DisplayName("ResolvedTarget")
    class ResolvedTargetTests {

        @Test
        @DisplayName("botToken from integration platformConfig")
        void botTokenFromIntegration() {
            var integration = new ChannelIntegrationConfiguration();
            integration.setPlatformConfig(Map.of("botToken", "xoxb-123"));
            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", integration, "legacy-token", null);
            assertEquals("xoxb-123", resolved.botToken());
        }

        @Test
        @DisplayName("botToken falls back to legacyBotToken when no integration")
        void botTokenFallbackNull() {
            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", null, "legacy-token", null);
            assertEquals("legacy-token", resolved.botToken());
        }

        @Test
        @DisplayName("botToken falls back when null platformConfig")
        void botTokenNullPlatformConfig() {
            var integration = new ChannelIntegrationConfiguration();
            integration.setPlatformConfig(null);
            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", integration, "legacy-token", null);
            assertNull(resolved.botToken());
        }

        @Test
        @DisplayName("signingSecret from integration platformConfig")
        void signingSecretFromIntegration() {
            var integration = new ChannelIntegrationConfiguration();
            integration.setPlatformConfig(Map.of("signingSecret", "sec123"));
            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", integration, null, "legacy-secret");
            assertEquals("sec123", resolved.signingSecret());
        }

        @Test
        @DisplayName("signingSecret falls back to legacy")
        void signingSecretFallback() {
            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", null, null, "legacy-secret");
            assertEquals("legacy-secret", resolved.signingSecret());
        }

        @Test
        @DisplayName("signingSecret null platformConfig falls to legacy")
        void signingSecretNullPlatformConfig() {
            var integration = new ChannelIntegrationConfiguration();
            integration.setPlatformConfig(null);
            var target = new ChannelTarget();
            var resolved = new ResolvedTarget(target, "msg", integration, null, "legacy-secret");
            assertNull(resolved.signingSecret());
        }
    }

    // ─── resolveTarget ──────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveTarget")
    class ResolveTarget {

        @Test
        @DisplayName("no integration found → null")
        void noIntegration() {
            var result = router.resolveTarget("slack", "C123", "hello");
            assertNull(result);
        }

        @Test
        @DisplayName("null channelType → empty normalized type")
        void nullChannelType() {
            var result = router.resolveTarget(null, "C123", "hello");
            assertNull(result);
        }
    }

    // ─── resolveThreadTarget ────────────────────────────────────────────

    @Nested
    @DisplayName("resolveThreadTarget")
    class ResolveThreadTarget {

        @Test
        @DisplayName("no locked target → null")
        void noLockedTarget() {
            when(threadTargetLock.get(anyString())).thenReturn(null);
            var result = router.resolveThreadTarget("slack", "C123", "ts1");
            assertNull(result);
        }

        @Test
        @DisplayName("locked target exists → returns resolved target")
        void lockedTargetExists() {
            var channelTarget = new ChannelTarget();
            channelTarget.setName("test");
            when(threadTargetLock.get("slack:C123:ts1")).thenReturn(channelTarget);

            var result = router.resolveThreadTarget("slack", "C123", "ts1");
            assertNotNull(result);
            assertEquals(channelTarget, result.target());
        }

        @Test
        @DisplayName("null channelType → empty normalization")
        void nullChannelType() {
            when(threadTargetLock.get(":C123:ts1")).thenReturn(null);
            var result = router.resolveThreadTarget(null, "C123", "ts1");
            assertNull(result);
        }
    }

    // ─── lockThreadTarget ───────────────────────────────────────────────

    @Nested
    @DisplayName("lockThreadTarget")
    class LockThreadTarget {

        @Test
        @DisplayName("lock stores target in cache")
        void lockStoresTarget() {
            var target = new ChannelTarget();
            router.lockThreadTarget("slack", "C123", "ts1", target);
            verify(threadTargetLock).put("slack:C123:ts1", target);
        }

        @Test
        @DisplayName("null channelType → empty normalization")
        void nullChannelType() {
            var target = new ChannelTarget();
            router.lockThreadTarget(null, "C123", "ts1", target);
            verify(threadTargetLock).put(":C123:ts1", target);
        }
    }

    // ─── getSigningSecrets ──────────────────────────────────────────────

    @Nested
    @DisplayName("getSigningSecrets")
    class GetSigningSecrets {

        @Test
        @DisplayName("slack → returns slackSigningSecrets")
        void slackSecrets() {
            var result = router.getSigningSecrets("slack");
            assertNotNull(result);
        }

        @Test
        @DisplayName("other channel type → empty set")
        void otherType() {
            var result = router.getSigningSecrets("teams");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null channel type → empty set")
        void nullType() {
            var result = router.getSigningSecrets(null);
            assertTrue(result.isEmpty());
        }
    }

    // ─── getIntegration ─────────────────────────────────────────────────

    @Nested
    @DisplayName("getIntegration")
    class GetIntegration {

        @Test
        @DisplayName("no match → empty")
        void noMatch() {
            var result = router.getIntegration("slack", "unknown");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null channelType → empty normalization")
        void nullType() {
            var result = router.getIntegration(null, "C123");
            assertTrue(result.isEmpty());
        }
    }

    // ─── resolveDefaultForDm ────────────────────────────────────────────

    @Nested
    @DisplayName("resolveDefaultForDm")
    class ResolveDefaultForDm {

        @Test
        @DisplayName("no integration → null (empty maps)")
        void noIntegration() {
            var result = router.resolveDefaultForDm("slack", "hello");
            assertNull(result);
        }

        @Test
        @DisplayName("null channelType → empty prefix")
        void nullChannelType() {
            var result = router.resolveDefaultForDm(null, "hello");
            assertNull(result);
        }
    }
}
