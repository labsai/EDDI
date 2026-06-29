/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ChannelConnector;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.secrets.SecretResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static ai.labs.eddi.utils.RestUtilities.extractResourceId;

/**
 * Target router for channel integrations. Resolves incoming channel messages to
 * the correct {@link ChannelTarget} based on configured trigger keywords
 * (colon-required syntax: {@code keyword: message}).
 * <p>
 * Platform-agnostic: signing-secret aggregation and target resolution are keyed
 * by {@code channelType} so multiple platform adapters can coexist. Currently,
 * only {@code slack} is registered/validated (see
 * {@code RestChannelIntegrationStore.REGISTERED_CHANNEL_TYPES}).
 * Platform-specific adapters provide their own webhook and event-handler
 * classes.
 * <p>
 * <b>Fallback rule:</b> If any {@code ChannelIntegrationConfiguration} matches
 * a channelType + channelId pair, ALL legacy {@code ChannelConnector} entries
 * for that same type + channel are ignored. Legacy entries only activate for
 * channels with zero new-style coverage of the same type.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class ChannelTargetRouter {

    private static final Logger LOGGER = Logger.getLogger(ChannelTargetRouter.class);
    private static final long REFRESH_INTERVAL_MS = 60_000; // 1 minute
    private static final String CHANNEL_TYPE_SLACK = "slack";

    private final IChannelIntegrationStore channelStore;
    private final IDocumentDescriptorStore descriptorStore;
    private final IRestAgentAdministration agentAdmin;
    private final IRestAgentStore agentStore;
    private final SecretResolver secretResolver;

    // ─── Cached state (atomic reference swap) ──────────────────────────────────

    /**
     * channelType:channelId → deep-copied ChannelIntegrationConfiguration with
     * resolved secrets. These cached instances may be returned by router methods
     * (e.g., {@link #getIntegration}) and must be treated as sensitive internal
     * data that must not be logged or serialized. The REST layer reads from the
     * store directly and returns vault references instead.
     */
    private volatile Map<String, ChannelIntegrationConfiguration> integrationMap = Map.of();

    /** Signing secrets per channel type (from both new + legacy configs). */
    private volatile Map<String, Set<String>> signingSecretsByType = Map.of();

    /** Legacy channelId → LegacyTarget for backward compat. */
    private volatile Map<String, LegacyTarget> legacyMap = Map.of();

    private volatile long lastRefreshTime = 0;
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);

    /**
     * Thread → locked target (prevents mid-thread target switching). TTL-evicted.
     */
    private final ICache<String, ChannelTarget> threadTargetLock;

    @Inject
    public ChannelTargetRouter(IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore descriptorStore,
            IRestAgentAdministration agentAdmin,
            IRestAgentStore agentStore,
            SecretResolver secretResolver,
            ICacheFactory cacheFactory) {
        this.channelStore = channelStore;
        this.descriptorStore = descriptorStore;
        this.agentAdmin = agentAdmin;
        this.agentStore = agentStore;
        this.secretResolver = secretResolver;
        this.threadTargetLock = cacheFactory.getCache("channel-thread-locks", Duration.ofHours(24));
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Resolve a target for a fresh message (not a thread reply). Scans for a
     * colon-delimited trigger keyword at the start of the message.
     *
     * @param channelType
     *            platform type (e.g., "slack")
     * @param platformChannelId
     *            the platform-specific channel ID
     * @param messageText
     *            the user's message (bot mention already stripped)
     * @return resolved target, or {@code null} if the message is "help" or no
     *         integration covers this channel
     */
    public ResolvedTarget resolveTarget(String channelType, String platformChannelId,
                                        String messageText) {
        refreshIfNeeded();
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";

        // 1. Try new-style ChannelIntegrationConfiguration
        String key = normalizedType + ":" + platformChannelId;
        ChannelIntegrationConfiguration integration = integrationMap.get(key);
        if (integration != null) {
            return resolveFromIntegration(integration, messageText);
        }

        // 2. Fallback: legacy ChannelConnector (only if no new-style config covers this
        // channel)
        if (CHANNEL_TYPE_SLACK.equals(normalizedType)) {
            LegacyTarget legacy = legacyMap.get(platformChannelId);
            if (legacy != null) {
                // Apply same help/blank check as new-style path for consistency
                String trimmed = messageText != null ? messageText.trim() : "";
                if (trimmed.isEmpty() || "help".equalsIgnoreCase(trimmed)) {
                    return null;
                }
                return new ResolvedTarget(legacy.toChannelTarget(), messageText, null,
                        legacy.botToken(), legacy.signingSecret());
            }
        }

        return null; // No integration for this channel
    }

    /**
     * Resolve a default target for DMs or unconfigured channels. Used when the
     * platform channel ID isn't explicitly configured (e.g., Slack DMs use dynamic
     * D-prefixed IDs unique to each user-bot pair).
     * <p>
     * Returns the default target from the first available integration of the given
     * channel type, or {@code null} if no integrations exist.
     */
    public ResolvedTarget resolveDefaultForDm(String channelType, String messageText) {
        refreshIfNeeded();
        String prefix = (channelType != null ? channelType.toLowerCase(Locale.ROOT) : "") + ":";
        for (var entry : integrationMap.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                return resolveFromIntegration(entry.getValue(), messageText);
            }
        }
        // Fallback to first legacy entry (Slack only)
        if (CHANNEL_TYPE_SLACK.equals(channelType) && !legacyMap.isEmpty()) {
            var firstLegacy = legacyMap.values().iterator().next();
            String trimmed = messageText != null ? messageText.trim() : "";
            if (trimmed.isEmpty() || "help".equalsIgnoreCase(trimmed)) {
                return null;
            }
            return new ResolvedTarget(firstLegacy.toChannelTarget(), messageText, null,
                    firstLegacy.botToken(), firstLegacy.signingSecret());
        }
        return null;
    }

    /**
     * Resolve the target for a thread reply using the thread→target lock.
     *
     * @return the locked target, or {@code null} if no lock exists for this thread
     */
    public ResolvedTarget resolveThreadTarget(String channelType, String platformChannelId,
                                              String threadTs) {
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        String lockKey = normalizedType + ":" + platformChannelId + ":" + threadTs;
        ChannelTarget locked = threadTargetLock.get(lockKey);
        if (locked == null) {
            return null;
        }

        refreshIfNeeded();
        String key = normalizedType + ":" + platformChannelId;
        ChannelIntegrationConfiguration integration = integrationMap.get(key);

        // Attach legacy credentials when no new-style integration exists
        String legacyBotToken = null;
        String legacySigningSecret = null;
        if (integration == null && CHANNEL_TYPE_SLACK.equals(normalizedType)) {
            LegacyTarget legacy = legacyMap.get(platformChannelId);
            if (legacy != null) {
                legacyBotToken = legacy.botToken();
                legacySigningSecret = legacy.signingSecret();
            }
        }
        return new ResolvedTarget(locked, null, integration, legacyBotToken, legacySigningSecret);
    }

    /**
     * Lock a target for a thread. Subsequent messages in this thread will always
     * route to the same target, ignoring trigger keywords.
     *
     * @param channelType
     *            platform type (e.g., "slack")
     * @param platformChannelId
     *            the platform-specific channel ID
     * @param threadTs
     *            the thread timestamp
     * @param target
     *            the target to lock for this thread
     */
    public void lockThreadTarget(String channelType, String platformChannelId,
                                 String threadTs, ChannelTarget target) {
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        String lockKey = normalizedType + ":" + platformChannelId + ":" + threadTs;
        threadTargetLock.put(lockKey, target);
    }

    /**
     * Get all signing secrets for a given platform type. Used by webhook signature
     * verifiers.
     */
    public Set<String> getSigningSecrets(String channelType) {
        refreshIfNeeded();
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        return signingSecretsByType.getOrDefault(normalizedType, Set.of());
    }

    /**
     * Get the integration config for a specific channel. Returns empty if no
     * new-style config covers this channel.
     */
    public Optional<ChannelIntegrationConfiguration> getIntegration(String channelType,
                                                                    String platformChannelId) {
        refreshIfNeeded();
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        return Optional.ofNullable(integrationMap.get(normalizedType + ":" + platformChannelId));
    }

    /**
     * Get the bot token for a channel, checking new-style integrations first, then
     * legacy. Returns {@code null} if no token is configured for this channel.
     */
    public String getBotToken(String channelType, String platformChannelId) {
        refreshIfNeeded();
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        String key = normalizedType + ":" + platformChannelId;
        ChannelIntegrationConfiguration integration = integrationMap.get(key);
        if (integration != null && integration.getPlatformConfig() != null) {
            String token = integration.getPlatformConfig().get("botToken");
            if (token != null && !token.isBlank()) {
                return token;
            }
        }
        // Fallback: legacy map (Slack only)
        if (CHANNEL_TYPE_SLACK.equals(normalizedType)) {
            LegacyTarget legacy = legacyMap.get(platformChannelId);
            if (legacy != null && legacy.botToken() != null) {
                return legacy.botToken();
            }
        }
        return null;
    }

    /**
     * Check if any channel integrations are configured (new or legacy).
     */
    public boolean hasAnyChannels(String channelType) {
        refreshIfNeeded();
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        if (CHANNEL_TYPE_SLACK.equals(normalizedType)) {
            return integrationMap.keySet().stream().anyMatch(k -> k.startsWith("slack:"))
                    || !legacyMap.isEmpty();
        }
        return integrationMap.keySet().stream().anyMatch(k -> k.startsWith(normalizedType + ":"));
    }

    // ─── Trigger matching ──────────────────────────────────────────────────────

    /**
     * Resolve a target from a new-style integration config.
     * <p>
     * Matching rule (colon required):
     * <ol>
     * <li>If message equals "help" (no colon) → return null (signal for help)</li>
     * <li>If message contains ":" → check if text before first ":" matches a
     * trigger</li>
     * <li>Match found → return that target with stripped message (text after
     * colon)</li>
     * <li>No match → return default target with full message</li>
     * </ol>
     */
    ResolvedTarget resolveFromIntegration(ChannelIntegrationConfiguration integration,
                                          String messageText) {
        if (messageText == null || messageText.isBlank()) {
            return null; // Empty → help
        }

        String trimmed = messageText.trim();

        // "help" → signal help
        if ("help".equalsIgnoreCase(trimmed)) {
            return null;
        }

        // Check for colon-delimited trigger
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx > 0) {
            String candidateTrigger = trimmed.substring(0, colonIdx).trim().toLowerCase(Locale.ROOT);
            String remainder = trimmed.substring(colonIdx + 1).trim();

            var targets = integration.getTargets();
            if (targets != null) {
                for (ChannelTarget target : targets) {
                    if (target.getTriggers() != null) {
                        for (String trigger : target.getTriggers()) {
                            if (trigger != null && trigger.toLowerCase(Locale.ROOT).trim().equals(candidateTrigger)) {
                                return new ResolvedTarget(target, remainder, integration,
                                        null, null);
                            }
                        }
                    }
                }
            }
        }

        // No trigger match → default target, full message
        ChannelTarget defaultTarget = findDefaultTarget(integration);
        if (defaultTarget != null) {
            return new ResolvedTarget(defaultTarget, trimmed, integration, null, null);
        }

        LOGGER.warnf("No default target found for integration '%s'", integration.getName());
        return null;
    }

    private ChannelTarget findDefaultTarget(ChannelIntegrationConfiguration integration) {
        String defaultName = integration.getDefaultTargetName();
        if (defaultName == null || integration.getTargets() == null)
            return null;
        return integration.getTargets().stream()
                .filter(t -> t.getName() != null
                        && t.getName().equalsIgnoreCase(defaultName))
                .findFirst()
                .orElse(null);
    }

    // ─── Refresh ───────────────────────────────────────────────────────────────

    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshTime < REFRESH_INTERVAL_MS) {
            return;
        }
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            refreshInternal();
            lastRefreshTime = now;
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh channel target router", e);
            lastRefreshTime = now; // Avoid hammering on repeated failures
        } finally {
            refreshInProgress.set(false);
        }
    }

    private void refreshInternal() {
        var newIntegrationMap = new HashMap<String, ChannelIntegrationConfiguration>();
        var newSigningSecretsByType = new HashMap<String, Set<String>>();
        var coveredChannelKeys = new HashSet<String>();

        // 1. Load new-style ChannelIntegrationConfigurations
        try {
            var descriptors = descriptorStore.readDescriptors("ai.labs.channel",
                    "", 0, 1000, false);
            for (var descriptor : descriptors) {
                try {
                    var resId = extractResourceId(descriptor.getResource());
                    var config = channelStore.read(resId.getId(),
                            resId.getVersion());
                    if (config != null && config.getChannelType() != null
                            && config.getPlatformConfig() != null) {

                        // Deep-copy before resolving secrets so the store's
                        // cached instance keeps vault references intact
                        String channelId = config.getPlatformConfig().get("channelId");
                        if (channelId != null && !channelId.isBlank()) {
                            var copy = deepCopyConfig(config);
                            resolvePlatformSecrets(copy);
                            String key = copy.getChannelType().toLowerCase(Locale.ROOT) + ":" + channelId;
                            newIntegrationMap.put(key, copy);
                            coveredChannelKeys.add(key); // type:channelId — scoped to prevent cross-type suppression

                            // Collect signing secrets per channel type
                            String ss = copy.getPlatformConfig().get("signingSecret");
                            if (ss != null && !ss.isBlank()) {
                                String type = copy.getChannelType().toLowerCase(Locale.ROOT);
                                newSigningSecretsByType
                                        .computeIfAbsent(type, k -> new HashSet<>())
                                        .add(ss);
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("Skipping channel config", e);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load channel integration configs", e);
        }

        // 2. Load legacy ChannelConnector entries (backward compat)
        var newLegacyMap = new HashMap<String, LegacyTarget>();
        try {
            List<AgentDeploymentStatus> statuses = agentAdmin.getDeploymentStatuses(
                    Deployment.Environment.production);
            for (AgentDeploymentStatus status : statuses) {
                if (status.getDescriptor() == null || status.getDescriptor().isDeleted()) {
                    continue;
                }
                String agentId = status.getAgentId();
                try {
                    AgentConfiguration agentConfig = agentStore.readAgent(
                            agentId, status.getAgentVersion());
                    if (agentConfig != null && agentConfig.getChannels() != null) {
                        for (ChannelConnector connector : agentConfig.getChannels()) {
                            if (connector.getType() != null
                                    && connector.getType().toString()
                                            .equalsIgnoreCase(CHANNEL_TYPE_SLACK)
                                    && connector.getConfig() != null) {

                                String chId = connector.getConfig().get("channelId");

                                // Strict rule: new-style config wins, skip legacy
                                // (scoped by type — only a new-style Slack config suppresses a legacy Slack
                                // connector)
                                String coveredKey = CHANNEL_TYPE_SLACK + ":" + chId;
                                if (chId != null && !coveredChannelKeys.contains(coveredKey)) {
                                    String bt = resolveSecret(
                                            connector.getConfig().get("botToken"));
                                    String ss = resolveSecret(
                                            connector.getConfig().get("signingSecret"));
                                    String gid = connector.getConfig().get("groupId");
                                    newLegacyMap.put(chId,
                                            new LegacyTarget(agentId, bt, ss,
                                                    gid != null && !gid.isBlank() ? gid : null));
                                    if (ss != null && !ss.isBlank()) {
                                        newSigningSecretsByType
                                                .computeIfAbsent(CHANNEL_TYPE_SLACK, k -> new HashSet<>())
                                                .add(ss);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debugf(e, "Skipping agent %s for legacy channel scan",
                            agentId);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to scan legacy ChannelConnectors", e);
        }

        // Swap cached references — each volatile write is individually atomic,
        // but the three writes are NOT mutually atomic. A concurrent reader may
        // briefly observe a mixed snapshot (e.g., new integrationMap with old
        // signingSecretsByType). This is acceptable: the data converges within
        // nanoseconds, and stale reads only affect a single request at worst.
        integrationMap = Map.copyOf(newIntegrationMap);
        legacyMap = Map.copyOf(newLegacyMap);
        // Freeze each per-type set, then freeze the outer map
        var frozenSecrets = new HashMap<String, Set<String>>();
        newSigningSecretsByType.forEach((type, secrets) -> frozenSecrets.put(type, Set.copyOf(secrets)));
        signingSecretsByType = Map.copyOf(frozenSecrets);

        int totalSecrets = frozenSecrets.values().stream().mapToInt(Set::size).sum();
        LOGGER.debugf("Channel target router refreshed: %d integrations, %d legacy, %d signing secrets across %d channel types",
                newIntegrationMap.size(), newLegacyMap.size(), totalSecrets, frozenSecrets.size());
    }

    /**
     * Deep-copy a config so that secret resolution does not mutate the store's
     * cached instance (which must retain {@code ${vault:...}} references for the
     * REST API).
     * <p>
     * <b>Invariant:</b> {@code ChannelTarget} instances are shared by reference
     * between the copy and the original. The router must never mutate target
     * objects — they are read-only after construction. If a future change needs
     * per-target secret resolution, targets must be deep-copied too.
     */
    private ChannelIntegrationConfiguration deepCopyConfig(ChannelIntegrationConfiguration src) {
        var copy = new ChannelIntegrationConfiguration();
        copy.setName(src.getName());
        copy.setChannelType(src.getChannelType());
        copy.setDefaultTargetName(src.getDefaultTargetName());
        if (src.getPlatformConfig() != null) {
            copy.setPlatformConfig(new HashMap<>(src.getPlatformConfig()));
        }
        if (src.getTargets() != null) {
            copy.setTargets(new ArrayList<>(src.getTargets()));
        }
        return copy;
    }

    private void resolvePlatformSecrets(ChannelIntegrationConfiguration config) {
        Map<String, String> resolved = new HashMap<>();
        for (var entry : config.getPlatformConfig().entrySet()) {
            resolved.put(entry.getKey(), resolveSecret(entry.getValue()));
        }
        config.setPlatformConfig(resolved);
    }

    private String resolveSecret(String value) {
        if (value == null || value.isBlank())
            return null;
        try {
            return secretResolver.resolveValue(value);
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve secret", e);
            return null;
        }
    }

    // ─── Inner types ───────────────────────────────────────────────────────────

    /**
     * Result of target resolution — includes the matched target, the message with
     * trigger keyword stripped, and (optionally) resolved credentials.
     */
    public record ResolvedTarget(
            ChannelTarget target,
            String strippedMessage,
            ChannelIntegrationConfiguration integration,
            String legacyBotToken,
            String legacySigningSecret) {
        /** Get bot token — from integration or legacy. */
        public String botToken() {
            if (integration != null && integration.getPlatformConfig() != null) {
                return integration.getPlatformConfig().get("botToken");
            }
            return legacyBotToken;
        }

        /** Get signing secret — from integration or legacy. */
        public String signingSecret() {
            if (integration != null && integration.getPlatformConfig() != null) {
                return integration.getPlatformConfig().get("signingSecret");
            }
            return legacySigningSecret;
        }
    }

    /**
     * Backward-compatible representation of a legacy ChannelConnector entry.
     */
    record LegacyTarget(String agentId, String botToken, String signingSecret, String groupId) {
        ChannelTarget toChannelTarget() {
            var target = new ChannelTarget();
            target.setName("default");
            if (groupId != null) {
                target.setType(ChannelTarget.TargetType.GROUP);
                target.setTargetId(groupId);
            } else {
                target.setType(ChannelTarget.TargetType.AGENT);
                target.setTargetId(agentId);
            }
            return target;
        }
    }
}
