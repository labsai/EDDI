/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels.types;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.integrations.channels.ChannelTypeDescriptor;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

/**
 * The mailbox channel. It already names a connection and holds no secret, but
 * it keeps its own validation ({@link EmailChannelSettings#validate}, which
 * refuses unknown keys itself) and its own deployment checks in
 * {@code EmailChannelSupport}, so it is not declared connection-backed here. It
 * is not in the router's index: the poller finds its integrations itself, so it
 * has no routing keys. Every mail is addressed to the mailbox, so there is
 * nothing to observe.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class EmailChannelType implements ChannelTypeDescriptor {

    @Override
    public String id() {
        return EmailChannelSettings.CHANNEL_TYPE;
    }

    @Override
    public List<String> validate(Map<String, String> platformConfig) {
        return EmailChannelSettings.validate(platformConfig);
    }

    @Override
    public boolean supportsObserveMode() {
        return false;
    }

    @Override
    public List<String> routingKeys(String resourceId, Map<String, String> platformConfig) {
        return List.of();
    }
}
