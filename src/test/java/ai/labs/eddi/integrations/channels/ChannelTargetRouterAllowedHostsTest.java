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
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A Slack integration's {@code botToken} vault reference was resolved to
 * plaintext with no look at the secret's {@code allowedHosts}, so an editor
 * could send a secret restricted to another host to {@code slack.com}.
 */
class ChannelTargetRouterAllowedHostsTest {

    private static final String CONFIG_ID = "5262b802dc6c4008b54c";
    private static final String CHANNEL_ID = "C07TESTCHANNEL";
    private static final String TOKEN_REF = "${vault:slack-bot}";

    private SecretResolver secretResolver;
    private ISecretProvider secretProvider;
    private ChannelTargetRouter router;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        var channelStore = mock(IChannelIntegrationStore.class);
        var descriptorStore = mock(IDocumentDescriptorStore.class);
        var agentAdmin = mock(IDeploymentStatusReader.class);
        secretResolver = mock(SecretResolver.class);
        secretProvider = mock(ISecretProvider.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.<String, ChannelTarget>getCache(anyString(), any(Duration.class))).thenReturn(mock(ICache.class));
        when(agentAdmin.readAllDeploymentStatuses(any())).thenReturn(List.of());
        when(secretResolver.resolveValue(anyString())).thenAnswer(i -> TOKEN_REF.equals(i.getArgument(0)) ? "xoxb-live" : i.getArgument(0));

        var config = new ChannelIntegrationConfiguration();
        config.setName("support");
        config.setChannelType("slack");
        var platformConfig = new HashMap<String, String>();
        platformConfig.put("channelId", CHANNEL_ID);
        platformConfig.put("botToken", TOKEN_REF);
        platformConfig.put("signingSecret", "signing-secret");
        config.setPlatformConfig(platformConfig);
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/" + CONFIG_ID + "?version=1"));
        when(descriptorStore.readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(descriptor));
        when(channelStore.read(eq(CONFIG_ID), eq(1))).thenReturn(config);

        router = new ChannelTargetRouter(channelStore, descriptorStore, agentAdmin, mock(IAgentStore.class), secretResolver,
                new VaultHostAllowlist(secretProvider), cacheFactory);
    }

    private void tokenAllowedAt(List<String> hosts) throws Exception {
        when(secretProvider.getMetadata(new SecretReference("default", "slack-bot")))
                .thenReturn(new SecretMetadata("default", "slack-bot", Instant.EPOCH, null, null, null, "bot", List.of("*"), hosts));
    }

    @Test
    @DisplayName("a botToken whose secret excludes slack.com is withheld, never resolved")
    void tokenExcludedFromSlackIsWithheld() throws Exception {
        tokenAllowedAt(List.of("api.other.example"));

        assertNull(router.getBotToken("slack", CHANNEL_ID));
        verify(secretResolver, never()).resolveValue(TOKEN_REF);
    }

    @Test
    @DisplayName("a botToken whose secret names slack.com resolves as before")
    void tokenAllowedAtSlackResolves() throws Exception {
        tokenAllowedAt(List.of("slack.com"));
        assertEquals("xoxb-live", router.getBotToken("slack", CHANNEL_ID));
    }

    @Test
    @DisplayName("an unrestricted botToken secret resolves as before")
    void unrestrictedTokenResolves() throws Exception {
        tokenAllowedAt(null);
        assertEquals("xoxb-live", router.getBotToken("slack", CHANNEL_ID));
    }
}
