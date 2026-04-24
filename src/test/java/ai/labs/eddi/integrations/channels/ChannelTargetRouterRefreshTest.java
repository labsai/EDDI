package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ChannelConnector;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ChannelTargetRouter} covering the full public API: refresh
 * logic, secret resolution, legacy fallback, deep copy safety, and channel
 * detection.
 * <p>
 * Complements {@link ChannelTargetRouterTest} which covers trigger matching.
 */
class ChannelTargetRouterRefreshTest {

    // A valid hex ID for extractResourceId (must be ≥18 hex chars)
    private static final String CHANNEL_CONFIG_ID = "5262b802dc6c4008b54c";
    private static final String AGENT_ID = "a1b2c3d4e5f6a7b8c9d0";
    private static final String CHANNEL_ID = "C07TESTCHANNEL";

    private IChannelIntegrationStore channelStore;
    private IDocumentDescriptorStore descriptorStore;
    private IRestAgentAdministration agentAdmin;
    private IRestAgentStore agentStore;
    private SecretResolver secretResolver;
    private ChannelTargetRouter router;

    @BeforeEach
    void setUp() throws Exception {
        channelStore = mock(IChannelIntegrationStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        agentAdmin = mock(IRestAgentAdministration.class);
        agentStore = mock(IRestAgentStore.class);
        secretResolver = mock(SecretResolver.class);

        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.<String, ChannelTarget>getCache(eq("channel-thread-locks"), any(Duration.class)))
                .thenReturn(new MapCache<>());

        router = new ChannelTargetRouter(channelStore, descriptorStore, agentAdmin, agentStore,
                secretResolver, cacheFactory);

        // Default: no legacy agents
        when(agentAdmin.getDeploymentStatuses(any())).thenReturn(List.of());
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Set up the store mocks to return a single channel integration config.
     */
    private ChannelIntegrationConfiguration setupNewStyleConfig(String channelId,
                                                                String botToken,
                                                                String signingSecret)
            throws Exception {
        var config = new ChannelIntegrationConfiguration();
        config.setName("Test Slack Channel");
        config.setChannelType("slack");
        config.setPlatformConfig(new HashMap<>(Map.of(
                "channelId", channelId,
                "botToken", botToken,
                "signingSecret", signingSecret)));
        config.setDefaultTargetName("default-agent");

        var target = new ChannelTarget();
        target.setName("default-agent");
        target.setTriggers(List.of("agent"));
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTargetId(AGENT_ID);
        config.setTargets(List.of(target));

        // Wire descriptor → config
        var descriptor = new DocumentDescriptor();
        URI resourceUri = URI.create("eddi://ai.labs.channel/channelstore/channels/"
                + CHANNEL_CONFIG_ID + "?version=1");
        descriptor.setResource(resourceUri);
        when(descriptorStore.readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(descriptor));
        when(channelStore.read(eq(CHANNEL_CONFIG_ID), eq(1)))
                .thenReturn(config);

        // Secret resolver: pass through (or resolve vault refs)
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> {
            String val = inv.getArgument(0);
            if (val.startsWith("${eddivault:")) {
                return "resolved-" + val.substring(12, val.length() - 1);
            }
            return val;
        });

        return config;
    }

    /**
     * Set up a legacy ChannelConnector on a deployed agent.
     */
    private void setupLegacyAgent(String agentId, String channelId, String botToken,
                                  String signingSecret, String groupId)
            throws Exception {
        var connector = new ChannelConnector();
        connector.setType(URI.create("slack"));
        var connConfig = new HashMap<String, String>();
        connConfig.put("channelId", channelId);
        connConfig.put("botToken", botToken);
        connConfig.put("signingSecret", signingSecret);
        if (groupId != null)
            connConfig.put("groupId", groupId);
        connector.setConfig(connConfig);

        var agentConfig = new AgentConfiguration();
        agentConfig.setChannels(List.of(connector));

        var desc = new DocumentDescriptor();
        desc.setDeleted(false);

        var status = new AgentDeploymentStatus(
                Deployment.Environment.production, agentId, 1,
                Deployment.Status.READY, desc);

        when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                .thenReturn(List.of(status));
        when(agentStore.readAgent(eq(agentId), eq(1))).thenReturn(agentConfig);
    }

