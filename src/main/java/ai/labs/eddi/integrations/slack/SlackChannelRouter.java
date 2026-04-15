package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ChannelConnector;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routes incoming Slack channel messages to the correct EDDI agent by scanning
 * deployed agents for {@link ChannelConnector} entries of type {@code "slack"}.
 * <p>
 * Resolution order:
 * <ol>
 * <li>Check channel→agent map (built from ChannelConnector configs)</li>
 * <li>Fall back to {@code eddi.slack.default-agent-id}</li>
 * <li>Return empty if no match</li>
 * </ol>
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class SlackChannelRouter {

    private static final Logger LOGGER = Logger.getLogger(SlackChannelRouter.class);
    private static final String CHANNEL_TYPE_SLACK = "slack";

    private final IRestAgentAdministration agentAdmin;
    private final IRestAgentStore agentStore;
    private final SlackIntegrationConfig config;

    /**
     * channelId → agentId mapping, rebuilt on demand. Volatile reference swap
     * ensures concurrent readers never see a partially-updated map.
     */
    private volatile Map<String, String> channelAgentMap = Map.of();

    /** channelId → groupId mapping (optional, for group discussions). */
    private volatile Map<String, String> channelGroupMap = Map.of();

    private volatile long lastRefreshTime = 0;
    private static final long REFRESH_INTERVAL_MS = 60_000; // 1 minute

    @Inject
    public SlackChannelRouter(IRestAgentAdministration agentAdmin, IRestAgentStore agentStore,
            SlackIntegrationConfig config) {
        this.agentAdmin = agentAdmin;
        this.agentStore = agentStore;
        this.config = config;
    }

    /**
     * Resolve which EDDI agent should handle messages from a given Slack channel.
     *
     * @param slackChannelId
     *            the Slack channel ID (e.g., "C0123ABCDEF")
     * @return the EDDI agent ID, or empty if no mapping exists
     */
    public Optional<String> resolveAgentId(String slackChannelId) {
        refreshIfNeeded();

        // 1. Check explicit channel→agent mapping
        String agentId = channelAgentMap.get(slackChannelId);
        if (agentId != null) {
            return Optional.of(agentId);
        }

        // 2. Fall back to default
        return config.defaultAgentId();
    }

    /**
     * Resolve which group configuration to use for group discussions from a given
     * Slack channel.
     *
     * @param slackChannelId
     *            the Slack channel ID
     * @return the EDDI group config ID, or empty if no mapping exists
     */
    public Optional<String> resolveGroupId(String slackChannelId) {
        refreshIfNeeded();

        String groupId = channelGroupMap.get(slackChannelId);
        if (groupId != null) {
            return Optional.of(groupId);
        }

        return config.defaultGroupId();
    }

    /**
     * Refresh the channel→agent mapping by scanning deployed agents. Uses a simple
     * time-based cache invalidation (1 minute).
     */
    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshTime < REFRESH_INTERVAL_MS) {
            return;
        }

        try {
            var newAgentMap = new java.util.HashMap<String, String>();
            var newGroupMap = new java.util.HashMap<String, String>();

            // Scan all deployed agents in production
            List<AgentDeploymentStatus> statuses = agentAdmin.getDeploymentStatuses(Deployment.Environment.production);
            for (AgentDeploymentStatus status : statuses) {
                if (status.getDescriptor() == null || status.getDescriptor().isDeleted()) {
                    continue;
                }

                String agentId = status.getAgentId();
                int version = status.getAgentVersion();

                try {
                    AgentConfiguration agentConfig = agentStore.readAgent(agentId, version);
                    if (agentConfig != null && agentConfig.getChannels() != null) {
                        for (ChannelConnector channel : agentConfig.getChannels()) {
                            if (channel.getType() != null
                                    && channel.getType().toString().equalsIgnoreCase(CHANNEL_TYPE_SLACK)
                                    && channel.getConfig() != null) {

                                String channelId = channel.getConfig().get("channelId");
                                if (channelId != null && !channelId.isBlank()) {
                                    newAgentMap.put(channelId, agentId);
                                    LOGGER.debugf("Mapped Slack channel %s → agent %s", channelId, agentId);

                                    // Optional group mapping
                                    String groupId = channel.getConfig().get("groupId");
                                    if (groupId != null && !groupId.isBlank()) {
                                        newGroupMap.put(channelId, groupId);
                                        LOGGER.debugf("Mapped Slack channel %s → group %s", channelId, groupId);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debugf("Could not read agent config for %s v%d: %s", agentId, version, e.getMessage());
                }
            }

            // Atomic reference swap — readers never see a partially-updated map
            channelAgentMap = Map.copyOf(newAgentMap);
            channelGroupMap = Map.copyOf(newGroupMap);
            lastRefreshTime = now;

            LOGGER.infof("Slack channel router refreshed: %d channel→agent, %d channel→group mappings",
                    newAgentMap.size(), newGroupMap.size());
        } catch (Exception e) {
            LOGGER.warnf("Failed to refresh Slack channel router: %s", e.getMessage());
            lastRefreshTime = now; // Avoid hammering on repeated failures
        }
    }
}
