package ai.labs.eddi.integrations.slack;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Slack integration configuration. Only the master toggle lives at server
 * level. All credentials (bot token, signing secret) and routing (channel →
 * agent) are configured per-agent via {@code ChannelConnector} entries.
 * <p>
 * This keeps a single infrastructure-level kill switch while allowing each
 * agent to connect to its own Slack workspace independently.
 *
 * @since 6.0.0
 */
@ConfigMapping(prefix = "eddi.slack")
public interface SlackIntegrationConfig {

    /**
     * Master toggle for the Slack integration. When false, the webhook endpoint
     * returns 404 and no Slack-related scanning happens.
     */
    @WithDefault("false")
    boolean enabled();
}
