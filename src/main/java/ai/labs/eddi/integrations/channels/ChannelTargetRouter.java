/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ChannelConnector;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.secrets.SecretResolver;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
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

    /**
     * Optional {@code platformConfig} keys that, when an integration sets them,
     * must equal the matching identifier in an inbound request's envelope. Slack
     * puts both on every Events API envelope and interactivity payload
     * ({@code team_id} / {@code team.id} and {@code api_app_id}). Unset means "not
     * pinned", so an existing integration keeps working unchanged.
     */
    public static final String CFG_TEAM_ID = "teamId";
    public static final String CFG_APP_ID = "appId";
    private static final List<String> INBOUND_ID_KEYS = List.of(CFG_TEAM_ID, CFG_APP_ID);

    private final IChannelIntegrationStore channelStore;
    private final IDocumentDescriptorStore descriptorStore;
    private final IRestAgentAdministration agentAdmin;
    private final IAgentStore agentStore;
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
     * Counts invalidations, so a refresh can tell whether one landed while it was
     * reading. Without it the marker this class exists to set was simply lost — see
     * {@link #refreshIfNeeded}.
     */
    private final AtomicLong invalidationGeneration = new AtomicLong();

    /**
     * Guards the pair {@code (invalidationGeneration, lastRefreshTime)}.
     * <p>
     * Reading the generation and then stamping the timestamp is a check-then-act,
     * and an invalidation landing between those two steps is exactly the case being
     * defended against: it would zero the timestamp only for the stamp to overwrite
     * it a moment later. The counter alone narrows that window, it does not close
     * it. Both sides take this lock, so the check and the stamp are one step, as
     * are the increment and the zeroing.
     */
    private final Object cacheStateLock = new Object();

    /**
     * Thread → locked target (prevents mid-thread target switching). TTL-evicted.
     */
    private final ICache<String, ChannelTarget> threadTargetLock;

    @Inject
    public ChannelTargetRouter(IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore descriptorStore,
            IRestAgentAdministration agentAdmin,
            IAgentStore agentStore,
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
     * Resolve a default target for a direct message. Slack DMs use dynamic
     * D-prefixed channel ids unique to each user-bot pair, so no integration names
     * the channel and the DM has to be attributed to an integration some other way.
     * <p>
     * It is attributed by the credentials that authenticated the request, never by
     * position: only an integration whose signing secret is the one that verified
     * the webhook — and whose optional platform identifiers (see
     * {@link #matchesInbound}) agree with the envelope — can answer. Picking "the
     * first integration" instead meant a DM went to whichever entry an unordered
     * map yielded first, which differed between JVMs and so between pods, and let a
     * DM to one Slack app be answered by another app's agent with that app's token.
     * <p>
     * When several integrations qualify (one Slack app serving several channels),
     * the choice is deterministic: an integration that pins more of the identifiers
     * the envelope carries wins, then the lowest name.
     *
     * @param verifiedSigningSecret
     *            the signing secret that verified this request
     * @param inboundIds
     *            platform identifiers from the request envelope (for Slack
     *            {@link #CFG_TEAM_ID} and {@link #CFG_APP_ID}); values may be null
     * @return the default target, or {@code null} for "help" or when no integration
     *         qualifies
     */
    public ResolvedTarget resolveDefaultForDm(String channelType, String messageText,
                                              String verifiedSigningSecret, Map<String, String> inboundIds) {
        if (verifiedSigningSecret == null || verifiedSigningSecret.isBlank()) {
            return null;
        }
        var dmIntegration = integrationForDm(channelType, verifiedSigningSecret, inboundIds);
        if (dmIntegration.isPresent()) {
            return resolveFromIntegration(dmIntegration.get(), messageText);
        }
        // Fallback: a legacy connector authenticated by the same secret (Slack only),
        // chosen by channel id so every pod picks the same one.
        if (CHANNEL_TYPE_SLACK.equals(channelType)) {
            LegacyTarget legacy = legacyMap.entrySet().stream()
                    .filter(e -> secretsEqual(e.getValue().signingSecret(), verifiedSigningSecret))
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            if (legacy == null) {
                return null;
            }
            String trimmed = messageText != null ? messageText.trim() : "";
            if (trimmed.isEmpty() || "help".equalsIgnoreCase(trimmed)) {
                return null;
            }
            return new ResolvedTarget(legacy.toChannelTarget(), messageText, null,
                    legacy.botToken(), legacy.signingSecret());
        }
        return null;
    }

    /**
     * The new-style integration that owns direct messages for the app identified by
     * {@code verifiedSigningSecret} and {@code inboundIds} — the selection
     * {@link #resolveDefaultForDm} makes, without resolving a target. Used to
     * attach credentials to a DM thread whose lock carries none.
     */
    public Optional<ChannelIntegrationConfiguration> integrationForDm(String channelType, String verifiedSigningSecret,
                                                                      Map<String, String> inboundIds) {
        refreshIfNeeded();
        if (verifiedSigningSecret == null || verifiedSigningSecret.isBlank()) {
            return Optional.empty();
        }
        String prefix = (channelType != null ? channelType.toLowerCase(Locale.ROOT) : "") + ":";
        ChannelIntegrationConfiguration best = null;
        int bestPinned = -1;
        for (var entry : integrationMap.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            var cfg = entry.getValue();
            if (!matchesInbound(cfg, verifiedSigningSecret, inboundIds)) {
                continue;
            }
            int pinned = pinnedIdentifierCount(cfg, inboundIds);
            if (best == null || pinned > bestPinned
                    || (pinned == bestPinned && compareNames(cfg, best) < 0)) {
                best = cfg;
                bestPinned = pinned;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Credentials for a reply in a DM thread whose lock names {@code lockedTarget}
     * — a lock on a D-channel carries no integration, because no integration names
     * the channel.
     * <p>
     * Only an integration (or legacy connector) that the verified secret
     * authenticates <b>and</b> that has {@code lockedTarget} among its own targets
     * qualifies. Attaching "whichever integration the secret authenticates" instead
     * would let the holder of one app's secret continue a thread locked to another
     * app's agent — the check that follows would pass by construction.
     *
     * @return a resolved target carrying the locked target and those credentials,
     *         or {@code null} when no qualifying integration or connector exists
     */
    public ResolvedTarget threadCredentialsForDm(String channelType, ChannelTarget lockedTarget, String verifiedSigningSecret,
                                                 Map<String, String> inboundIds) {
        refreshIfNeeded();
        if (lockedTarget == null || verifiedSigningSecret == null || verifiedSigningSecret.isBlank()) {
            return null;
        }
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        String prefix = normalizedType + ":";
        ChannelIntegrationConfiguration best = null;
        int bestPinned = -1;
        for (var entry : integrationMap.entrySet()) {
            var cfg = entry.getValue();
            if (!entry.getKey().startsWith(prefix) || !matchesInbound(cfg, verifiedSigningSecret, inboundIds)
                    || !hasTarget(cfg, lockedTarget)) {
                continue;
            }
            int pinned = pinnedIdentifierCount(cfg, inboundIds);
            if (best == null || pinned > bestPinned || (pinned == bestPinned && compareNames(cfg, best) < 0)) {
                best = cfg;
                bestPinned = pinned;
            }
        }
        if (best != null) {
            return new ResolvedTarget(lockedTarget, null, best, null, null);
        }
        if (CHANNEL_TYPE_SLACK.equals(normalizedType)) {
            return legacyMap.entrySet().stream()
                    .filter(e -> secretsEqual(e.getValue().signingSecret(), verifiedSigningSecret))
                    .filter(e -> sameTarget(e.getValue().toChannelTarget(), lockedTarget))
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> new ResolvedTarget(lockedTarget, null, null, e.getValue().botToken(), e.getValue().signingSecret()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static boolean hasTarget(ChannelIntegrationConfiguration cfg, ChannelTarget target) {
        return cfg.getTargets() != null && cfg.getTargets().stream().anyMatch(t -> sameTarget(t, target));
    }

    private static boolean sameTarget(ChannelTarget a, ChannelTarget b) {
        return a != null && b != null && a.getType() == b.getType() && a.getTargetId() != null
                && a.getTargetId().equals(b.getTargetId());
    }

    /**
     * The Slack workspace every routed integration of {@code channelType} pins, or
     * {@code null} when any of them pins none or they pin different ones. A
     * deployment where this is non-null serves exactly one workspace, so a bare
     * Slack user id there can only ever have meant a user of that workspace.
     */
    public String commonPinnedTeamId(String channelType) {
        refreshIfNeeded();
        String prefix = (channelType != null ? channelType.toLowerCase(Locale.ROOT) : "") + ":";
        String common = null;
        for (var entry : integrationMap.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            String team = entry.getValue().getPlatformConfig().get(CFG_TEAM_ID);
            if (team == null || team.isBlank() || (common != null && !common.equals(team.trim()))) {
                return null;
            }
            common = team.trim();
        }
        return common;
    }

    /**
     * Whether an inbound request, authenticated with {@code verifiedSigningSecret}
     * and carrying {@code inboundIds}, may act on {@code integration}.
     * <p>
     * The secret must be this integration's own: holding one integration's signing
     * secret must never be enough to drive another integration's agents, reply with
     * its bot token or decide its pauses. Each identifier the integration pins
     * ({@link #CFG_TEAM_ID}, {@link #CFG_APP_ID}) must additionally equal the
     * envelope's; a pinned identifier the envelope does not carry fails closed.
     */
    public static boolean matchesInbound(ChannelIntegrationConfiguration integration, String verifiedSigningSecret,
                                         Map<String, String> inboundIds) {
        if (integration == null || integration.getPlatformConfig() == null) {
            return false;
        }
        var platformConfig = integration.getPlatformConfig();
        if (!secretsEqual(platformConfig.get("signingSecret"), verifiedSigningSecret)) {
            return false;
        }
        return identifiersMatch(integration, inboundIds);
    }

    /**
     * Whether every identifier {@code integration} pins ({@link #CFG_TEAM_ID},
     * {@link #CFG_APP_ID}) equals the one in {@code inboundIds}. Unpinned
     * identifiers are not checked; a pinned one missing from the envelope fails.
     */
    public static boolean identifiersMatch(ChannelIntegrationConfiguration integration, Map<String, String> inboundIds) {
        if (integration == null || integration.getPlatformConfig() == null) {
            return false;
        }
        var platformConfig = integration.getPlatformConfig();
        for (String key : INBOUND_ID_KEYS) {
            String pinned = platformConfig.get(key);
            if (pinned == null || pinned.isBlank()) {
                continue;
            }
            String actual = inboundIds != null ? inboundIds.get(key) : null;
            if (!pinned.trim().equals(actual)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link #matchesInbound} for a resolved target, covering legacy connectors
     * too: those carry only a signing secret, so the secret is all that can be
     * checked.
     */
    public static boolean matchesInbound(ResolvedTarget resolved, String verifiedSigningSecret,
                                         Map<String, String> inboundIds) {
        if (resolved == null) {
            return false;
        }
        if (resolved.integration() != null) {
            return matchesInbound(resolved.integration(), verifiedSigningSecret, inboundIds);
        }
        return secretsEqual(resolved.legacySigningSecret(), verifiedSigningSecret);
    }

    /**
     * Whether the channel {@code platformChannelId} is served by an integration (or
     * legacy connector) that {@code verifiedSigningSecret} authenticates. A channel
     * nobody serves is not accepted.
     */
    public boolean channelMatchesInbound(String channelType, String platformChannelId, String verifiedSigningSecret,
                                         Map<String, String> inboundIds) {
        refreshIfNeeded();
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        ChannelIntegrationConfiguration integration = integrationMap.get(normalizedType + ":" + platformChannelId);
        if (integration != null) {
            return matchesInbound(integration, verifiedSigningSecret, inboundIds);
        }
        if (CHANNEL_TYPE_SLACK.equals(normalizedType)) {
            LegacyTarget legacy = legacyMap.get(platformChannelId);
            return legacy != null && secretsEqual(legacy.signingSecret(), verifiedSigningSecret);
        }
        return false;
    }

    private static int pinnedIdentifierCount(ChannelIntegrationConfiguration cfg, Map<String, String> inboundIds) {
        int count = 0;
        for (String key : INBOUND_ID_KEYS) {
            String pinned = cfg.getPlatformConfig().get(key);
            if (pinned != null && !pinned.isBlank() && inboundIds != null && inboundIds.get(key) != null) {
                count++;
            }
        }
        return count;
    }

    private static int compareNames(ChannelIntegrationConfiguration a, ChannelIntegrationConfiguration b) {
        String an = a.getName() != null ? a.getName() : "";
        String bn = b.getName() != null ? b.getName() : "";
        return an.compareTo(bn);
    }

    /** Constant-time comparison; a null or blank secret never matches. */
    static boolean secretsEqual(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
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
     * Find the integration whose {@code hitlApprovalChannel} equals
     * {@code approvalChannelId}. Used by the interactivity endpoint to resolve
     * which integration owns an approval message (and thus which approver list and
     * bot token govern the decision).
     * <p>
     * <b>Caveat:</b> when two integrations of the same type share one
     * {@code hitlApprovalChannel}, the first match (unspecified map order) is
     * returned — see {@link #getIntegrationByName}, which HITL decisions prefer
     * because the owning integration is carried explicitly in the button value.
     *
     * @return the owning integration, or empty if none is configured to post HITL
     *         approvals to this channel
     */
    public Optional<ChannelIntegrationConfiguration> getIntegrationByApprovalChannel(String channelType,
                                                                                     String approvalChannelId) {
        refreshIfNeeded();
        if (approvalChannelId == null || approvalChannelId.isBlank()) {
            return Optional.empty();
        }
        String prefix = (channelType != null ? channelType.toLowerCase(Locale.ROOT) : "") + ":";
        for (var entry : integrationMap.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            var cfg = entry.getValue();
            var platformConfig = cfg.getPlatformConfig();
            if (approvalChannelId.equals(platformConfig.get("hitlApprovalChannel"))) {
                return Optional.of(cfg);
            }
        }
        return Optional.empty();
    }

    /**
     * Find the new-style integration with the given (case-sensitive) name for a
     * channel type. Unlike {@link #getIntegrationByApprovalChannel}, this resolves
     * a specific integration deterministically even when several share one
     * {@code hitlApprovalChannel} — the HITL interactivity handler carries the
     * owning integration name in the approval button value and authorizes/verifies
     * against exactly that integration (prevents cross-integration IDOR and the
     * shared-channel nondeterminism).
     *
     * @return the named integration, or empty if none matches
     */
    public Optional<ChannelIntegrationConfiguration> getIntegrationByName(String channelType, String name) {
        refreshIfNeeded();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String prefix = (channelType != null ? channelType.toLowerCase(Locale.ROOT) : "") + ":";
        ChannelIntegrationConfiguration found = null;
        for (var entry : integrationMap.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            var cfg = entry.getValue();
            if (name.equals(cfg.getName())) {
                if (found != null) {
                    // Two integrations share the name. The name is what binds a HITL
                    // decision to its integration, so returning either would let the
                    // other's secret and approver list govern it — and which one came
                    // first depended on map order. Fail closed; the store refuses a
                    // duplicate name, so only data written before that can get here.
                    LOGGER.warnf("Two integrations of type %s are named '%s' — refusing to bind a decision to either",
                            sanitize(channelType), sanitize(name));
                    return Optional.empty();
                }
                found = cfg;
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * The observe-mode targets configured for this channel, in configuration order.
     *
     * Deliberately separate from {@link #resolveTarget}, which answers "who was
     * this message addressed to". An observer is addressed to nobody: it watches
     * traffic it was not part of, so it must never be reachable as a trigger match
     * or as the default target for a mention, and a channel with no observers must
     * keep behaving exactly as it did before this existed. Whether any of these
     * should actually answer is {@code ObserveGate}'s decision, not the router's.
     *
     * Legacy {@code ChannelConnector} entries have no observe configuration and so
     * never appear here.
     *
     * @return the observers for this channel, or an empty list — never null
     */
    public List<ChannelTarget> observeCandidates(String channelType, String platformChannelId) {
        refreshIfNeeded();
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        ChannelIntegrationConfiguration integration = integrationMap.get(normalizedType + ":" + platformChannelId);
        if (integration == null || integration.getTargets() == null) {
            return List.of();
        }
        return integration.getTargets().stream()
                .filter(ChannelTarget::isObserveMode)
                .toList();
    }

    /**
     * The integration serving this channel, for a caller that already holds a
     * target from {@link #observeCandidates} and needs its credentials.
     */
    public ChannelIntegrationConfiguration integrationFor(String channelType, String platformChannelId) {
        refreshIfNeeded();
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        return integrationMap.get(normalizedType + ":" + platformChannelId);
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
        if (integration != null) {
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
                    // Observers are excluded here for the same reason
                    // `findDefaultTarget` excludes them: an observer watches
                    // traffic it was not part of, under a cooldown and daily caps
                    // that the addressed path does not apply. Its `triggers` are
                    // an addressed-routing field it has no use for — keyword and
                    // MIME matching for an observer live in `ObserveConfig` — so
                    // one left set made the observer reachable as
                    // `architect: ...`, running its agent with no limits at all,
                    // as often as anyone cared to type it.
                    if (target.isObserveMode()) {
                        continue;
                    }
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

    /**
     * The target an addressed message falls back to when no trigger matched.
     * <p>
     * Observers are excluded. An observer watches traffic it was not part of, so
     * making it the answer to "the user mentioned the bot and named no trigger"
     * inverts what it is for — and would let the same target answer both addressed
     * and unaddressed messages, each under a different set of limits.
     * {@code RestChannelIntegrationStore} refuses to store that pairing, so this
     * only fires for a document written straight to the datastore, past the REST
     * validation.
     */
    private ChannelTarget findDefaultTarget(ChannelIntegrationConfiguration integration) {
        String defaultName = integration.getDefaultTargetName();
        if (defaultName == null || integration.getTargets() == null)
            return null;
        return integration.getTargets().stream()
                .filter(t -> t.getName() != null
                        && t.getName().equalsIgnoreCase(defaultName))
                .filter(t -> !t.isObserveMode())
                .findFirst()
                .orElse(null);
    }

    // ─── Refresh ───────────────────────────────────────────────────────────────

    /**
     * Drop the resolved-secret cache the moment a vault secret changes, instead of
     * waiting out the poll interval.
     * <p>
     * This cache holds bot tokens and signing secrets already RESOLVED to their
     * plaintext values, so after a rotation it keeps presenting the revoked
     * credential — for up to a minute of inbound webhooks, every one of which fails
     * against the platform. Every other credential-holding cache in the codebase
     * registers for this; the poll made the gap look bounded rather than absent,
     * which is why it went unnoticed.
     * <p>
     * Zeroing the timestamp rather than refreshing inline: refreshing here would
     * run store reads on whatever thread happened to write a secret, and the next
     * inbound message rebuilds the maps anyway.
     */
    @PostConstruct
    void registerSecretInvalidation() {
        secretResolver.registerInvalidationListener(reference -> {
            synchronized (cacheStateLock) {
                invalidationGeneration.incrementAndGet();
                lastRefreshTime = 0;
            }
            LOGGER.info("Channel integration cache marked stale after a vault secret change");
        });
    }

    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshTime < REFRESH_INTERVAL_MS) {
            return;
        }
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        // Read BEFORE the store reads below. An invalidation that arrives while
        // they are in flight would otherwise zero the timestamp only for this
        // method to stamp it fresh again a moment later — with maps built from
        // rows read before the rotation. The cache would then serve the revoked
        // credential for a full interval, which is exactly the window the
        // invalidation listener exists to close.
        long generationAtStart = invalidationGeneration.get();
        try {
            refreshInternal();
            synchronized (cacheStateLock) {
                if (invalidationGeneration.get() == generationAtStart) {
                    lastRefreshTime = now;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to refresh channel target router", e);
            // Stamped even when an invalidation raced, unlike the success path: the
            // maps are stale either way, and a store that just failed will fail
            // again on the next inbound message. Retrying it per webhook trades a
            // stale cache for a hot loop against a store that is already down.
            lastRefreshTime = now;
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
                    "", 0, IDescriptorStore.NO_LIMIT, false);
            for (var descriptor : descriptors) {
                try {
                    var resId = extractResourceId(descriptor.getResource());
                    var config = channelStore.read(resId.getId(),
                            resId.getVersion());
                    if (config != null && config.getChannelType() != null) {

                        // Deep-copy before resolving secrets so the store's
                        // cached instance keeps vault references intact
                        String channelId = config.getPlatformConfig().get("channelId");
                        if (channelId != null && !channelId.isBlank()) {
                            var copy = deepCopyConfig(config);
                            copy.setResourceId(resId.getId());
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
                    AgentConfiguration agentConfig = agentStore.read(
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
        copy.setResourceId(src.getResourceId());
        copy.setPlatformConfig(new HashMap<>(src.getPlatformConfig()));
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
            if (integration != null) {
                return integration.getPlatformConfig().get("botToken");
            }
            return legacyBotToken;
        }

        /** Get signing secret — from integration or legacy. */
        public String signingSecret() {
            if (integration != null) {
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
