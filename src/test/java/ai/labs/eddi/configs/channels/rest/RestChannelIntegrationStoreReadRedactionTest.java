/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M1 of the 2026-10-17 security review: an editor read a Slack integration's
 * {@code botToken} and {@code signingSecret} — the latter enough to forge
 * {@code /integrations/slack/*} events — verbatim.
 */
class RestChannelIntegrationStoreReadRedactionTest {

    private static final String ID = "aabbccddee1122334455aabb";

    private IChannelIntegrationStore store;
    private ResourceAccessGuard accessGuard;
    private RestChannelIntegrationStore rest;

    @BeforeEach
    void setUp() {
        store = mock(IChannelIntegrationStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestChannelIntegrationStore(store, mock(IDocumentDescriptorStore.class), accessGuard);
    }

    private static ChannelIntegrationConfiguration config(Map<String, String> platformConfig) {
        var config = new ChannelIntegrationConfiguration();
        config.setName("support");
        config.setChannelType("slack");
        config.setPlatformConfig(new LinkedHashMap<>(platformConfig));
        return config;
    }

    private ChannelIntegrationConfiguration read(ChannelIntegrationConfiguration stored, boolean admin) throws Exception {
        when(store.read(ID, 1)).thenReturn(stored);
        when(accessGuard.isAdmin()).thenReturn(admin);
        return rest.readChannel(ID, 1);
    }

    @Test
    @DisplayName("an editor reads botToken, signingSecret and appToken redacted; ids and references stay legible")
    void editorReadsRedacted() throws Exception {
        ChannelIntegrationConfiguration stored = config(
                Map.of("botToken", "xoxb-1234-5678-abcdef", "signingSecret", "8f14e45fceea167a5a36dedd4bea2543",
                        "appToken", "${vault:slack-app}", "teamId", "T0123", "channelId", "C0456"));

        Map<String, String> seen = read(stored, false).getPlatformConfig();

        assertEquals(ConfigSecretRedactor.REDACTED, seen.get("botToken"));
        assertEquals(ConfigSecretRedactor.REDACTED, seen.get("signingSecret"));
        assertEquals("${vault:slack-app}", seen.get("appToken"));
        assertEquals("T0123", seen.get("teamId"));
        assertEquals("C0456", seen.get("channelId"));
        assertEquals("xoxb-1234-5678-abcdef", stored.getPlatformConfig().get("botToken"), "the stored object is never touched");
    }

    @Test
    @DisplayName("an administrator reads the document as stored")
    void adminReadsRaw() throws Exception {
        ChannelIntegrationConfiguration stored = config(Map.of("botToken", "xoxb-1234-5678-abcdef"));
        assertSame(stored, read(stored, true));
    }
}