    // ─── Public API — resolveTarget ────────────────────────────────────────────

    @Nested
    @DisplayName("resolveTarget — new-style integration")
    class ResolveTargetNewStyle {

        @Test
        @DisplayName("returns target for known channel with trigger match")
        void triggerMatchViaPublicApi() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "xoxb-token", "signing123");

            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "agent: deploy now");

            assertNotNull(result);
            assertEquals("default-agent", result.target().getName());
            assertEquals("deploy now", result.strippedMessage());
        }

        @Test
        @DisplayName("returns default target for plain message")
        void defaultTargetViaPublicApi() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "xoxb-token", "signing123");

            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "how do I deploy?");

            assertNotNull(result);
            assertEquals("default-agent", result.target().getName());
            assertEquals("how do I deploy?", result.strippedMessage());
        }

        @Test
        @DisplayName("returns null for unknown channel")
        void unknownChannelReturnsNull() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "xoxb-token", "signing123");

            ResolvedTarget result = router.resolveTarget("slack", "UNKNOWN_CHANNEL", "hello");

            assertNull(result);
        }

        @Test
        @DisplayName("botToken() returns resolved secret from integration config")
        void botTokenResolved() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "${eddivault:slack-bot-token}", "signing123");

            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "hello");

            assertNotNull(result);
            assertEquals("resolved-slack-bot-token", result.botToken());
        }

        @Test
        @DisplayName("signingSecret() returns resolved secret from integration config")
        void signingSecretResolved() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "xoxb-token", "${eddivault:slack-signing}");

            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "hello");

            assertNotNull(result);
            assertEquals("resolved-slack-signing", result.signingSecret());
        }
    }

    // ─── Legacy fallback ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveTarget — legacy fallback")
    class LegacyFallback {

        @Test
        @DisplayName("legacy agent routes correctly when no new-style config exists")
        void legacyFallbackWorks() throws Exception {
            // No new-style configs
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            setupLegacyAgent(AGENT_ID, CHANNEL_ID, "xoxb-legacy", "sign-legacy", null);
            when(secretResolver.resolveValue("xoxb-legacy")).thenReturn("xoxb-legacy");
            when(secretResolver.resolveValue("sign-legacy")).thenReturn("sign-legacy");

            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "hello");

            assertNotNull(result);
            assertEquals(ChannelTarget.TargetType.AGENT, result.target().getType());
            assertEquals(AGENT_ID, result.target().getTargetId());
            assertEquals("xoxb-legacy", result.legacyBotToken());
        }

        @Test
        @DisplayName("legacy agent with groupId routes to GROUP type")
        void legacyGroupRouting() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            setupLegacyAgent(AGENT_ID, CHANNEL_ID, "xoxb-leg", "sign-leg", "group-123");
            when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "hello");

            assertNotNull(result);
            assertEquals(ChannelTarget.TargetType.GROUP, result.target().getType());
            assertEquals("group-123", result.target().getTargetId());
        }

        @Test
        @DisplayName("new-style config suppresses legacy for same channelId")
        void newStyleSuppressesLegacy() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "xoxb-new", "sign-new");
            setupLegacyAgent(AGENT_ID, CHANNEL_ID, "xoxb-legacy", "sign-legacy", null);

            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "hello");

            assertNotNull(result);
            // Should come from new-style config, not legacy
            assertEquals("default-agent", result.target().getName());
            assertEquals("xoxb-new", result.botToken());
        }
    }

    // ─── Signing secrets ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSigningSecrets")
    class SigningSecrets {

        @Test
        @DisplayName("returns resolved signing secrets from new-style configs")
        void newStyleSigningSecrets() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "xoxb-token", "${eddivault:slack-signing}");

            Set<String> secrets = router.getSigningSecrets("slack");

            assertFalse(secrets.isEmpty());
            assertTrue(secrets.contains("resolved-slack-signing"));
            assertFalse(secrets.contains("${eddivault:slack-signing}"),
                    "Should contain resolved secret, not vault reference");
        }

        @Test
        @DisplayName("includes legacy signing secrets")
        void legacySigningSecrets() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            setupLegacyAgent(AGENT_ID, CHANNEL_ID, "xoxb-leg", "sign-legacy", null);
            when(secretResolver.resolveValue("xoxb-leg")).thenReturn("xoxb-leg");
            when(secretResolver.resolveValue("sign-legacy")).thenReturn("sign-legacy-resolved");

            Set<String> secrets = router.getSigningSecrets("slack");

            assertTrue(secrets.contains("sign-legacy-resolved"));
        }

        @Test
        @DisplayName("returns empty for non-slack channel type")
        void nonSlackReturnsEmpty() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "token", "secret");

            Set<String> secrets = router.getSigningSecrets("teams");

            assertTrue(secrets.isEmpty());
        }
    }

    // ─── Channel detection ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("hasAnyChannels & getIntegration")
    class ChannelDetection {

        @Test
        @DisplayName("hasAnyChannels returns true for slack with new-style config")
        void hasSlackChannels() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "token", "secret");

            assertTrue(router.hasAnyChannels("slack"));
        }

        @Test
        @DisplayName("hasAnyChannels returns true for slack with legacy only")
        void hasLegacyChannels() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            setupLegacyAgent(AGENT_ID, CHANNEL_ID, "token", "secret", null);
            when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

            assertTrue(router.hasAnyChannels("slack"));
        }

        @Test
        @DisplayName("hasAnyChannels returns false for teams with only slack configs")
        void noTeamsChannels() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "token", "secret");

            assertFalse(router.hasAnyChannels("teams"));
        }

        @Test
        @DisplayName("getIntegration returns config for known channel")
        void getIntegrationKnownChannel() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "token", "secret");

            Optional<ChannelIntegrationConfiguration> result = router.getIntegration("slack", CHANNEL_ID);

            assertTrue(result.isPresent());
            assertEquals("Test Slack Channel", result.get().getName());
        }

        @Test
        @DisplayName("getIntegration returns empty for unknown channel")
        void getIntegrationUnknownChannel() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "token", "secret");

            Optional<ChannelIntegrationConfiguration> result = router.getIntegration("slack", "UNKNOWN");

            assertTrue(result.isEmpty());
        }
    }

    // ─── Deep copy safety ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Deep copy safety")
    class DeepCopySafety {

        @Test
        @DisplayName("resolved config does not mutate store's cached original")
        void resolvedDoesNotMutateOriginal() throws Exception {
            var original = setupNewStyleConfig(CHANNEL_ID, "${eddivault:bot-token}", "${eddivault:signing}");

            // Trigger refresh
            router.resolveTarget("slack", CHANNEL_ID, "hello");

            // Original should still have vault references
            assertEquals("${eddivault:bot-token}", original.getPlatformConfig().get("botToken"));
            assertEquals("${eddivault:signing}", original.getPlatformConfig().get("signingSecret"));
        }

        @Test
        @DisplayName("returned integration has resolved secrets")
        void returnedConfigHasResolvedSecrets() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "${eddivault:bot-token}", "${eddivault:signing}");

            Optional<ChannelIntegrationConfiguration> result = router.getIntegration("slack", CHANNEL_ID);

            assertTrue(result.isPresent());
            assertEquals("resolved-bot-token", result.get().getPlatformConfig().get("botToken"));
        }
    }

    // ─── Refresh mechanism ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Refresh mechanism")
    class RefreshMechanism {

        @Test
        @DisplayName("refresh loads data on first call")
        void refreshOnFirstCall() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "token", "secret");

            // First call triggers refresh
            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "hello");

            assertNotNull(result);
            verify(descriptorStore, times(1))
                    .readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean());
        }

        @Test
        @DisplayName("rapid successive calls do not re-refresh")
        void noReRefreshWithinInterval() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "token", "secret");

            // Two rapid calls
            router.resolveTarget("slack", CHANNEL_ID, "first");
            router.resolveTarget("slack", CHANNEL_ID, "second");

            // Only one refresh
            verify(descriptorStore, times(1))
                    .readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean());
        }

        @Test
        @DisplayName("refresh handles store exception gracefully")
        void refreshHandlesStoreException() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenThrow(new RuntimeException("DB down"));

            // Should not throw
            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "hello");

            assertNull(result); // No configs loaded
        }

        @Test
        @DisplayName("refresh handles null channelId gracefully")
        void refreshSkipsNullChannelId() throws Exception {
            var config = new ChannelIntegrationConfiguration();
            config.setName("No ChannelId Config");
            config.setChannelType("slack");
            config.setPlatformConfig(new HashMap<>(Map.of("botToken", "tok")));
            // No channelId in platformConfig

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/"
                    + CHANNEL_CONFIG_ID + "?version=1"));
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor));
            when(channelStore.read(eq(CHANNEL_CONFIG_ID), eq(1))).thenReturn(config);

            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "hello");

            assertNull(result); // Config skipped due to missing channelId
        }

        @Test
        @DisplayName("refresh handles null config from store")
        void refreshHandlesNullConfig() throws Exception {
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/"
                    + CHANNEL_CONFIG_ID + "?version=1"));
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor));
            when(channelStore.read(eq(CHANNEL_CONFIG_ID), eq(1))).thenReturn(null);

            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "hello");

            assertNull(result);
        }
    }

    // ─── Secret resolution ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Secret resolution")
    class SecretResolution {

        @Test
        @DisplayName("null secret value resolves to null")
        void nullSecret() throws Exception {
            var config = new ChannelIntegrationConfiguration();
            config.setName("Test");
            config.setChannelType("slack");
            config.setPlatformConfig(new HashMap<>(Map.of("channelId", CHANNEL_ID)));
            config.setDefaultTargetName("default");

            var target = new ChannelTarget();
            target.setName("default");
            target.setTargetId(AGENT_ID);
            target.setTriggers(List.of("default"));
            config.setTargets(List.of(target));

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/"
                    + CHANNEL_CONFIG_ID + "?version=1"));
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor));
            when(channelStore.read(eq(CHANNEL_CONFIG_ID), eq(1))).thenReturn(config);
            when(secretResolver.resolveValue(CHANNEL_ID)).thenReturn(CHANNEL_ID);

            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "hello");

            // Should resolve without error even though botToken/signingSecret are absent
            assertNotNull(result);
        }

        @Test
        @DisplayName("secret resolver failure returns null for that secret")
        void secretResolverFailure() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "xoxb-good", "sign-good");
            // Override: make resolver throw for one key
            when(secretResolver.resolveValue("xoxb-good")).thenThrow(new RuntimeException("vault down"));
            when(secretResolver.resolveValue("sign-good")).thenReturn("sign-resolved");
            when(secretResolver.resolveValue(CHANNEL_ID)).thenReturn(CHANNEL_ID);

            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "hello");

            assertNotNull(result);
            // botToken should be null (failed resolution), signing should work
            assertNull(result.botToken());
            assertEquals("sign-resolved", result.signingSecret());
        }
    }

    // ─── ResolvedTarget record ─────────────────────────────────────────────────

    @Nested
    @DisplayName("ResolvedTarget credential accessors")
    class ResolvedTargetAccessors {

        @Test
        @DisplayName("botToken() falls back to legacyBotToken when integration is null")
        void botTokenFallback() {
            var target = new ChannelTarget();
            target.setName("test");
            var resolved = new ResolvedTarget(target, "msg", null, "legacy-bot", null);

            assertEquals("legacy-bot", resolved.botToken());
        }

        @Test
        @DisplayName("signingSecret() falls back to legacySigningSecret when integration is null")
        void signingSecretFallback() {
            var target = new ChannelTarget();
            target.setName("test");
            var resolved = new ResolvedTarget(target, "msg", null, null, "legacy-sign");

            assertEquals("legacy-sign", resolved.signingSecret());
        }

        @Test
        @DisplayName("botToken() prefers integration over legacy")
        void botTokenPrefersIntegration() {
            var target = new ChannelTarget();
            target.setName("test");
            var integration = new ChannelIntegrationConfiguration();
            integration.setPlatformConfig(Map.of("botToken", "integration-bot"));
            var resolved = new ResolvedTarget(target, "msg", integration, "legacy-bot", null);

            assertEquals("integration-bot", resolved.botToken());
        }

        @Test
        @DisplayName("botToken() returns null when both sources are null")
        void botTokenBothNull() {
            var target = new ChannelTarget();
            target.setName("test");
            var resolved = new ResolvedTarget(target, "msg", null, null, null);

            assertNull(resolved.botToken());
        }
    }

    // ─── LegacyTarget record ───────────────────────────────────────────────────

    @Nested
    @DisplayName("LegacyTarget.toChannelTarget")
    class LegacyTargetConversion {

        @Test
        @DisplayName("without groupId → AGENT type")
        void withoutGroupId() {
            var legacy = new ChannelTargetRouter.LegacyTarget("agent-1", "tok", "sign", null);
            ChannelTarget target = legacy.toChannelTarget();

            assertEquals("default", target.getName());
            assertEquals(ChannelTarget.TargetType.AGENT, target.getType());
            assertEquals("agent-1", target.getTargetId());
        }

        @Test
        @DisplayName("with groupId → GROUP type")
        void withGroupId() {
            var legacy = new ChannelTargetRouter.LegacyTarget("agent-1", "tok", "sign", "group-abc");
            ChannelTarget target = legacy.toChannelTarget();

            assertEquals("default", target.getName());
            assertEquals(ChannelTarget.TargetType.GROUP, target.getType());
            assertEquals("group-abc", target.getTargetId());
        }
    }

    // ─── resolveThreadTarget with legacy credentials ──────────────────────────

    @Nested
    @DisplayName("resolveThreadTarget with legacy credentials")
    class ThreadTargetLegacyCredentials {

        @Test
        @DisplayName("thread target attaches legacy credentials when no new-style config exists")
        void legacyCredentialsAttached() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            setupLegacyAgent(AGENT_ID, CHANNEL_ID, "xoxb-leg", "sign-leg", null);
            when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

            // Force refresh
            router.hasAnyChannels("slack");

            // Lock a thread target
            var target = new ChannelTarget();
            target.setName("locked-target");
            router.lockThreadTarget("slack", CHANNEL_ID, "thread-1", target);

            ResolvedTarget result = router.resolveThreadTarget("slack", CHANNEL_ID, "thread-1");

            assertNotNull(result);
            assertEquals("locked-target", result.target().getName());
            assertEquals("xoxb-leg", result.legacyBotToken());
            assertEquals("sign-leg", result.legacySigningSecret());
        }

        @Test
        @DisplayName("thread target has null legacy credentials when new-style config exists")
        void noLegacyWhenNewStyleExists() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "xoxb-new", "sign-new");

            // Lock a thread target
            var target = new ChannelTarget();
            target.setName("locked-target");
            router.lockThreadTarget("slack", CHANNEL_ID, "thread-1", target);

            ResolvedTarget result = router.resolveThreadTarget("slack", CHANNEL_ID, "thread-1");

            assertNotNull(result);
            assertNull(result.legacyBotToken());
            assertNull(result.legacySigningSecret());
            assertNotNull(result.integration());
        }
    }

    // ─── Refresh edge cases for legacy agents ─────────────────────────────────

    @Nested
    @DisplayName("Legacy refresh edge cases")
    class LegacyRefreshEdgeCases {

        @Test
        @DisplayName("agent with deleted descriptor → skipped")
        void deletedDescriptorSkipped() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            var desc = new DocumentDescriptor();
            desc.setDeleted(true);

            var status = new AgentDeploymentStatus(
                    Deployment.Environment.production, AGENT_ID, 1,
                    Deployment.Status.READY, desc);
            when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                    .thenReturn(List.of(status));

            assertNull(router.resolveTarget("slack", CHANNEL_ID, "hello"));
        }

        @Test
        @DisplayName("agent with null descriptor → skipped")
        void nullDescriptorSkipped() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            var status = new AgentDeploymentStatus(
                    Deployment.Environment.production, AGENT_ID, 1,
                    Deployment.Status.READY, null);
            when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                    .thenReturn(List.of(status));

            assertNull(router.resolveTarget("slack", CHANNEL_ID, "hello"));
        }

        @Test
        @DisplayName("agent with non-slack connector type → skipped in legacy path")
        void nonSlackConnectorSkipped() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            var connector = new ChannelConnector();
            connector.setType(URI.create("teams")); // not slack
            connector.setConfig(Map.of("channelId", CHANNEL_ID, "botToken", "tok"));

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));

            var desc = new DocumentDescriptor();
            desc.setDeleted(false);
            var status = new AgentDeploymentStatus(
                    Deployment.Environment.production, AGENT_ID, 1,
                    Deployment.Status.READY, desc);
            when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                    .thenReturn(List.of(status));
            when(agentStore.readAgent(eq(AGENT_ID), eq(1))).thenReturn(agentConfig);

            assertNull(router.resolveTarget("slack", CHANNEL_ID, "hello"));
            assertFalse(router.hasAnyChannels("slack"));
        }

        @Test
        @DisplayName("connector with null config → skipped")
        void connectorNullConfigSkipped() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            var connector = new ChannelConnector();
            connector.setType(URI.create("slack"));
            connector.setConfig(null);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));

            var desc = new DocumentDescriptor();
            desc.setDeleted(false);
            var status = new AgentDeploymentStatus(
                    Deployment.Environment.production, AGENT_ID, 1,
                    Deployment.Status.READY, desc);
            when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                    .thenReturn(List.of(status));
            when(agentStore.readAgent(eq(AGENT_ID), eq(1))).thenReturn(agentConfig);

            assertNull(router.resolveTarget("slack", CHANNEL_ID, "hello"));
        }

        @Test
        @DisplayName("legacy agent with blank groupId → AGENT type (not GROUP)")
        void blankGroupIdIsAgent() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            setupLegacyAgent(AGENT_ID, CHANNEL_ID, "xoxb-tok", "sign-sec", null);
            // The helper passes null for groupId, so set up an agent with blank groupId
            // explicitly
            var connector = new ChannelConnector();
            connector.setType(URI.create("slack"));
            var cfg = new HashMap<String, String>();
            cfg.put("channelId", CHANNEL_ID);
            cfg.put("botToken", "xoxb-tok");
            cfg.put("signingSecret", "sign-sec");
            cfg.put("groupId", "   "); // blank, not null
            connector.setConfig(cfg);

            var agentConfig = new AgentConfiguration();
            agentConfig.setChannels(List.of(connector));

            var desc = new DocumentDescriptor();
            desc.setDeleted(false);
            var status = new AgentDeploymentStatus(
                    Deployment.Environment.production, AGENT_ID, 1,
                    Deployment.Status.READY, desc);
            when(agentAdmin.getDeploymentStatuses(Deployment.Environment.production))
                    .thenReturn(List.of(status));
            when(agentStore.readAgent(eq(AGENT_ID), eq(1))).thenReturn(agentConfig);
            when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

            ResolvedTarget result = router.resolveTarget("slack", CHANNEL_ID, "hello");
            assertNotNull(result);
            assertEquals(ChannelTarget.TargetType.AGENT, result.target().getType());
        }
    }

    // ─── getBotToken ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getBotToken — unified token lookup")
    class GetBotTokenTests {

        @Test
        @DisplayName("returns bot token from new-style integration")
        void newStyleToken() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "xoxb-integration", "signing123");

            String token = router.getBotToken("slack", CHANNEL_ID);

            assertEquals("xoxb-integration", token);
        }

        @Test
        @DisplayName("returns bot token from legacy when no new-style config exists")
        void legacyFallbackToken() throws Exception {
            when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());
            setupLegacyAgent(AGENT_ID, CHANNEL_ID, "xoxb-legacy", "sign", null);
            when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

            String token = router.getBotToken("slack", CHANNEL_ID);

            assertEquals("xoxb-legacy", token);
        }

        @Test
        @DisplayName("new-style token takes precedence over legacy")
        void newStylePrecedence() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "xoxb-new", "sign-new");
            setupLegacyAgent(AGENT_ID, CHANNEL_ID, "xoxb-legacy", "sign-legacy", null);

            String token = router.getBotToken("slack", CHANNEL_ID);

            assertEquals("xoxb-new", token);
        }

        @Test
        @DisplayName("returns null for unknown channel")
        void unknownChannelReturnsNull() throws Exception {
            setupNewStyleConfig(CHANNEL_ID, "xoxb-token", "signing");

            String token = router.getBotToken("slack", "UNKNOWN_CHANNEL");

            assertNull(token);
        }
    }

    // ─── Test helper: simple ConcurrentHashMap-based ICache ─────────────────

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
