package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ChannelConnector;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.secrets.SecretResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Routes incoming Slack channel messages to the correct EDDI agent and resolves
 * per-agent credentials by scanning deployed agents for
 * {@link ChannelConnector} entries of type {@code "slack"}.
 * <p>
 * All Slack credentials (bot token, signing secret) live in the agent's
 * {@code ChannelConnector.config} map and are resolved via
 * {@link SecretResolver} (supporting {@code ${eddivault:...}} references).
 * <p>
 * Resolution order for agent routing:
 * <ol>
 * <li>Check channel→agent map (built from ChannelConnector configs)</li>
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
    private final SecretResolver secretResolver;

    /**
     * Resolved credentials for a Slack channel connector.
     *
     * @param agentId
     *            the EDDI agent ID
     * @param botToken
     *            the resolved bot token (plaintext)
     * @param signingSecret
     *            the resolved signing secret (plaintext)
     * @param groupId
     *            optional group ID for multi-agent discussions
     */
    public record SlackCredentials(String agentId, String botToken, String signingSecret, String groupId) {
    }

    /**
     * channelId → full credentials mapping, rebuilt on demand. Volatile reference
     * swap ensures concurrent readers never see a partially-updated map.
     */
    private volatile Map<String, SlackCredentials> channelCredentialsMap = Map.of();

    /**
     * All unique signing secrets across all configured agents. Used for webhook
     * signature verification (try all secrets since we don't know the workspace
     * before verification).
     */
    private volatile Set<String> allSigningSecrets = Set.of();

    private volatile long lastRefreshTime = 0;
    private static final long REFRESH_INTERVAL_MS = 60_000; // 1 minute
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);

    @Inject
    public SlackChannelRouter(IRestAgentAdministration agentAdmin, IRestAgentStore agentStore,
            SecretResolver secretResolver) {
        this.agentAdmin = agentAdmin;
        this.agentStore = agentStore;
        this.secretResolver = secretResolver;
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
        SlackCredentials creds = channelCredentialsMap.get(slackChannelId);
        return creds != null ? Optional.of(creds.agentId()) : Optional.empty();
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
        SlackCredentials creds = channelCredentialsMap.get(slackChannelId);
        return creds != null && creds.groupId() != null ? Optional.of(creds.groupId()) : Optional.empty();
    }

    /**
     * Resolve the full credentials for a Slack channel. Returns the bot token,
     * signing secret, agent ID, and optional group ID.
     *
     * @param slackChannelId
     *            the Slack channel ID
     * @return the resolved credentials, or empty if no mapping exists
     */
    public Optional<SlackCredentials> resolveCredentials(String slackChannelId) {
        refreshIfNeeded();
        return Optional.ofNullable(channelCredentialsMap.get(slackChannelId));
    }

    /**
     * Get all known signing secrets across all configured agents. Used by the
     * webhook endpoint for signature verification — the verifier tries each secret
     * until one matches (standard multi-workspace Slack pattern).
     *
     * @return an unmodifiable set of resolved signing secrets (never null, may be
     *         empty)
     */
    public Set<String> getAllSigningSecrets() {
        refreshIfNeeded();
        return allSigningSecrets;
    }

    /**
     * Check if any Slack channel connectors are configured across all deployed
     * agents.
     *
     * @return true if at least one agent has a Slack channel connector
     */
    public boolean hasAnySlackChannels() {
        refreshIfNeeded();
        return !channelCredentialsMap.isEmpty();
    }

    /**
     * Refresh the channel→credentials mapping by scanning deployed agents. Uses a
     * simple time-based cache invalidation (1 minute). Vault references
     * (${eddivault:...}) are resolved during refresh via {@link SecretResolver}.
     */
    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshTime < REFRESH_INTERVAL_MS) {
            return;
        }

        // Gate: only one thread refreshes at a time
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }

        try {
            var newCredentialsMap = new HashMap<String, SlackCredentials>();
            var newSigningSecrets = new HashSet<String>();

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

                                processSlackChannel(agentId, channel.getConfig(),
                                        newCredentialsMap, newSigningSecrets);
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debugf("Could not read agent config for %s v%d: %s", agentId, version, e.getMessage());
                }
            }

            // Atomic reference swap — readers never see a partially-updated map
            channelCredentialsMap = Map.copyOf(newCredentialsMap);
            allSigningSecrets = Set.copyOf(newSigningSecrets);
            lastRefreshTime = now;

            LOGGER.infof("Slack channel router refreshed: %d channel mappings, %d unique signing secrets",
                    newCredentialsMap.size(), newSigningSecrets.size());
        } catch (Exception e) {
            LOGGER.warnf("Failed to refresh Slack channel router: %s", e.getMessage());
            lastRefreshTime = now; // Avoid hammering on repeated failures
        } finally {
            refreshInProgress.set(false);
        }
    }

    /**
     * Process a single Slack ChannelConnector config entry, resolving vault
     * references and building the credentials mapping.
     */
    private void processSlackChannel(String agentId, Map<String, String> config,
                                     Map<String, SlackCredentials> credentialsMap, Set<String> signingSecrets) {

        String channelId = config.get("channelId");
        if (channelId == null || channelId.isBlank()) {
            LOGGER.debugf("Slack ChannelConnector on agent %s has no channelId — skipping", agentId);
            return;
        }

        // Resolve vault references for credentials
        String botToken = resolveSecret(config.get("botToken"), agentId, "botToken");
        String signingSecret = resolveSecret(config.get("signingSecret"), agentId, "signingSecret");
        String groupId = config.get("groupId");

        if (botToken == null || botToken.isBlank()) {
            LOGGER.warnf("Slack ChannelConnector on agent %s, channel %s: botToken is missing or unresolved",
                    agentId, channelId);
        }

        if (signingSecret == null || signingSecret.isBlank()) {
            LOGGER.warnf("Slack ChannelConnector on agent %s, channel %s: signingSecret is missing or unresolved",
                    agentId, channelId);
        }

        credentialsMap.put(channelId, new SlackCredentials(agentId, botToken, signingSecret,
                groupId != null && !groupId.isBlank() ? groupId : null));
        LOGGER.debugf("Mapped Slack channel %s → agent %s", channelId, agentId);

        if (signingSecret != null && !signingSecret.isBlank()) {
            signingSecrets.add(signingSecret);
        }
    }

    /**
     * Resolve a config value that may be a vault reference. Returns the resolved
     * plaintext value, or the original value if vault is not configured. Returns
     * null if the value is null.
     */
    private String resolveSecret(String value, String agentId, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return secretResolver.resolveValue(value);
        } catch (Exception e) {
            LOGGER.warnf("Failed to resolve %s for agent %s: %s", fieldName, agentId, e.getMessage());
            return null;
        }
    }
}
