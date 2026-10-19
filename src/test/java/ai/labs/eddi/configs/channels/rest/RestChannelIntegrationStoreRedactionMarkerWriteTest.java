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

import static ai.labs.eddi.configs.rest.RedactionMarkerAssertions.assertRefusedNaming;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The channel store refuses to store the read-redaction marker — a saved-back
 * Slack integration would otherwise lose its bot token and signing secret.
 */
class RestChannelIntegrationStoreRedactionMarkerWriteTest {

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

    private static ChannelIntegrationConfiguration config(String botToken) {
        var config = new ChannelIntegrationConfiguration();
        config.setName("support");
        config.setChannelType("slack");
        config.setPlatformConfig(new LinkedHashMap<>(Map.of("botToken", botToken, "channelId", "C0456")));
        return config;
    }

    @Test
    @DisplayName("an editor's read-then-save round trip is refused and nothing is written")
    void editorRoundTripIsRefused() throws Exception {
        when(store.read(ID, 1)).thenReturn(config("xoxb-fixturefixturefixture"));
        ChannelIntegrationConfiguration seen = rest.readChannel(ID, 1);

        assertRefusedNaming("platformConfig.botToken", () -> rest.updateChannel(ID, 1, seen));
        verify(store, never()).update(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("an administrator is refused the marker on create, and a stored marker is not duplicated")
    void adminCreateAndDuplicateAreRefused() throws Exception {
        when(accessGuard.isAdmin()).thenReturn(true);
        assertRefusedNaming("platformConfig.botToken", () -> rest.createChannel(config(ConfigSecretRedactor.REDACTED)));

        when(store.read(ID, 1)).thenReturn(config(ConfigSecretRedactor.REDACTED));
        assertRefusedNaming("platformConfig.botToken", () -> rest.duplicateChannel(ID, 1));
        verify(store, never()).create(any());
    }
}
