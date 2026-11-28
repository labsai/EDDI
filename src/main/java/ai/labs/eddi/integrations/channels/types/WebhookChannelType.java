/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels.types;

import ai.labs.eddi.configs.channels.model.WebhookChannelSettings;
import ai.labs.eddi.integrations.channels.ChannelTypeDescriptor;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

/**
 * The signed inbound webhook ({@code POST /integrations/webhook/{id}}). It has
 * no platform channel: it is addressed, and routed, by its own resource id,
 * which is unique by construction. It sends nothing outbound and has no traffic
 * to observe.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class WebhookChannelType implements ChannelTypeDescriptor {

    @Override
    public String id() {
        return WebhookChannelSettings.CHANNEL_TYPE;
    }

    @Override
    public List<String> validate(Map<String, String> platformConfig) {
        return WebhookChannelSettings.validate(platformConfig);
    }

    @Override
    public boolean supportsObserveMode() {
        return false;
    }

    @Override
    public List<String> routingKeys(String resourceId, Map<String, String> platformConfig) {
        return resourceId == null || resourceId.isBlank() ? List.of() : List.of(resourceId);
    }
}
