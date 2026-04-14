package ai.labs.eddi.integrations.slack;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Slack integration configuration. Feature-flagged — disabled by default.
 * <p>
 * To enable: set {@code eddi.slack.enabled=true}, configure bot token and
 * signing secret (via environment variables or SecretsVault references).
 *
 * @since 6.0.0
 */
@ConfigMapping(prefix = "eddi.slack")
public interface SlackIntegrationConfig {

    /**
     * Master toggle for the Slack integration. When false, the webhook endpoint
     * returns 404 and no Slack-related beans are activated.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Slack Bot User OAuth Token (starts with {@code xoxb-}). Required when
     * enabled. Recommended: use a vault reference like
     * {@code ${eddivault:slack-bot-token}}.
     */
    Optional<String> botToken();

    /**
     * Slack Signing Secret for verifying incoming webhook requests. Required when
     * enabled.
     */
    Optional<String> signingSecret();

    /**
     * Default agent ID to use when no {@code ChannelConnector} matches the incoming
     * Slack channel. Optional — if unset and no match found, the bot responds with
     * a configuration error.
     */
    Optional<String> defaultAgentId();

    /**
     * Default group configuration ID for group discussions triggered via Slack.
     * Optional.
     */
    Optional<String> defaultGroupId();
}
