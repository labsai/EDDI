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
import ai.labs.eddi.configs.channels.model.WebhookChannelSettings;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.api.IDeploymentStatusReader;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.integrations.channels.types.SlackChannelType;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RestUtilities.extractResourceId;

/**
 * Target router for channel integrations. Resolves incoming channel messages to
 * the correct {@link ChannelTarget} based on configured trigger keywords
 * (colon-required syntax: {@code keyword: message}).
 * <p>
 * Platform-agnostic: signing-secret aggregation and target resolution are keyed
 * by {@code channelType} so multiple platform adapters can coexist. Currently
 * {@code slack}, {@code webhook} and {@code email} are registered (see
 * {@link ChannelTypeRegistry}, one {@link ChannelTypeDescriptor} per type);
 * only Slack also has legacy {@code ChannelConnector} entries.
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
    private static final String CHANNEL_TYPE_WEBHOOK = "webhook";
    private static final String KEY_BOT_TOKEN = "botToken";
    private static final String KEY_SIGNING_SECRET = "signingSecret";

    /**
     * The only platform settings whose {@code ${vault:...}} references the router
     * resolves. Everything else in {@code platformConfig} is configuration or
     * content (a webhook's {@code inputTemplate} becomes conversation input), and
     * expanding a reference there would hand the plaintext of any secret the editor
     * can name to whoever reads that conversation.
     */
    static final Set<String> CREDENTIAL_KEYS = Set.of(KEY_BOT_TOKEN, KEY_SIGNING_SECRET);

    /**
     * Where a Slack bot token is sent - checked against the secret's allowedHosts.
     */
    private static final String SLACK_API_DESTINATION = SlackChannelType.API_BASE;

    private final IChannelIntegrationStore channelStore;
    private final IDocumentDescriptorStore descriptorStore;
    private final IDeploymentStatusReader agentAdmin;
    private final IAgentStore agentStore;
    private final SecretResolver secretResolver;
    private final VaultHostAllowlist vaultHostAllowlist;
    private final ChannelTypeRegistry channelTypes;

    // ─── Cached state (atomic reference swap) ──────────────────────────────────

    /**
     * channelType:channelId → deep-copied ChannelIntegrationConfiguration with
     * resolved secrets. These cached instances may be returned by router methods
     * (e.g., {@link #getIntegration}) and must be treated as sensitive internal
     * data that must not be logged or serialized. The REST layer reads from the
     * store directly and returns vault references instead.
     */
    private volatile Map<String, ChannelIntegrationConfiguration> integrationMap = Map.of();

    /**
     * Channel type → its distinct indexed integrations, ordered by their lowest
     * routing key. An integration indexed under several keys (a channel and an
     * application id) appears once here, so the scans that look for "the
     * integration with this name / secret / approval channel" see it once and do
     * not mistake it for two integrations.
     */
    private volatile TypeIndex integrationsByType = new TypeIndex(Map.of(), Map.of());

    /**
     * The per-type view of one {@link #integrationMap} snapshot. Derived from the
     * map it was built from (and rebuilt when that map is swapped), so the two can
     * never disagree.
     */
    private record TypeIndex(Map<String, ChannelIntegrationConfiguration> source,
            Map<String, List<ChannelIntegrationConfiguration>> byType) {
    }

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
     * Shortest webhook signing secret the router will hand to the verifier: a floor
     * below which the key is brute-forceable offline from one captured request (the
     * endpoint is public), not a recommendation - use 32 random bytes.
     */
    public static final int MIN_WEBHOOK_SECRET_LENGTH = WebhookChannelSettings.MIN_SECRET_LENGTH;

    /**
     * Webhook integration versions already warned about, so a refresh does not
     * repeat it; cleared when a vault secret changes, so a secret that is still
     * wrong after a rotation is reported again.
     */
    private final Set<String> weakWebhookSecretWarned = ConcurrentHashMap.newKeySet();

    /**
     * Credentials already warned about as unusable (an unresolved reference, or a
     * bot token whose secret may not go to Slack), so a refresh does not repeat the
     * warning every minute.
     */
    private final Set<String> unusableCredentialWarned = ConcurrentHashMap.newKeySet();
    /**
     * Dropped keys already counted, per integration version and key. Separate from
     * {@link #unusableCredentialWarned}, which a vault change clears so rotated
     * credentials warn again: a dropped key is no credential, and a vault change
     * must not count it a second time.
     */
    private final Set<String> droppedKeyCounted = ConcurrentHashMap.newKeySet();
    private final AtomicInteger weakWebhookSecretWarnings = new AtomicInteger();

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
            IDeploymentStatusReader agentAdmin,
            IAgentStore agentStore,
            SecretResolver secretResolver,
            VaultHostAllowlist vaultHostAllowlist,
            ICacheFactory cacheFactory,
            ChannelTypeRegistry channelTypes) {
        this.channelTypes = channelTypes != null ? channelTypes : ChannelTypeRegistry.builtIns();
        this.channelStore = channelStore;
        this.descriptorStore = descriptorStore;
        this.agentAdmin = agentAdmin;
        this.agentStore = agentStore;
        this.secretResolver = secretResolver;
        this.vaultHostAllowlist = vaultHostAllowlist;
        this.threadTargetLock = cacheFactory.getCache("channel-thread-locks", Duration.ofHours(24));
    }

    /** With the built-in channel types only — for tests. */
    public ChannelTargetRouter(IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore descriptorStore,
            IDeploymentStatusReader agentAdmin,
            IAgentStore agentStore,
            SecretResolver secretResolver,
            VaultHostAllowlist vaultHostAllowlist,
            ICacheFactory cacheFactory) {
        this(channelStore, descriptorStore, agentAdmin, agentStore, secretResolver, vaultHostAllowlist, cacheFactory,
                ChannelTypeRegistry.builtIns());
    }

    /** Without the destination check — for tests that do not exercise it. */
    public ChannelTargetRouter(IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore descriptorStore,
            IDeploymentStatusReader agentAdmin,
            IAgentStore agentStore,
            SecretResolver secretResolver,
            ICacheFactory cacheFactory) {
        this(channelStore, descriptorStore, agentAdmin, agentStore, secretResolver, null, cacheFactory);
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
        ChannelIntegrationConfiguration integration = byChannel(key);
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
     * Resolves against the first available integration of the given channel type
     * (lowest routing key first; the trigger syntax of
     * {@link #resolveFromIntegration} applies), falling back to the first legacy
     * Slack connector. Returns {@code null} if no integrations exist or the message
     * is blank or "help", and always for a connection-backed type, whose
     * integrations may belong to different tenants (see
     * {@link #resolveDefaultForOrigin}).
     */
    public ResolvedTarget resolveDefaultForDm(String channelType, String messageText) {
        refreshIfNeeded();
        if (channelTypes.find(channelType).map(ChannelTypeDescriptor::connectionBacked).orElse(false)) {
            // A connection-backed type's integrations may belong to different tenants:
            // "the first one" is no owner. Its DMs go through resolveDefaultForOrigin.
            return null;
        }
        for (var integration : integrationsOf(channelType)) {
            return resolveFromIntegration(integration, messageText);
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
     * Resolve a DM's default target from ONE named integration — the one whose
     * signing secret authenticated the event.
     * <p>
     * {@link #resolveDefaultForDm(String, String)} takes the first integration in
     * map order, which with several Slack apps is an arbitrary one: a DM to app B
     * could run on app A's default target and credentials. The webhook knows which
     * app signed the request, so it names the integration and this uses only that
     * one.
     *
     * @return the named integration's default (or trigger-matched) target, or
     *         {@code null} if the integration is unknown or has none
     */
    public ResolvedTarget resolveDefaultForDm(String channelType, String messageText, String integrationName) {
        if (integrationName == null) {
            return resolveDefaultForDm(channelType, messageText);
        }
        return getIntegrationByName(channelType, integrationName)
                .map(integration -> resolveFromIntegration(integration, messageText))
                .orElse(null);
    }

    /**
     * Resolve a DM's default target from the legacy connector whose secret verified
     * it — the DM counterpart of
     * {@link #resolveDefaultForDm(String, String, String)} for a legacy per-agent
     * connector, which has no name. Only legacy connectors holding
     * {@code verifiedSigningSecret} are considered (lowest channel key first); a
     * new-style integration never is, so a DM to a legacy app cannot be routed to
     * another app's agent and credentials.
     *
     * @return the connector's target with its credentials, or {@code null}
     */
    public ResolvedTarget resolveLegacyDefaultForDm(String messageText, String verifiedSigningSecret) {
        refreshIfNeeded();
        String trimmed = messageText != null ? messageText.trim() : "";
        if (trimmed.isEmpty() || "help".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return legacyMap.entrySet().stream()
                .filter(entry -> secretsEqual(entry.getValue().signingSecret(), verifiedSigningSecret))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ResolvedTarget(entry.getValue().toChannelTarget(), messageText, null,
                        entry.getValue().botToken(), entry.getValue().signingSecret()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Credentials for a thread reply whose lock carries none — a thread in a
     * channel no integration owns (a DM). Only an integration (lowest key first) or
     * legacy connector that holds {@code verifiedSigningSecret} AND has
     * {@code lockedTarget} among its own targets qualifies, so one app's secret
     * cannot continue a thread locked to another app's agent.
     *
     * @return the locked target with that owner's credentials, or {@code null} when
     *         no integration or connector of the verifying app serves it
     */
    public ResolvedTarget threadCredentialsForDm(String channelType, ChannelTarget lockedTarget,
                                                 String verifiedSigningSecret) {
        if (verifiedSigningSecret == null || verifiedSigningSecret.isBlank()) {
            return null;
        }
        return threadCredentialsForDm(lockedTarget, InboundOrigin.signedBy(channelType, null, verifiedSigningSecret));
    }

    /**
     * {@link #threadCredentialsForDm(String, ChannelTarget, String)} for any
     * {@link InboundOrigin}: only an integration (lowest routing key first) the
     * origin verifiably belongs to — the same signing secret, or the verified
     * application's channel-less integration (never a channel-bound one of the same
     * application) — AND that has {@code lockedTarget} among its own targets
     * qualifies; for a Slack signing secret, the legacy connectors after that.
     *
     * @return the locked target with that owner's credentials, or {@code null}
     */
    public ResolvedTarget threadCredentialsForDm(ChannelTarget lockedTarget, InboundOrigin origin) {
        refreshIfNeeded();
        if (lockedTarget == null || origin == null) {
            return null;
        }
        String normalizedType = origin.channelType() != null ? origin.channelType().toLowerCase(Locale.ROOT) : "";
        var integration = integrationsOf(normalizedType).stream()
                .filter(cfg -> belongsTo(cfg, origin))
                .filter(cfg -> cfg.getTargets() != null
                        && cfg.getTargets().stream().anyMatch(target -> sameTarget(target, lockedTarget)))
                .findFirst();
        if (integration.isPresent()) {
            return new ResolvedTarget(lockedTarget, null, integration.get(), null, null);
        }
        String verifiedSigningSecret = origin.verifiedSigningSecret();
        if (CHANNEL_TYPE_SLACK.equals(normalizedType) && verifiedSigningSecret != null && !verifiedSigningSecret.isBlank()) {
            return legacyMap.entrySet().stream()
                    .filter(entry -> secretsEqual(entry.getValue().signingSecret(), verifiedSigningSecret))
                    .filter(entry -> sameTarget(entry.getValue().toChannelTarget(), lockedTarget))
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new ResolvedTarget(lockedTarget, null, null, entry.getValue().botToken(),
                            entry.getValue().signingSecret()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    /**
     * Resolve a DM's (or any channel-less message's) default target from the
     * integration its verified origin names. A verified application id resolves to
     * that application's channel-less integration only, and a name given with it
     * must name that same integration. A verified signing secret resolves to the
     * named integration if it holds that secret, else (Slack) to the legacy
     * connector holding it. Fails closed: an application id no integration answers
     * for, a name with no secret or application id, or no verified identity at all
     * resolves to nothing, never to some other integration of the type.
     *
     * @return the target, or {@code null} for help, a blank message or no owner
     */
    public ResolvedTarget resolveDefaultForOrigin(InboundOrigin origin, String messageText) {
        if (origin == null) {
            return null;
        }
        String applicationId = origin.verifiedApplicationId();
        if (applicationId != null && !applicationId.isBlank()) {
            // The application's DMs belong to its channel-less integration alone. A
            // name (which may come from a request body, a card action) can only
            // confirm that integration, never pick a channel-bound one of the same
            // application, which may be another tenant's.
            var owner = getIntegrationForApplication(origin.channelType(), applicationId);
            if (origin.integrationName() != null) {
                owner = owner.filter(integration -> getIntegrationByName(origin.channelType(), origin.integrationName())
                        .filter(named -> named == integration)
                        .isPresent());
            }
            return owner.map(integration -> resolveFromIntegration(integration, messageText)).orElse(null);
        }
        String signingSecret = origin.verifiedSigningSecret();
        if (signingSecret == null || signingSecret.isBlank()) {
            // A bare name proves nothing: it may come from a URL or a request body.
            return null;
        }
        if (origin.integrationName() != null) {
            return getIntegrationByName(origin.channelType(), origin.integrationName())
                    .filter(integration -> belongsTo(integration, origin))
                    .map(integration -> resolveFromIntegration(integration, messageText))
                    .orElse(null);
        }
        if (CHANNEL_TYPE_SLACK.equalsIgnoreCase(origin.channelType())) {
            return resolveLegacyDefaultForDm(messageText, signingSecret);
        }
        // No verified identity this method can map to an owner: never "the first
        // integration of the type".
        return null;
    }

    /**
     * The integration that answers for {@code applicationId} without a channel —
     * the one indexed under its application key (a Teams bot serving personal
     * chats), and only when it still declares that application.
     * <p>
     * Deliberately no fallback to a channel-bound integration that declares the
     * application: several integrations may declare one application (one per
     * Discord channel), and nothing proves which of them owns its DMs, so "the
     * lowest-keyed one" could hand one tenant's DM to another tenant's agent and
     * connection. Without a channel-less integration the application has no DM
     * route.
     */
    public Optional<ChannelIntegrationConfiguration> getIntegrationForApplication(String channelType, String applicationId) {
        refreshIfNeeded();
        if (applicationId == null || applicationId.isBlank()) {
            return Optional.empty();
        }
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        var direct = integrationMap.get(normalizedType + ":" + ChannelTypeDescriptor.applicationKey(applicationId));
        if (direct == null) {
            return Optional.empty();
        }
        // Re-checked rather than trusted from the key, so the answer is the
        // integration that declares this application and nothing else. A
        // descriptor's routing keys are not proof of channel-lessness either: one
        // that emits the application key for a channel-bound integration must not
        // hand that channel's tenant the application's direct messages.
        String boundChannel = direct.getPlatformConfig() == null
                ? null
                : direct.getPlatformConfig().get(ChannelTypeDescriptor.KEY_CHANNEL_ID);
        if (boundChannel != null && !boundChannel.isBlank()) {
            return Optional.empty();
        }
        boolean declares = channelTypes.find(normalizedType)
                .flatMap(descriptor -> descriptor.applicationId(direct.getPlatformConfig()))
                .filter(applicationId::equals)
                .isPresent();
        return declares ? Optional.of(direct) : Optional.empty();
    }

    /**
     * Whether {@code integration} is the one {@code origin} was verified for: the
     * same signing secret (constant-time), or — for a verified application id —
     * that application's channel-less integration
     * ({@link #getIntegrationForApplication}). Not merely any integration that
     * declares the application: channel-bound ones of one application may belong to
     * different tenants, and nothing in a DM says which.
     */
    private boolean belongsTo(ChannelIntegrationConfiguration integration, InboundOrigin origin) {
        Map<String, String> platformConfig = integration.getPlatformConfig();
        if (platformConfig == null) {
            return false;
        }
        if (origin.verifiedSigningSecret() != null && secretsEqual(platformConfig.get("signingSecret"), origin.verifiedSigningSecret())) {
            return true;
        }
        String applicationId = origin.verifiedApplicationId();
        if (applicationId == null || applicationId.isBlank()) {
            return false;
        }
        return getIntegrationForApplication(integration.getChannelType(), applicationId)
                .filter(owner -> owner == integration)
                .isPresent();
    }

    /**
     * The integration indexed under a {@code type:channelId} key, or {@code null}.
     * Never answers an application key: a channel id read from a request body that
     * happens to start with {@code @app:} must not reach the integration that
     * answers for that application.
     */
    private ChannelIntegrationConfiguration byChannel(String typeAndChannel) {
        int colon = typeAndChannel.indexOf(':');
        if (colon >= 0 && typeAndChannel.startsWith(ChannelTypeDescriptor.APPLICATION_KEY_PREFIX, colon + 1)) {
            return null;
        }
        return integrationMap.get(typeAndChannel);
    }

    /** The distinct indexed integrations of a type, lowest routing key first. */
    /**
     * Whether any integration of {@code channelType}, channel-bound or not,
     * declares {@code applicationId}. Lets an adapter refuse a token whose audience
     * no integration names before doing any work for it.
     */
    public boolean declaresApplication(String channelType, String applicationId) {
        refreshIfNeeded();
        if (applicationId == null || applicationId.isBlank()) {
            return false;
        }
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        Optional<ChannelTypeDescriptor> descriptor = channelTypes.find(normalizedType);
        if (descriptor.isEmpty()) {
            return false;
        }
        return integrationsOf(normalizedType).stream()
                .anyMatch(cfg -> descriptor.get().applicationId(cfg.getPlatformConfig()).filter(applicationId::equals).isPresent());
    }

    private List<ChannelIntegrationConfiguration> integrationsOf(String channelType) {
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        Map<String, ChannelIntegrationConfiguration> current = integrationMap;
        TypeIndex index = integrationsByType;
        if (index.source() != current) {
            // A benign race: two threads may both rebuild it from the same snapshot.
            index = new TypeIndex(current, distinctByType(current));
            integrationsByType = index;
        }
        return index.byType().getOrDefault(normalizedType, List.of());
    }

    private static boolean sameTarget(ChannelTarget a, ChannelTarget b) {
        return a != null && b != null && a.getType() == b.getType() && a.getTargetId() != null
                && a.getTargetId().equals(b.getTargetId());
    }

    /**
     * Constant-time comparison of two signing secrets; {@code false} when either is
     * missing or blank.
     */
    public static boolean secretsEqual(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        return HmacSignatures.constantTimeEquals(a, b);
    }

    /**
     * The signing secret of whoever owns {@code platformChannelId}: the new-style
     * integration configured for it, else (Slack only) the legacy connector.
     * <p>
     * The events webhook verifies every request against this once it has read the
     * channel out of the body. Verifying only against the POOLED set of secrets
     * proves the request came from SOME configured app, not from the one that owns
     * the channel — so a holder of one integration's secret could otherwise drive
     * another integration's agents, credentials and approval flow.
     *
     * @return the owner's secret; empty when nobody owns the channel (a DM, or an
     *         unconfigured channel) or the owner has no secret configured
     */
    public Optional<String> getSigningSecretForChannel(String channelType, String platformChannelId) {
        refreshIfNeeded();
        if (platformChannelId == null || platformChannelId.isBlank()) {
            return Optional.empty();
        }
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        ChannelIntegrationConfiguration integration = byChannel(normalizedType + ":" + platformChannelId);
        if (integration != null) {
            return nonBlank(integration.getPlatformConfig().get("signingSecret"));
        }
        if (CHANNEL_TYPE_SLACK.equals(normalizedType)) {
            LegacyTarget legacy = legacyMap.get(platformChannelId);
            if (legacy != null) {
                return nonBlank(legacy.signingSecret());
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a deployed agent's legacy {@code ChannelConnector} binds
     * {@code platformChannelId} — the authority a channel-integration save asks
     * before it may claim a channel.
     *
     * <p>
     * Read from the deployments and the deployed agent configurations themselves,
     * not from {@link #legacyMap}: that cache is rebuilt at most once per
     * {@link #REFRESH_INTERVAL_MS} and only a vault-secret change invalidates it,
     * so a connector deployed a moment ago is absent from it, and a save judged
     * against it could take that agent's routing over. Standalone integrations are
     * ignored for the same reason in the other direction; the integration store is
     * the authority on them. Channel saves are rare, so a live read is affordable.
     * </p>
     *
     * @throws IllegalStateException
     *             when the deployments or a deployed agent cannot be read, so the
     *             caller can refuse the save rather than guess
     */
    public boolean isServedByLegacyConnector(String channelType, String platformChannelId) {
        if (platformChannelId == null || platformChannelId.isBlank()) {
            return false;
        }
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        if (!CHANNEL_TYPE_SLACK.equals(normalizedType)) {
            return false;
        }
        List<AgentDeploymentStatus> statuses;
        try {
            statuses = agentAdmin.readAllDeploymentStatuses(Deployment.Environment.production);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not read the agent deployments", e);
        }
        for (AgentDeploymentStatus status : statuses) {
            if (status.getDescriptor() == null || status.getDescriptor().isDeleted()) {
                continue;
            }
            AgentConfiguration agentConfig;
            try {
                agentConfig = agentStore.read(status.getAgentId(), status.getAgentVersion());
            } catch (IResourceStore.ResourceNotFoundException gone) {
                continue; // a deployment of a deleted agent routes nothing
            } catch (IResourceStore.ResourceStoreException | RuntimeException e) {
                throw new IllegalStateException("Could not read deployed agent " + sanitize(status.getAgentId()), e);
            }
            if (agentConfig != null && agentConfig.getChannels() != null) {
                for (ChannelConnector connector : agentConfig.getChannels()) {
                    if (connector != null && connector.getType() != null
                            && connector.getType().toString().equalsIgnoreCase(CHANNEL_TYPE_SLACK)
                            && connector.getConfig() != null
                            && platformChannelId.equals(connector.getConfig().get("channelId"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether any integration or legacy connector owns {@code platformChannelId}.
     * An owned channel whose owner has no signing secret can never be verified, and
     * the webhook refuses its events rather than falling back to the pool.
     */
    public boolean isChannelOwned(String channelType, String platformChannelId) {
        refreshIfNeeded();
        if (platformChannelId == null || platformChannelId.isBlank()) {
            return false;
        }
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        if (byChannel(normalizedType + ":" + platformChannelId) != null) {
            return true;
        }
        return CHANNEL_TYPE_SLACK.equals(normalizedType) && legacyMap.containsKey(platformChannelId);
    }

    /**
     * Every configured signing identity of a channel type: each new-style
     * integration's name with its secret, then each legacy connector's secret with
     * a {@code null} name. Used to find which app signed an event whose channel
     * nobody owns (a DM), so it can be routed to that app's integration.
     */
    public List<SigningIdentity> getSigningIdentities(String channelType) {
        refreshIfNeeded();
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        List<SigningIdentity> identities = new ArrayList<>();
        integrationsOf(normalizedType)
                .forEach(integration -> nonBlank(integration.getPlatformConfig().get("signingSecret"))
                        .ifPresent(secret -> identities.add(new SigningIdentity(integration.getName(), secret))));
        if (CHANNEL_TYPE_SLACK.equals(normalizedType)) {
            legacyMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> nonBlank(entry.getValue().signingSecret())
                            .ifPresent(secret -> identities.add(new SigningIdentity(null, secret))));
        }
        return identities;
    }

    private static Optional<String> nonBlank(String value) {
        return value != null && !value.isBlank() ? Optional.of(value) : Optional.empty();
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
        ChannelIntegrationConfiguration integration = byChannel(key);

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
        return Optional.ofNullable(byChannel(normalizedType + ":" + platformChannelId));
    }

    /**
     * The webhook integration with resource id {@code integrationId}, with its
     * platform secrets resolved. Treat the result as sensitive: never log or
     * serialize it.
     *
     * @return the integration, or empty when no webhook integration has that id (or
     *         it was created less than a refresh interval ago)
     */
    public Optional<ChannelIntegrationConfiguration> getWebhookIntegration(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return Optional.empty();
        }
        return getIntegration(CHANNEL_TYPE_WEBHOOK, integrationId);
    }

    /**
     * Route an inbound event by its type.
     * <p>
     * The counterpart of {@link #resolveFromIntegration} for channels whose traffic
     * is events rather than chat: there is no {@code trigger: message} syntax to
     * parse, the event type IS the trigger. A target matches when one of its
     * triggers equals the type (case-insensitive) or, for a trigger ending in
     * {@code *}, when the type starts with what precedes the star
     * ({@code com.github.*}). An exact match wins over a prefix match, and a longer
     * prefix over a shorter one, so a specific route can carve an exception out of
     * a broad one. No match falls back to the default target. Observers are never
     * routed to, for the same reasons as on the chat path.
     *
     * @return the target, with {@code strippedMessage} left null; {@code null} when
     *         nothing matches and there is no usable default
     */
    public ResolvedTarget resolveEventTarget(ChannelIntegrationConfiguration integration, String eventType) {
        if (integration == null) {
            return null;
        }
        String type = eventType != null ? eventType.trim().toLowerCase(Locale.ROOT) : "";
        ChannelTarget best = null;
        int bestScore = -1;
        if (integration.getTargets() != null && !type.isEmpty()) {
            for (ChannelTarget target : integration.getTargets()) {
                if (target == null || target.isObserveMode() || target.getTriggers() == null) {
                    continue;
                }
                for (String trigger : target.getTriggers()) {
                    int score = triggerScore(trigger, type);
                    if (score > bestScore) {
                        best = target;
                        bestScore = score;
                    }
                }
            }
        }
        if (best == null) {
            best = findDefaultTarget(integration);
        }
        return best != null ? new ResolvedTarget(best, null, integration, null, null) : null;
    }

    /**
     * How specifically {@code trigger} matches {@code type}: -1 for no match, the
     * prefix length for a {@code prefix*} match, {@code Integer.MAX_VALUE} for an
     * exact match.
     */
    private static int triggerScore(String trigger, String type) {
        if (trigger == null || trigger.isBlank()) {
            return -1;
        }
        String normalized = trigger.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("*")) {
            String prefix = normalized.substring(0, normalized.length() - 1);
            return type.startsWith(prefix) ? prefix.length() : -1;
        }
        return normalized.equals(type) ? Integer.MAX_VALUE : -1;
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
        for (var cfg : integrationsOf(channelType)) {
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
        ChannelIntegrationConfiguration match = null;
        for (var cfg : integrationsOf(channelType)) {
            if (name.equals(cfg.getName())) {
                if (match != null) {
                    // AMBIGUOUS: two integrations share this display name. Names are not
                    // globally unique in older data, and a HITL decision authorizes and
                    // verifies against the integration this resolves to while the
                    // approval record is keyed by the same name string — so resolving to
                    // EITHER of two same-named integrations would let one approve the
                    // other's paused conversation (Finding C). Refuse rather than pick.
                    LOGGER.warnf("Multiple '%s' integrations are named '%s' — refusing to resolve by name",
                            prefix, name);
                    return Optional.empty();
                }
                match = cfg;
            }
        }
        return Optional.ofNullable(match);
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
        ChannelIntegrationConfiguration integration = byChannel(normalizedType + ":" + platformChannelId);
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
        return byChannel(normalizedType + ":" + platformChannelId);
    }

    /**
     * Get the bot token for a channel, checking new-style integrations first, then
     * legacy. Returns {@code null} if no token is configured for this channel.
     */
    public String getBotToken(String channelType, String platformChannelId) {
        refreshIfNeeded();
        String normalizedType = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        String key = normalizedType + ":" + platformChannelId;
        ChannelIntegrationConfiguration integration = byChannel(key);
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
            return !integrationsOf(CHANNEL_TYPE_SLACK).isEmpty() || !legacyMap.isEmpty();
        }
        return !integrationsOf(normalizedType).isEmpty();
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
            weakWebhookSecretWarned.clear();
            unusableCredentialWarned.clear();
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
            // Touch the registry outside the per-document catch: a registry that refuses
            // to construct (a duplicate or malformed type id) must surface as the WARN
            // below, not as one DEBUG "Skipping channel config" per document.
            channelTypes.ids();
            var descriptors = descriptorStore.readDescriptors("ai.labs.channel",
                    "", 0, IDescriptorStore.NO_LIMIT, false);
            for (var descriptor : descriptors) {
                try {
                    var resId = extractResourceId(descriptor.getResource());
                    var config = channelStore.read(resId.getId(),
                            resId.getVersion());
                    if (config != null && config.getChannelType() != null) {

                        // Deep-copy before resolving secrets so the store's
                        // cached instance keeps vault references intact.
                        // The keys come from the type's descriptor: channelId for Slack,
                        // the resource id for a webhook (POST /integrations/webhook/{id},
                        // unique by construction), an application key for a channel-less
                        // integration of a type that declares one, none for email.
                        var typeDescriptor = channelTypes.find(config.getChannelType());
                        boolean connectionBacked = typeDescriptor.isPresent() && typeDescriptor.get().connectionBacked();
                        var copy = deepCopyConfig(config);
                        String integrationKey = resId.getId() + ":" + resId.getVersion();
                        if (connectionBacked) {
                            // Holds no secret: its credential is resolved per send from the
                            // connection it names. Nothing is resolved from the vault, and keys
                            // outside the type's allow-list are dropped BEFORE the routing keys
                            // are read — save validation refuses them, but a direct store write
                            // never ran it, and an undeclared key must not route either.
                            keepAllowedKeysOnly(integrationKey, typeDescriptor.get(), copy);
                        }
                        List<String> routingKeys = channelTypes.routingKeys(config.getChannelType(), resId.getId(),
                                copy.getPlatformConfig());
                        if (!routingKeys.isEmpty()) {
                            if (!connectionBacked) {
                                resolvePlatformSecrets(integrationKey, copy);
                                if (CHANNEL_TYPE_WEBHOOK.equalsIgnoreCase(config.getChannelType())) {
                                    vetWebhookSigningSecret(integrationKey, config, copy);
                                }
                                dropUnresolvedCredentials(integrationKey, copy);
                            }
                            String typePrefix = copy.getChannelType().toLowerCase(Locale.ROOT) + ":";
                            for (String routingKey : routingKeys) {
                                if (routingKey == null || routingKey.isBlank()) {
                                    continue;
                                }
                                String key = typePrefix + routingKey;
                                newIntegrationMap.put(key, copy);
                                coveredChannelKeys.add(key); // type:key — scoped to prevent cross-type suppression
                            }

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
            List<AgentDeploymentStatus> statuses = agentAdmin.readAllDeploymentStatuses(
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
                                    String legacyKey = "legacy:" + agentId + ":" + chId;
                                    String bt = usableCredential(legacyKey, KEY_BOT_TOKEN,
                                            connector.getConfig().get(KEY_BOT_TOKEN));
                                    String ss = usableCredential(legacyKey, KEY_SIGNING_SECRET,
                                            connector.getConfig().get(KEY_SIGNING_SECRET));
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
     * The distinct integrations per type in {@code index}, each ordered by its
     * lowest key — so an integration indexed under several keys is listed once, and
     * one that lost every key to a colliding integration (save validation refuses
     * that, an import does not) is not listed at all, as it is not routable.
     */
    private static Map<String, List<ChannelIntegrationConfiguration>> distinctByType(
                                                                                     Map<String, ChannelIntegrationConfiguration> index) {
        var lowestKey = new IdentityHashMap<ChannelIntegrationConfiguration, String>();
        index.forEach((key, integration) -> lowestKey.merge(integration, key, (a, b) -> a.compareTo(b) <= 0 ? a : b));
        var byType = new HashMap<String, List<ChannelIntegrationConfiguration>>();
        lowestKey.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> {
                    // The integration's own type, not a parse of the key: a stored type may
                    // itself contain ':' (a fork's adapter), and splitting at the first one
                    // would file it under the wrong type and hide it from every scan.
                    String key = entry.getValue();
                    String type = entry.getKey().getChannelType() != null
                            ? entry.getKey().getChannelType().toLowerCase(Locale.ROOT)
                            : key.substring(0, key.indexOf(':'));
                    byType.computeIfAbsent(type, k -> new ArrayList<>()).add(entry.getKey());
                });
        var frozen = new HashMap<String, List<ChannelIntegrationConfiguration>>();
        byType.forEach((type, list) -> frozen.put(type, List.copyOf(list)));
        return Map.copyOf(frozen);
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
        copy.setPlatformConfig(new HashMap<>(src.getPlatformConfig()));
        if (src.getTargets() != null) {
            copy.setTargets(new ArrayList<>(src.getTargets()));
        }
        return copy;
    }

    /**
     * Resolves the vault references of the {@link #CREDENTIAL_KEYS} only. Every
     * other value is kept verbatim: a reference in one stays literal text. A Slack
     * bot token whose secret may not be sent to Slack is removed rather than
     * resolved.
     */
    private void resolvePlatformSecrets(String integrationKey, ChannelIntegrationConfiguration config) {
        Map<String, String> resolved = new HashMap<>(config.getPlatformConfig());
        for (String key : CREDENTIAL_KEYS) {
            if (!resolved.containsKey(key)) {
                continue;
            }
            String raw = resolved.get(key);
            if (KEY_BOT_TOKEN.equals(key) && CHANNEL_TYPE_SLACK.equalsIgnoreCase(config.getChannelType())
                    && !botTokenMayGoToSlack(integrationKey, raw)) {
                resolved.remove(key);
                continue;
            }
            resolved.put(key, resolveSecret(raw));
        }
        config.setPlatformConfig(resolved);
    }

    /**
     * Removes credentials whose vault reference did not resolve, for every channel
     * type. The resolver passes such a reference through unchanged, and its text is
     * public: kept as a signing secret it would let anyone who read the
     * configuration sign requests. A Slack integration without a usable signing
     * secret authenticates nothing, which is the point.
     */
    private void dropUnresolvedCredentials(String integrationKey, ChannelIntegrationConfiguration config) {
        // getPlatformConfig() hands out a copy: edit it, then set it back.
        Map<String, String> resolved = config.getPlatformConfig();
        for (String key : CREDENTIAL_KEYS) {
            String value = resolved.get(key);
            if (value != null && SecretReference.isVaultReference(value)) {
                resolved.remove(key);
                warnUnusable(integrationKey, key, "does not resolve to a vault value", false);
            }
        }
        config.setPlatformConfig(resolved);
    }

    /**
     * Removes every {@code platformConfig} key a connection-backed type does not
     * declare, warning once per integration version and key. Values are never
     * resolved, so whatever such a key held stays out of the router's cache.
     */
    private void keepAllowedKeysOnly(String integrationKey, ChannelTypeDescriptor descriptor,
                                     ChannelIntegrationConfiguration config) {
        Set<String> allowed = descriptor.allowedPlatformConfigKeys().orElse(Set.of());
        Map<String, String> kept = config.getPlatformConfig();
        for (String key : new TreeSet<>(kept.keySet())) {
            if (!allowed.contains(key)) {
                kept.remove(key);
                warnUnusable(integrationKey, key, "is not a " + descriptor.id() + " setting (" + descriptor.id()
                        + " credentials come from the named connection)", false);
                // Counted once per integration version and key — not on every refresh,
                // which would make the counter measure refresh frequency.
                if (droppedKeyCounted.add(integrationKey + "/" + key)) {
                    channelTypes.recordDroppedKey(descriptor.id());
                }
            }
        }
        config.setPlatformConfig(kept);
    }

    /**
     * The resolved value of one legacy credential, or {@code null} when it is
     * absent, unresolved, or a bot token that may not go to Slack.
     */
    private String usableCredential(String integrationKey, String key, String raw) {
        if (KEY_BOT_TOKEN.equals(key) && !botTokenMayGoToSlack(integrationKey, raw)) {
            return null;
        }
        String value = resolveSecret(raw);
        if (value != null && SecretReference.isVaultReference(value)) {
            warnUnusable(integrationKey, key, "does not resolve to a vault value", false);
            return null;
        }
        return value;
    }

    /**
     * Whether the secret behind a Slack bot token reference may be sent to Slack's
     * API under its {@code allowedHosts}. A plaintext token is not the vault's
     * business and passes.
     */
    private boolean botTokenMayGoToSlack(String integrationKey, String raw) {
        if (raw == null || !SecretReference.isVaultReference(raw)) {
            return true;
        }
        try {
            // Every reference in the value, not just the first: the resolver expands all
            // of them, so a token written as "${vault:a}${vault:b}" sends both secrets.
            List<String> references = List.copyOf(VaultHostAllowlist.referencesIn(List.of(raw)));
            vaultHostAllowlist.requireAllowed(references, SLACK_API_DESTINATION, "Slack integration " + integrationKey + " bot token");
            return true;
        } catch (VaultHostAllowlist.HostNotAllowedException e) {
            warnUnusable(integrationKey, KEY_BOT_TOKEN, "may not be sent to slack.com (" + e.getMessage() + ")", true);
            return false;
        }
    }

    /**
     * @param security
     *            a refusal of a destination, tagged {@code [SECURITY]} for the
     *            operators who alert on it; an unresolved secret is a missing
     *            value, not that
     * @return whether this was the first warning for that integration version and
     *         key
     */
    private boolean warnUnusable(String integrationKey, String key, String problem, boolean security) {
        if (unusableCredentialWarned.add(integrationKey + "/" + key)) {
            // The key is the stored document's, not a constant: sanitized like the id.
            LOGGER.warnf("%sChannel integration %s: its %s %s - it is not used until that is fixed", security ? "[SECURITY] " : "",
                    sanitize(integrationKey), sanitize(key), problem);
            return true;
        }
        return false;
    }

    /**
     * Strips a webhook signing secret the endpoint must not trust: one stored in
     * plaintext (save-time validation refuses it, but a ZIP import or a direct
     * store write does not pass through that) or shorter than
     * {@link #MIN_WEBHOOK_SECRET_LENGTH} once resolved. With no secret the verifier
     * answers 401 to every request, so the integration fails closed instead of
     * authenticating with a guessable key. Warned about once per integration
     * version (and again after a vault secret changes).
     */
    private void vetWebhookSigningSecret(String integrationKey, ChannelIntegrationConfiguration stored,
                                         ChannelIntegrationConfiguration resolved) {
        String raw = stored.getPlatformConfig() == null ? null : stored.getPlatformConfig().get("signingSecret");
        String secret = resolved.getPlatformConfig().get("signingSecret");
        if (raw == null || raw.isBlank()) {
            return;
        }
        String problem = null;
        if (!raw.trim().startsWith("${vault:")) {
            problem = "is stored in plaintext (it must be a ${vault:...} reference)";
        } else if (secret != null && (secret.equals(raw) || secret.strip().equals(raw.strip()) || secret.contains("${vault:"))) {
            // Resolution leaves a reference it cannot resolve unchanged (also one with
            // surrounding whitespace). It is long enough to pass the length check, and
            // it is public knowledge - never a key.
            problem = "does not resolve to a vault value";
        } else if (secret != null && secret.length() < MIN_WEBHOOK_SECRET_LENGTH) {
            problem = "is shorter than " + MIN_WEBHOOK_SECRET_LENGTH + " characters";
        }
        if (problem == null) {
            return;
        }
        Map<String, String> without = resolved.getPlatformConfig();
        without.remove("signingSecret");
        resolved.setPlatformConfig(without);
        if (weakWebhookSecretWarned.add(integrationKey)) {
            weakWebhookSecretWarnings.incrementAndGet();
            LOGGER.warnf("Webhook integration %s: its signing secret %s - every request to it is refused (401) until it is fixed",
                    sanitize(integrationKey), problem);
        }
    }

    /** Test seam: weak-secret warnings logged so far. */
    int weakWebhookSecretWarningsLogged() {
        return weakWebhookSecretWarnings.get();
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
     * A signing secret and the integration it belongs to — {@code null} name for a
     * legacy per-agent connector. Holds a resolved secret: never log or serialize.
     */
    public record SigningIdentity(String integrationName, String signingSecret) {
        @Override
        public String toString() {
            return "SigningIdentity[integrationName=" + integrationName + "]";
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
