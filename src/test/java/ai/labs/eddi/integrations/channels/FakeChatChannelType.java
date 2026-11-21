/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.integrations.channels.types.EmailChannelType;
import ai.labs.eddi.integrations.channels.types.SlackChannelType;
import ai.labs.eddi.integrations.channels.types.WebhookChannelType;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A third, connection-backed channel type for tests — shaped like a Teams bot:
 * an application id, an optional channel, credentials from a connection.
 */
public class FakeChatChannelType implements ChannelTypeDescriptor {

    public static final String ID = "fakechat";
    public static final URI ORIGIN = URI.create("https://api.fakechat.example");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean connectionBacked() {
        return true;
    }

    @Override
    public Optional<Set<String>> allowedPlatformConfigKeys() {
        return Optional.of(Set.of(KEY_CONNECTION, "appId", KEY_CHANNEL_ID, "hitlApprovalChannel"));
    }

    @Override
    public Optional<URI> apiOrigin() {
        return Optional.of(ORIGIN);
    }

    @Override
    public Set<AuthType> acceptedConnectionAuthTypes() {
        return Set.of(AuthType.OAUTH2_CLIENT_CREDENTIALS);
    }

    @Override
    public Optional<String> applicationId(Map<String, String> platformConfig) {
        return Optional.ofNullable(platformConfig == null ? null : platformConfig.get("appId"));
    }

    @Override
    public List<String> validate(Map<String, String> platformConfig) {
        String appId = platformConfig == null ? null : platformConfig.get("appId");
        return appId == null || appId.isBlank() ? List.of("A fakechat integration requires platformConfig.appId.") : List.of();
    }

    /** The built-in types plus this one. */
    public static ChannelTypeRegistry registry() {
        return new ChannelTypeRegistry(List.of(new SlackChannelType(), new WebhookChannelType(), new EmailChannelType(),
                new FakeChatChannelType()));
    }
}
