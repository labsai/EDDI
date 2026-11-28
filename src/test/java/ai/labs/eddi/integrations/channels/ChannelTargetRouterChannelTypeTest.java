/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.api.IDeploymentStatusReader;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelTargetRouter} with a third, connection-backed channel type
 * registered.
 */
@SuppressWarnings("unchecked")
class ChannelTargetRouterChannelTypeTest {

    private IChannelIntegrationStore channelStore;
    private IDocumentDescriptorStore descriptorStore;
    private SecretResolver secretResolver;
    private ChannelTargetRouter router;
    private IDeploymentStatusReader agentAdmin;
    private ICacheFactory cacheFactory;
    private final List<DocumentDescriptor> descriptors = new ArrayList<>();
    private int nextId = 0;

    @BeforeEach
    void setUp() throws Exception {
        channelStore = mock(IChannelIntegrationStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(anyString(), any()))
                .thenAnswer(inv -> "resolved-" + inv.getArgument(0, String.class).replace("${vault:", "").replace("}", ""));
        agentAdmin = mock(IDeploymentStatusReader.class);
        when(agentAdmin.readAllDeploymentStatuses(any())).thenReturn(List.of());
        cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.<String, ChannelTarget>getCache(anyString(), any(Duration.class))).thenReturn(mock(ICache.class));
        when(descriptorStore.readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean())).thenReturn(descriptors);
        router = routerWith(FakeChatChannelType.registry());
    }

    private ChannelTargetRouter routerWith(ChannelTypeRegistry registry) {
        return new ChannelTargetRouter(channelStore, descriptorStore, agentAdmin, mock(IAgentStore.class), secretResolver,
                mock(VaultHostAllowlist.class), cacheFactory, registry);
    }

    private ChannelIntegrationConfiguration store(String type, String name, Map<String, String> platformConfig) throws Exception {
        String id = String.format("5262b802dc6c4008b5%02x", nextId++);
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/" + id + "?version=1"));
        descriptors.add(descriptor);
        var config = new ChannelIntegrationConfiguration();
        config.setName(name);
        config.setChannelType(type);
        config.setDefaultTargetName("assistant");
        var target = new ChannelTarget();
        target.setName("assistant");
        target.setTargetId("agent-" + name);
        target.setType(ChannelTarget.TargetType.AGENT);
        config.setTargets(List.of(target));
        config.setPlatformConfig(platformConfig);
        when(channelStore.read(id, 1)).thenReturn(config);
        return config;
    }

    private static Map<String, String> pc(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("pc(...) takes key/value pairs, got " + kv.length + " arguments");
        }
        var map = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("a connection-backed type gets nothing resolved from the vault, even in a key called botToken")
    void noVaultResolution() throws Exception {
        store(FakeChatChannelType.ID, "fake", pc("channelId", "C1", "connection", "bot", "appId", "${vault:app-id}",
                "botToken", "${vault:bot-token}", "signingSecret", "${vault:signing}"));

        var integration = router.getIntegration(FakeChatChannelType.ID, "C1").orElseThrow();
        Map<String, String> platformConfig = integration.getPlatformConfig();
        assertEquals("${vault:app-id}", platformConfig.get("appId"), "an allowed key stays literal");
        assertFalse(platformConfig.containsKey("botToken"), "a key outside the allow-list is dropped");
        assertFalse(platformConfig.containsKey("signingSecret"));
        assertEquals("bot", platformConfig.get("connection"));
        verify(secretResolver, never()).resolveValue(anyString(), any());
    }

    @Test
    @DisplayName("Slack keeps resolving its two credential keys")
    void slackUnchanged() throws Exception {
        store("slack", "slack", pc("channelId", "C2", "botToken", "${vault:bot}", "signingSecret", "${vault:sig}"));
        var resolved = router.resolveTarget("slack", "C2", "hello");
        assertNotNull(resolved);
        assertEquals("resolved-bot", resolved.botToken());
        assertEquals("resolved-sig", resolved.signingSecret());
    }

    @Test
    @DisplayName("a connection-backed type adds no signing secret to the pool")
    void noSigningSecretPooled() throws Exception {
        store(FakeChatChannelType.ID, "fake", pc("channelId", "C1", "connection", "bot", "appId", "a", "signingSecret", "plain"));
        assertTrue(router.getSigningSecrets(FakeChatChannelType.ID).isEmpty());
    }

    // ─── Phase 0.4–0.5: routing keys per type, inbound origin ───────────────────

    private static ChannelTarget targetOf(ChannelIntegrationConfiguration integration) {
        return integration.getTargets().get(0);
    }

    @Test
    @DisplayName("an integration without a channel is indexed under its application and routed by its verified origin")
    void channelLessIntegrationRouted() throws Exception {
        var stored = store(FakeChatChannelType.ID, "fake", pc("connection", "bot", "appId", "app-1"));

        var resolved = router.resolveDefaultForOrigin(InboundOrigin.application(FakeChatChannelType.ID, "app-1"), "hello");
        assertNotNull(resolved, "a Teams-style personal chat must find its integration");
        assertEquals(targetOf(stored).getTargetId(), resolved.target().getTargetId());

        assertTrue(router.getIntegrationByName(FakeChatChannelType.ID, "fake").isPresent());
        assertTrue(router.getIntegrationForApplication(FakeChatChannelType.ID, "app-1").isPresent());
        assertTrue(router.hasAnyChannels(FakeChatChannelType.ID));
        assertNull(router.resolveDefaultForDm(FakeChatChannelType.ID, "hello"),
                "'the first integration of the type' is no owner for a connection-backed type");
    }

    @Test
    @DisplayName("an application id no integration declares resolves to nothing, not to another integration")
    void unknownApplicationFailsClosed() throws Exception {
        store(FakeChatChannelType.ID, "fake", pc("connection", "bot", "appId", "app-1"));
        assertNull(router.resolveDefaultForOrigin(InboundOrigin.application(FakeChatChannelType.ID, "app-2"), "hello"));
        assertTrue(router.getIntegrationForApplication(FakeChatChannelType.ID, "app-2").isEmpty());
        assertTrue(router.getIntegrationForApplication(FakeChatChannelType.ID, " ").isEmpty());
    }

    @Test
    @DisplayName("a channel-bound integration does not answer its application's DMs: several may declare one application")
    void channelBoundDoesNotOwnApplication() throws Exception {
        store(FakeChatChannelType.ID, "tenant-a", pc("connection", "bot", "appId", "app-1", "channelId", "C1"));
        store(FakeChatChannelType.ID, "tenant-b", pc("connection", "bot", "appId", "app-1", "channelId", "C2"));
        assertTrue(router.getIntegration(FakeChatChannelType.ID, "C1").isPresent());
        assertTrue(router.getIntegrationForApplication(FakeChatChannelType.ID, "app-1").isEmpty());
        assertNull(router.resolveDefaultForOrigin(InboundOrigin.application(FakeChatChannelType.ID, "app-1"), "hello"));
    }

    @Test
    @DisplayName("a channel lookup never answers with an application key")
    void channelLookupIgnoresApplicationKeys() throws Exception {
        store(FakeChatChannelType.ID, "fake", pc("connection", "bot", "appId", "app-1"));
        String bodyChannelId = ChannelTypeDescriptor.applicationKey("app-1");
        assertTrue(router.getIntegration(FakeChatChannelType.ID, bodyChannelId).isEmpty());
        assertNull(router.integrationFor(FakeChatChannelType.ID, bodyChannelId));
        assertTrue(router.observeCandidates(FakeChatChannelType.ID, bodyChannelId).isEmpty());
        assertFalse(router.isChannelOwned(FakeChatChannelType.ID, bodyChannelId));
        assertNull(router.resolveTarget(FakeChatChannelType.ID, bodyChannelId, "hello"));
    }

    @Test
    @DisplayName("an origin with no verified identity resolves to nothing")
    void unverifiedOriginFailsClosed() throws Exception {
        store(FakeChatChannelType.ID, "fake", pc("connection", "bot", "appId", "app-1"));
        assertNull(router.resolveDefaultForOrigin(new InboundOrigin(FakeChatChannelType.ID, null, null, null), "hello"));
        assertNull(router.resolveDefaultForOrigin(InboundOrigin.signedBy(FakeChatChannelType.ID, null, "a-secret"), "hello"));
        assertNull(router.resolveDefaultForOrigin(new InboundOrigin(FakeChatChannelType.ID, "fake", null, null), "hello"),
                "a bare name may come from a URL or a request body: it proves nothing");
        assertNull(router.resolveDefaultForOrigin(null, "hello"));
    }

    @Test
    @DisplayName("a named origin with a signing secret resolves only when the named integration holds that secret")
    void namedOriginMustHoldTheSecret() throws Exception {
        store("slack", "hub", pc("channelId", "C2", "botToken", "${vault:bot}", "signingSecret", "${vault:sig}"));
        assertNotNull(router.resolveDefaultForOrigin(InboundOrigin.signedBy("slack", "hub", "resolved-sig"), "hello"));
        assertNull(router.resolveDefaultForOrigin(InboundOrigin.signedBy("slack", "hub", "resolved-other"), "hello"),
                "another app's secret must not reach this integration by naming it");
    }

    @Test
    @DisplayName("a channel-bound integration of the same application is never the DM owner, by name or by thread")
    void channelBoundOfSameApplicationIsNotTheOwner() throws Exception {
        var owner = store(FakeChatChannelType.ID, "owner", pc("connection", "bot", "appId", "app-1"));
        // A numeric channel id sorts before '@app:', so a lowest-key-first scan meets
        // it first.
        var tenantB = store(FakeChatChannelType.ID, "tenant-b", pc("connection", "bot", "appId", "app-1", "channelId", "111"));
        tenantB.setTargets(owner.getTargets());
        var locked = targetOf(owner);

        assertNull(router.resolveDefaultForOrigin(new InboundOrigin(FakeChatChannelType.ID, "tenant-b", "app-1", null), "hi"),
                "a name from a request body must not pick another tenant's channel-bound integration");
        assertNotNull(router.resolveDefaultForOrigin(new InboundOrigin(FakeChatChannelType.ID, "owner", "app-1", null), "hi"));

        var resolved = router.threadCredentialsForDm(locked, InboundOrigin.application(FakeChatChannelType.ID, "app-1"));
        assertNotNull(resolved);
        assertEquals("owner", resolved.integration().getName(), "a DM thread is the channel-less owner's, not tenant-b's");
    }

    @Test
    @DisplayName("a registry that fails to construct stops the refresh before any document is read")
    void brokenRegistrySurfaces() throws Exception {
        ChannelTypeRegistry broken = mock(ChannelTypeRegistry.class);
        when(broken.ids()).thenThrow(new IllegalStateException("Channel type 'x' is registered twice"));
        var brokenRouter = routerWith(broken);
        assertTrue(brokenRouter.getIntegrationByName(FakeChatChannelType.ID, "fake").isEmpty());
        verify(descriptorStore, never()).readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("a dropped key is counted once per integration version, not on every refresh")
    void droppedKeyCountedOnce() throws Exception {
        var meters = new SimpleMeterRegistry();
        var counted = routerWith(new ChannelTypeRegistry(List.of(new FakeChatChannelType()), meters));
        store(FakeChatChannelType.ID, "fake", pc("connection", "bot", "appId", "app-1", "channelId", "C1", "botToken", "x"));
        assertTrue(counted.getIntegration(FakeChatChannelType.ID, "C1").isPresent());
        var lastRefresh = ChannelTargetRouter.class.getDeclaredField("lastRefreshTime");
        lastRefresh.setAccessible(true);
        lastRefresh.set(counted, 0L);
        // What a vault change does: credential warnings are re-armed for rotated
        // secrets, which must not count the same dropped key again.
        var warned = ChannelTargetRouter.class.getDeclaredField("unusableCredentialWarned");
        warned.setAccessible(true);
        ((Set<?>) warned.get(counted)).clear();
        assertTrue(counted.getIntegration(FakeChatChannelType.ID, "C1").isPresent());
        verify(descriptorStore, times(2)).readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean());
        assertEquals(1, meters.find("eddi_channel_config_keys_dropped_total").tags("type", FakeChatChannelType.ID).counter().count());
    }

    @Test
    @DisplayName("an integration indexed under two keys is still one integration to the name and DM scans")
    void multiKeyIsNotAmbiguous() throws Exception {
        ChannelTypeDescriptor bothKeys = new FakeChatChannelType() {
            @Override
            public List<String> routingKeys(String resourceId, Map<String, String> platformConfig) {
                return List.of(platformConfig.get("channelId"), ChannelTypeDescriptor.applicationKey(platformConfig.get("appId")));
            }
        };
        var multiRouter = routerWith(new ChannelTypeRegistry(List.of(bothKeys)));
        store(FakeChatChannelType.ID, "fake", pc("connection", "bot", "appId", "app-1", "channelId", "C1"));

        assertTrue(multiRouter.getIntegrationByName(FakeChatChannelType.ID, "fake").isPresent(),
                "the name scan must not see one integration under two keys as two integrations");
        assertTrue(multiRouter.getIntegration(FakeChatChannelType.ID, "C1").isPresent(), "its channel still routes");
        assertTrue(multiRouter.getIntegrationForApplication(FakeChatChannelType.ID, "app-1").isEmpty(),
                "a channel-bound integration under its application key must not own the application's DMs");
        assertNull(multiRouter.resolveDefaultForOrigin(InboundOrigin.application(FakeChatChannelType.ID, "app-1"), "hi"));
    }

    @Test
    @DisplayName("a locked DM thread is re-credentialed only by an integration of the verified application with that target")
    void threadCredentialsByApplication() throws Exception {
        var stored = store(FakeChatChannelType.ID, "fake", pc("connection", "bot", "appId", "app-1"));
        var locked = targetOf(stored);

        var resolved = router.threadCredentialsForDm(locked, InboundOrigin.application(FakeChatChannelType.ID, "app-1"));
        assertNotNull(resolved);
        assertEquals("fake", resolved.integration().getName());

        assertNull(router.threadCredentialsForDm(locked, InboundOrigin.application(FakeChatChannelType.ID, "app-2")),
                "another application must not continue this thread");
        var other = new ChannelTarget();
        other.setType(ChannelTarget.TargetType.AGENT);
        other.setTargetId("agent-somebody-else");
        assertNull(router.threadCredentialsForDm(other, InboundOrigin.application(FakeChatChannelType.ID, "app-1")),
                "a target the integration does not serve must not be continued");
        assertNull(router.threadCredentialsForDm(locked, null));
    }

    @Test
    @DisplayName("the Slack signing-secret path is the same comparison through the origin overload")
    void slackSecretThroughOrigin() throws Exception {
        var stored = store("slack", "slack", pc("channelId", "C2", "botToken", "${vault:bot}", "signingSecret", "${vault:sig}"));
        var locked = targetOf(stored);
        assertNotNull(router.threadCredentialsForDm("slack", locked, "resolved-sig"));
        assertNotNull(router.threadCredentialsForDm(locked, InboundOrigin.signedBy("slack", null, "resolved-sig")));
        assertNull(router.threadCredentialsForDm("slack", locked, "resolved-other"));
        assertNull(router.threadCredentialsForDm("slack", locked, " "));
    }

    @Test
    @DisplayName("a webhook is still indexed by its resource id and an email integration not at all")
    void webhookAndEmailIndexing() throws Exception {
        store("webhook", "hook", pc());
        String webhookId = String.format("5262b802dc6c4008b5%02x", nextId - 1);
        assertEquals("hook", router.getWebhookIntegration(webhookId).orElseThrow().getName());
        store("email", "mailbox", pc("connection", "exo"));
        assertTrue(router.getIntegrationByName("email", "mailbox").isEmpty(), "the poller finds email integrations itself");
        assertFalse(router.hasAnyChannels("email"));
    }

    @Test
    @DisplayName("InboundOrigin never prints the signing secret")
    void originRedacted() {
        var origin = InboundOrigin.signedBy("slack", "hub", "super-secret-value");
        assertFalse(origin.toString().contains("super-secret-value"));
        assertTrue(origin.isVerified());
        assertFalse(new InboundOrigin("slack", null, null, null).isVerified());
        assertFalse(new InboundOrigin("slack", "hub", null, null).isVerified(), "a name alone is not a verified identity");
    }

    @Test
    @DisplayName("a name in the origin counts only when that integration is the verified application's")
    void namedOriginMustBelongToApplication() throws Exception {
        store(FakeChatChannelType.ID, "app-one", pc("connection", "bot", "appId", "app-1"));
        store(FakeChatChannelType.ID, "app-two", pc("connection", "bot", "appId", "app-2"));
        assertNotNull(router.resolveDefaultForOrigin(new InboundOrigin(FakeChatChannelType.ID, "app-one", "app-1", null), "hi"));
        assertNull(router.resolveDefaultForOrigin(new InboundOrigin(FakeChatChannelType.ID, "app-two", "app-1", null), "hi"),
                "a name taken from a request body must not reach another application's integration");
    }

    @Test
    @DisplayName("an integration of a stored type containing ':' is still found by the scans")
    void typeWithColon() throws Exception {
        store("acme:chat", "forked", pc("channelId", "C1"));
        assertTrue(router.getIntegrationByName("acme:chat", "forked").isPresent());
        assertTrue(router.hasAnyChannels("acme:chat"));
    }

    @Test
    @DisplayName("an undeclared key cannot route a connection-backed integration that bypassed save validation")
    void undeclaredKeyDoesNotRoute() throws Exception {
        ChannelTypeDescriptor routedByUndeclared = new FakeChatChannelType() {
            @Override
            public List<String> routingKeys(String resourceId, Map<String, String> platformConfig) {
                String guild = platformConfig.get("guildId");
                return guild == null ? List.of() : List.of(guild);
            }
        };
        var strict = routerWith(new ChannelTypeRegistry(List.of(routedByUndeclared)));
        store(FakeChatChannelType.ID, "fake", pc("connection", "bot", "appId", "app-1", "guildId", "G1"));
        assertTrue(strict.getIntegration(FakeChatChannelType.ID, "G1").isEmpty(), "guildId is not an allowed key, so it is dropped first");
    }
}
