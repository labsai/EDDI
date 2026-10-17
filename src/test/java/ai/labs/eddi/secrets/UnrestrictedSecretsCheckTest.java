/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.secrets.UnrestrictedSecretsCheck.UnrestrictedSecret;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * The H1 residual of the 2026-10-17 security review: secrets vaulted before the
 * setup wizard set {@code allowedHosts} keep an open destination. The startup
 * check names every such secret a configuration references.
 */
class UnrestrictedSecretsCheckTest {

    private static final String LLM_ID = "68a1b2c3d4e5f60718293a4b";
    private static final String CHANNEL_ID = "68a1b2c3d4e5f60718293a4c";
    private static final URI LLM_URI = URI.create("eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=2");
    private static final URI CHANNEL_URI = URI.create("eddi://ai.labs.channel/channelstore/channels/" + CHANNEL_ID + "?version=1");

    private ISecretProvider secretProvider;
    private IDocumentDescriptorStore descriptorStore;
    private ILlmStore llmStore;
    private IChannelIntegrationStore channelStore;
    private UnrestrictedSecretsCheck check;

    @BeforeEach
    void setUp() throws Exception {
        secretProvider = mock(ISecretProvider.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        llmStore = mock(ILlmStore.class);
        channelStore = mock(IChannelIntegrationStore.class);
        check = new UnrestrictedSecretsCheck(secretProvider, descriptorStore, llmStore, mock(IMcpCallsStore.class), mock(IRagStore.class),
                mock(IApiCallsStore.class), channelStore);

        when(descriptorStore.readDescriptors(anyString(), anyString(), anyInt(), any(), anyBoolean())).thenReturn(List.of());
        when(descriptorStore.readDescriptors("ai.labs.llm", "", 0, IDescriptorStore.NO_LIMIT, false)).thenReturn(List.of(descriptor(LLM_URI)));
        when(descriptorStore.readDescriptors("ai.labs.channel", "", 0, IDescriptorStore.NO_LIMIT, false))
                .thenReturn(List.of(descriptor(CHANNEL_URI)));

        var task = new LlmConfiguration.Task();
        task.setType("openai");
        task.setParameters(new LinkedHashMap<>(Map.of("apiKey", "${vault:setup.victim.apiKey}", "password", "${vault:narrowed-key}",
                "token", "${vault:gone}")));
        when(llmStore.read(LLM_ID, 2)).thenReturn(new LlmConfiguration(List.of(task)));

        var channel = new ChannelIntegrationConfiguration();
        var platformConfig = new LinkedHashMap<String, String>();
        platformConfig.put("signingSecret", "${vault:setup.victim.apiKey}");
        platformConfig.put("botToken", "${vault:acme/slack-bot}");
        channel.setPlatformConfig(platformConfig);
        when(channelStore.read(CHANNEL_ID, 1)).thenReturn(channel);

        when(secretProvider.getMetadata(new SecretReference("default", "setup.victim.apiKey"))).thenReturn(metadata("default",
                "setup.victim.apiKey", null));
        when(secretProvider.getMetadata(new SecretReference("default", "narrowed-key"))).thenReturn(metadata("default", "narrowed-key",
                List.of("api.openai.com")));
        when(secretProvider.getMetadata(new SecretReference("default", "gone"))).thenThrow(new ISecretProvider.SecretNotFoundException("gone"));
        when(secretProvider.getMetadata(new SecretReference("acme", "slack-bot"))).thenReturn(metadata("acme", "slack-bot", List.of()));
    }

    private static DocumentDescriptor descriptor(URI resource) {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(resource);
        return descriptor;
    }

    private static SecretMetadata metadata(String tenant, String key, List<String> allowedHosts) {
        return new SecretMetadata(tenant, key, Instant.now(), null, null, null, "", List.of("*"), allowedHosts);
    }

    @Test
    @DisplayName("names every referenced secret without allowedHosts, with the configurations that use it")
    void findsUnrestrictedReferencedSecrets() {
        List<UnrestrictedSecret> found = check.scan();

        assertEquals(List.of(new UnrestrictedSecret(new SecretReference("default", "setup.victim.apiKey"),
                List.of(LLM_URI.toString(), CHANNEL_URI.toString())),
                new UnrestrictedSecret(new SecretReference("acme", "slack-bot"), List.of(CHANNEL_URI.toString()))), found);
    }

    @Test
    @DisplayName("the listing learns which configurations reference each secret, narrowed ones included")
    void recordsReferencesForTheListing() {
        assertEquals(Optional.empty(), check.referencedBy(new SecretReference("default", "narrowed-key")), "nothing before a scan");

        check.scan();

        assertEquals(Optional.of(List.of(LLM_URI.toString())), check.referencedBy(new SecretReference("default", "narrowed-key")));
        assertEquals(Optional.of(List.of()), check.referencedBy(new SecretReference("default", "unreferenced")));
    }

    @Test
    @DisplayName("the WARN names tenant/key, the configurations and the grant call that narrows them")
    void warningIsActionable() {
        String warning = UnrestrictedSecretsCheck.warning(check.scan());

        assertTrue(warning.startsWith("[SECURITY] 2 vault secret(s)"), warning);
        assertTrue(warning.contains("default/setup.victim.apiKey used by " + LLM_URI), warning);
        assertTrue(warning.contains("acme/slack-bot used by " + CHANNEL_URI), warning);
        assertTrue(warning.contains("PUT /secretstore/secrets/{tenantId}/{keyName}/grant"), warning);
        assertTrue(warning.contains("\"allowedHosts\""), warning);
    }

    @Test
    @DisplayName("with the vault off nothing is read")
    void vaultOffDoesNothing() throws Exception {
        when(secretProvider.isAvailable()).thenReturn(false);

        check.onStartup(null);

        verify(descriptorStore, never()).readDescriptors(anyString(), anyString(), anyInt(), any(), anyBoolean());
    }

    @Test
    @DisplayName("an unreadable configuration is skipped, not fatal")
    void unreadableConfigIsSkipped() throws Exception {
        when(llmStore.read(eq(LLM_ID), anyInt())).thenThrow(new IllegalStateException("db down"));

        List<UnrestrictedSecret> found = check.scan();

        assertEquals(List.of(new UnrestrictedSecret(new SecretReference("default", "setup.victim.apiKey"), List.of(CHANNEL_URI.toString())),
                new UnrestrictedSecret(new SecretReference("acme", "slack-bot"), List.of(CHANNEL_URI.toString()))), found);
    }
}
