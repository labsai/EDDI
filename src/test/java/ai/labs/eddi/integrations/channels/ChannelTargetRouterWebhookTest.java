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
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Webhook integrations in the router: indexing by resource id and routing by
 * event type.
 */
class ChannelTargetRouterWebhookTest {

    private static final String WEBHOOK_ID = "0123456789abcdef01234567";

    private IChannelIntegrationStore channelStore;
    private IDocumentDescriptorStore descriptorStore;
    private SecretResolver secretResolver;
    private VaultHostAllowlist vaultHostAllowlist;
    private ChannelTargetRouter router;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        channelStore = mock(IChannelIntegrationStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        secretResolver = mock(SecretResolver.class);
        vaultHostAllowlist = mock(VaultHostAllowlist.class);
        IDeploymentStatusReader agentAdmin = mock(IDeploymentStatusReader.class);
        when(agentAdmin.readAllDeploymentStatuses(any())).thenReturn(List.of());
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString(), any(Duration.class))).thenReturn(mock(ICache.class));
        router = new ChannelTargetRouter(channelStore, descriptorStore, agentAdmin, mock(IAgentStore.class), secretResolver, vaultHostAllowlist,
                cacheFactory);
    }

    private static ChannelTarget target(String name, String... triggers) {
        var target = new ChannelTarget();
        target.setName(name);
        target.setTargetId("abcdefabcdefabcdefab" + String.format("%04d", Math.abs(name.hashCode()) % 10000));
        target.setTriggers(List.of(triggers));
        return target;
    }

    private static ChannelIntegrationConfiguration webhook(ChannelTarget... targets) {
        var config = new ChannelIntegrationConfiguration();
        config.setName("hooks");
        config.setChannelType("webhook");
        config.setTargets(List.of(targets));
        config.setDefaultTargetName("default");
        return config;
    }

    @Test
    void webhookIntegrationIsIndexedByResourceIdWithResolvedSecret() throws Exception {
        var config = webhook(target("default"));
        config.setPlatformConfig(Map.of("signingSecret", "${vault:hook-secret}"));
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/" + WEBHOOK_ID + "?version=2"));
        when(descriptorStore.readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(descriptor));
        when(channelStore.read(WEBHOOK_ID, 2)).thenReturn(config);
        when(secretResolver.resolveValue("${vault:hook-secret}")).thenReturn("plain-secret-0123456789");

        var found = router.getWebhookIntegration(WEBHOOK_ID);

        assertTrue(found.isPresent());
        assertEquals("plain-secret-0123456789", found.get().getPlatformConfig().get("signingSecret"));
        // The store's own instance keeps the reference.
        assertEquals("${vault:hook-secret}", config.getPlatformConfig().get("signingSecret"));
        assertTrue(router.getWebhookIntegration("ffffffffffffffffffffffff").isEmpty());
        assertTrue(router.getIntegration("slack", WEBHOOK_ID).isEmpty());
    }

    @Test
    void exactTypeMatchWinsOverPrefixAndPrefixOverDefault() {
        var push = target("push", "push");
        var github = target("github", "com.github.*");
        var pr = target("pr", "com.github.pull_request");
        var fallback = target("default");
        var config = webhook(push, github, pr, fallback);

        assertSame(push, router.resolveEventTarget(config, "PUSH").target());
        assertSame(pr, router.resolveEventTarget(config, "com.github.pull_request").target());
        assertSame(github, router.resolveEventTarget(config, "com.github.issue").target());
        assertSame(fallback, router.resolveEventTarget(config, "com.stripe.charge").target());
        assertSame(fallback, router.resolveEventTarget(config, null).target());
    }

    @Test
    void longerPrefixWins() {
        var broad = target("broad", "com.*");
        var narrow = target("narrow", "com.github.*");
        var config = webhook(broad, narrow, target("default"));
        assertSame(narrow, router.resolveEventTarget(config, "com.github.push").target());
    }

    @Test
    void observersAreNeverRouted() {
        var observer = target("watcher", "push");
        observer.setObserveMode(true);
        var fallback = target("default");
        var config = webhook(observer, fallback);
        assertSame(fallback, router.resolveEventTarget(config, "push").target());
    }

    @Test
    void noMatchAndNoDefaultIsNull() {
        var config = webhook(target("push", "push"));
        config.setDefaultTargetName("missing");
        assertNull(router.resolveEventTarget(config, "other"));
        assertNull(router.resolveEventTarget(null, "push"));
    }

    private String signingSecretOf(String stored, String resolved) throws Exception {
        var config = webhook(target("default"));
        config.setPlatformConfig(Map.of("signingSecret", stored));
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/" + WEBHOOK_ID + "?version=2"));
        when(descriptorStore.readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(descriptor));
        when(channelStore.read(WEBHOOK_ID, 2)).thenReturn(config);
        when(secretResolver.resolveValue(anyString())).thenReturn(resolved);
        var found = router.getWebhookIntegration(WEBHOOK_ID);
        assertTrue(found.isPresent());
        return found.get().getPlatformConfig().get("signingSecret");
    }

    @Test
    void plaintextSigningSecretIsNeverHandedToTheVerifier() throws Exception {
        // Reachable through a ZIP import or a direct store write; save-time
        // validation refuses it, so the runtime must too.
        assertNull(signingSecretOf("a-plaintext-secret-long-enough", "a-plaintext-secret-long-enough"));
    }

    @Test
    void tooShortResolvedSigningSecretIsNeverHandedToTheVerifier() throws Exception {
        assertNull(signingSecretOf("${vault:hook}", "short"));
    }

    @Test
    void sufficientlyLongVaultSecretIsKept() throws Exception {
        assertEquals("0123456789abcdef", signingSecretOf("${vault:hook}", "0123456789abcdef"));
    }

    @Test
    void fifteenCharacterSecretIsStripped() throws Exception {
        assertNull(signingSecretOf("${vault:hook}", "a".repeat(15)));
        assertEquals(1, router.weakWebhookSecretWarningsLogged());
    }

    @Test
    void sixteenCharacterSecretIsKept() throws Exception {
        assertEquals("a".repeat(16), signingSecretOf("${vault:hook}", "a".repeat(16)));
        assertEquals(0, router.weakWebhookSecretWarningsLogged());
    }

    @Test
    void weakSecretWarnsOncePerRefreshCycleAndAgainAfterAVaultChange() throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<SecretReference>> listener = ArgumentCaptor.forClass(Consumer.class);
        router.registerSecretInvalidation();
        verify(secretResolver).registerInvalidationListener(listener.capture());

        assertNull(signingSecretOf("${vault:hook}", "a".repeat(15)));
        assertEquals(1, router.weakWebhookSecretWarningsLogged());

        // A second refresh with nothing changed is silent.
        forceRefresh();
        router.getWebhookIntegration(WEBHOOK_ID);
        assertEquals(1, router.weakWebhookSecretWarningsLogged());

        // A vault change re-arms the warning: the secret may still be wrong.
        listener.getValue().accept(new SecretReference("default", "hook"));
        router.getWebhookIntegration(WEBHOOK_ID);
        assertEquals(2, router.weakWebhookSecretWarningsLogged());
    }

    private void forceRefresh() throws Exception {
        var field = ChannelTargetRouter.class.getDeclaredField("lastRefreshTime");
        field.setAccessible(true);
        field.setLong(router, 0L);
    }

    @Test
    void unresolvedLongReferenceIsStrippedWithOneWarning() throws Exception {
        String reference = "${vault:a-reference-that-is-long-enough}";
        assertNull(signingSecretOf(reference, reference), "an unresolved reference is left unchanged by resolution");
        assertEquals(1, router.weakWebhookSecretWarningsLogged());
    }

    @Test
    void unresolvedLongReferenceWithLeadingWhitespaceIsStripped() throws Exception {
        String reference = " ${vault:a-reference-that-is-long-enough}";
        assertNull(signingSecretOf(reference, reference));
        assertEquals(1, router.weakWebhookSecretWarningsLogged());
    }

    private void storeIntegration(ChannelIntegrationConfiguration config) throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/" + WEBHOOK_ID + "?version=2"));
        when(descriptorStore.readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(descriptor));
        when(channelStore.read(WEBHOOK_ID, 2)).thenReturn(config);
    }

    private static ChannelIntegrationConfiguration slack(Map<String, String> platformConfig) {
        var config = new ChannelIntegrationConfiguration();
        config.setName("team-slack");
        config.setChannelType("slack");
        config.setTargets(List.of(target("default")));
        config.setDefaultTargetName("default");
        config.setPlatformConfig(platformConfig);
        return config;
    }

    @Test
    void vaultReferenceInInputTemplateIsNeverResolved() throws Exception {
        // inputTemplate becomes conversation input: expanding a reference there
        // would put the secret's plaintext into a conversation its editor can read.
        var config = webhook(target("default"));
        config.setPlatformConfig(Map.of("signingSecret", "${vault:hook-secret}", "inputTemplate", "leak ${vault:restricted}"));
        storeIntegration(config);
        when(secretResolver.resolveValue("${vault:hook-secret}")).thenReturn("plain-secret-0123456789");
        when(secretResolver.resolveValue("leak ${vault:restricted}")).thenReturn("leak PLAINTEXT");

        var found = router.getWebhookIntegration(WEBHOOK_ID).orElseThrow();

        assertEquals("leak ${vault:restricted}", found.getPlatformConfig().get("inputTemplate"));
        verify(secretResolver, never()).resolveValue("leak ${vault:restricted}");
    }

    @Test
    void unresolvedSlackSigningSecretIsNeverUsedAsAKey() throws Exception {
        var config = slack(Map.of("channelId", "C1", "signingSecret", "${vault:slack-signing}", "botToken", "${vault:slack-bot}"));
        storeIntegration(config);
        // Vault unavailable or key missing: the resolver passes the reference through.
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(router.getSigningSecrets("slack").isEmpty());
        var found = router.getIntegration("slack", "C1").orElseThrow();
        assertNull(found.getPlatformConfig().get("signingSecret"));
        assertNull(router.getBotToken("slack", "C1"));
        assertTrue(router.getSigningSecretForChannel("slack", "C1").isEmpty());
    }

    @Test
    void slackBotTokenWhoseSecretMayNotGoToSlackIsDropped() throws Exception {
        var config = slack(Map.of("channelId", "C1", "signingSecret", "${vault:slack-signing}", "botToken", "${vault:restricted-token}"));
        storeIntegration(config);
        when(secretResolver.resolveValue("${vault:slack-signing}")).thenReturn("signing-plain");
        when(secretResolver.resolveValue("${vault:restricted-token}")).thenReturn("xoxb-plain");
        doThrow(new VaultHostAllowlist.HostNotAllowedException("not allowed")).when(vaultHostAllowlist)
                .requireAllowed(eq(List.of("${vault:restricted-token}")), eq("https://slack.com/api/"), anyString());

        assertNull(router.getBotToken("slack", "C1"));
        verify(secretResolver, never()).resolveValue("${vault:restricted-token}");
        assertEquals(Set.of("signing-plain"), router.getSigningSecrets("slack"));
    }

    @Test
    void slackBotTokenAllowedToGoToSlackIsResolved() throws Exception {
        var config = slack(Map.of("channelId", "C1", "botToken", "${vault:token}"));
        storeIntegration(config);
        when(secretResolver.resolveValue("${vault:token}")).thenReturn("xoxb-plain");

        assertEquals("xoxb-plain", router.getBotToken("slack", "C1"));
        verify(vaultHostAllowlist).requireAllowed(eq(List.of("${vault:token}")), eq("https://slack.com/api/"), anyString());
    }
}
