/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebhookChannelSettingsTest {

    @Test
    void defaults() {
        var settings = WebhookChannelSettings.from(Map.of("signingSecret", "${vault:k}"));
        assertEquals(WebhookChannelSettings.Scheme.EDDI_V1, settings.scheme());
        assertEquals(WebhookChannelSettings.Strategy.NEW, settings.conversationStrategy());
        assertEquals("X-Event-Id", settings.eventIdHeader());
        assertEquals("hex", settings.signatureEncoding());
        assertNull(settings.eventTypeHeader());
        assertNull(settings.inputTemplate());
    }

    @Test
    void githubSchemeDefaultsItsHeaders() {
        var settings = WebhookChannelSettings.from(Map.of("signatureScheme", "GitHub"));
        assertEquals(WebhookChannelSettings.Scheme.GITHUB, settings.scheme());
        assertEquals("X-GitHub-Event", settings.eventTypeHeader());
        assertEquals("X-GitHub-Delivery", settings.eventIdHeader());
        // An explicit type pointer takes precedence over the default header.
        assertNull(WebhookChannelSettings.from(Map.of("signatureScheme", "github", "eventTypePath", "/action")).eventTypeHeader());
    }

    @Test
    void validVaultReferencedConfigHasNoErrors() {
        assertTrue(WebhookChannelSettings.validate(Map.of("signingSecret", "${vault:k}")).isEmpty());
    }

    @Test
    void storedSlackIntegrationJsonStillRoundTripsUnchanged() throws Exception {
        // A configuration stored before the webhook type existed.
        String json = "{\"name\":\"Eng\",\"channelType\":\"slack\",\"platformConfig\":{\"channelId\":\"C1\","
                + "\"signingSecret\":\"${vault:s}\"},\"targets\":[{\"name\":\"a\",\"targetId\":\"x\",\"triggers\":[\"a\"]}],"
                + "\"defaultTargetName\":\"a\"}";
        var mapper = new ObjectMapper();
        var config = mapper.readValue(json, ChannelIntegrationConfiguration.class);
        assertEquals("slack", config.getChannelType());
        assertEquals(Map.of("channelId", "C1", "signingSecret", "${vault:s}"), config.getPlatformConfig());
        var reread = mapper.readValue(mapper.writeValueAsString(config), ChannelIntegrationConfiguration.class);
        assertEquals(config.getPlatformConfig(), reread.getPlatformConfig());
        assertEquals("a", reread.getDefaultTargetName());
        assertEquals(ChannelTarget.TargetType.AGENT, reread.getTargets().get(0).getType());
    }
}
