/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels.types;

import ai.labs.eddi.integrations.channels.ChannelTypeDescriptor;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.util.Optional;

/**
 * Slack. A legacy type: its {@code botToken} and {@code signingSecret} stay in
 * {@code platformConfig} as vault references, resolved by the router (see
 * {@code ChannelTargetRouter.CREDENTIAL_KEYS}), and any other key is accepted
 * as before. Routed by {@code channelId}; an integration without one is not
 * indexed.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class SlackChannelType implements ChannelTypeDescriptor {

    public static final String ID = "slack";

    private static final URI API_ORIGIN = URI.create("https://slack.com");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<URI> apiOrigin() {
        return Optional.of(API_ORIGIN);
    }
}
