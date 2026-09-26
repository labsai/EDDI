/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ObserveGate;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core Slack event handler. Receives parsed events from
 * {@link ai.labs.eddi.integrations.slack.rest.RestSlackWebhook}, routes them to
 * the correct EDDI target (agent or group) via {@link ChannelTargetRouter},
 * manages conversation state (via {@link IUserConversationStore}), and posts
 * responses back to Slack (via {@link SlackWebApiClient}).
 * <p>
 * All credentials (bot tokens, signing secrets) are resolved from
 * {@link ChannelTargetRouter} — either from new-style
 * {@code ChannelIntegrationConfiguration} or legacy {@code ChannelConnector}.
 * <p>
 * Key behaviors:
 * <ul>
 * <li>De-duplicates events by {@code event_id} (Slack retries up to 3x)</li>
 * <li>Filters out bot's own messages to prevent infinite loops</li>
 * <li>Strips bot mention prefix from message text</li>
 * <li>Routes via colon-delimited trigger keywords (e.g.,
 * {@code architect:})</li>
 * <li>Thread target locking — first message locks the target for the
 * thread</li>
 * <li>Detects replies in agent threads to route context-aware follow-ups</li>
 * <li>Maps Slack threads → EDDI conversations via IUserConversationStore</li>
 * <li>Processes async to meet Slack's 3-second response requirement</li>
 * </ul>
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class SlackEventHandler {

    private static final Logger LOGGER = Logger.getLogger(SlackEventHandler.class);

    /** Pattern to strip bot mention: {@code <@U0123BOTID> actual message} */
    private static final Pattern BOT_MENTION_PATTERN = Pattern.compile("^<@[A-Z0-9]+>\\s*");

    /**
     * Any user mention, anywhere in the text, in either of the two forms Slack
     * writes. Used only when the event envelope named no bot authorization, so this
     * app's own user id is unknown and every mention has to be treated as possibly
     * its own — see {@code mentionsThisBot}.
     */
    private static final Pattern ANY_MENTION_PATTERN = Pattern.compile("<@[A-Z0-9]+(\\|[^>]*)?>");

    /**
     * Start-context keys a Slack-started conversation carries. The intent is the
     * thread mapping key; the integration name binds the conversation to the
     * integration that started it, for HITL decisions.
     */
    static final String CONTEXT_CHANNEL_INTENT = "channelIntent";
    static final String CONTEXT_CHANNEL_INTEGRATION_ID = "channelIntegrationId";
    static final String CONTEXT_SLACK_USER_ID = "slackUserId";
    static final String CONTEXT_SLACK_TEAM_ID = "slackTeamId";

    /**
     * {@link IUserConversationStore} intent prefix recording which integration
     * started a group discussion; the key's user half is
     * {@code integration:<resourceId>}. A Slack decision on a discussion is
     * accepted only from that integration.
     */
    static final String GROUP_ORIGIN_INTENT_PREFIX = "channel:slack-group-origin:";
    static final String GROUP_ORIGIN_USER_PREFIX = "integration:";

    /** Maximum Slack message length (safe limit under 4000). */
    private static final int MAX_SLACK_MESSAGE_LENGTH = 3900;

    /** Retry budget for reading the HITL bookmark after a pause (see H10). */
    private static final int HITL_BOOKMARK_READ_ATTEMPTS = 5;
    private static final long HITL_BOOKMARK_READ_DELAY_MS = 100;

    /**
     * Sentinel snapshots returned by {@link #sendAndWait} when a queued turn was
     * DROPPED (onSkipped) rather than executed. Distinguishing them from a real
     * pause snapshot prevents a dropped input from being mistaken for a fresh pause
     * (→ duplicate approval card, H4) or for a fresh agent response (→ stale
     * replay, H7). Identity-compared with {@code ==} — never inspected as data.
     */
    private static final SimpleConversationMemorySnapshot SKIPPED_STILL_AWAITING = new SimpleConversationMemorySnapshot();
    private static final SimpleConversationMemorySnapshot SKIPPED_NOT_ACTIVE = new SimpleConversationMemorySnapshot();

    private final ChannelTargetRouter channelTargetRouter;
    private final ObserveGate observeGate;
    private final ToolCostTracker toolCostTracker;
    private final SlackWebApiClient slackApi;
    private final IConversationService conversationService;
    private final IGroupConversationService groupConversationService;
    private final IUserConversationStore userConversationStore;
    private final ICache<String, Boolean> eventDedup;
    private final ExecutorService executorService;

    /**
     * Timeouts and the API retry budget. These were compile-time constants, so an
     * agent whose turn legitimately ran past sixty seconds always failed on Slack
     * and worked over {@code /v1}, with no way to tune it short of a rebuild.
     */
    private final SlackConfig slackConfig;

    /**
     * Tracks active group discussion listeners keyed by Slack message ts. Used to
     * detect user thread-replies for follow-up conversations. Uses ICache with TTL
     * to prevent unbounded growth — follow-ups are only useful shortly after a
     * discussion finishes.
     */
    private final ICache<String, GroupFollowUp> activeGroupListeners;

    /**
     * pause-identity key ({@code conversationId + '|' + hitlPausedAt-epochMillis})
     * → posted marker for approval notifications. Prevents a second approval card
     * for the same pending pause: a card is posted once when a pause is first
     * observed (a normal-turn pause, or an init-turn/CONVERSATION_START pause
     * detected on the next say — H8), and re-message-while-paused must not re-post.
     * Keying by pause identity (not conversationId alone) means a later distinct
     * pause in the same conversation (pause → resume → pause) still gets its own
     * card (F12). The marker is cleared on FAILED delivery so a retry can
     * re-attempt. TTL-bounded to stay small; a stale eviction only risks one
     * duplicate card, never a missed one.
     */
    private final ICache<String, Boolean> approvalNotified;

    /** Long-term memory, for carrying a legacy user's bare-id memories over. */
    private final IUserMemoryStore userMemoryStore;

    /**
     * Namespaced ids whose legacy memories were already carried over (per node).
     */
    private final ICache<String, Boolean> legacyMemoriesCarried;

    @Inject
    public SlackEventHandler(ChannelTargetRouter channelTargetRouter,
            ObserveGate observeGate,
            ToolCostTracker toolCostTracker,
            SlackWebApiClient slackApi,
            IConversationService conversationService,
            IGroupConversationService groupConversationService,
            IUserConversationStore userConversationStore,
            IUserMemoryStore userMemoryStore,
            ICacheFactory cacheFactory,
            SlackConfig slackConfig) {
        this.userMemoryStore = userMemoryStore;
        this.legacyMemoriesCarried = cacheFactory.getCache("slack-legacy-memories-carried", Duration.ofHours(24));
        this.channelTargetRouter = channelTargetRouter;
        this.observeGate = observeGate;
        this.toolCostTracker = toolCostTracker;
        this.slackApi = slackApi;
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.userConversationStore = userConversationStore;
        this.slackConfig = slackConfig;
        this.eventDedup = cacheFactory.getCache("slack-event-dedup", Duration.ofMinutes(10));
        this.activeGroupListeners = cacheFactory.getCache("slack-group-listeners", Duration.ofHours(2));
        this.approvalNotified = cacheFactory.getCache("slack-hitl-approval-notified", Duration.ofHours(24));
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PreDestroy
    void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Handle an incoming Slack event asynchronously. Called from the webhook
     * endpoint after signature verification. Returns immediately — processing
     * happens on a virtual thread.
     *
     * @param eventId
     *            the unique event ID (for dedup)
     * @param event
     *            the parsed event JSON as a Map
     */
    /**
     * @param envelope
     *            who sent the event: the signing secret that verified it plus the
     *            envelope's team/app ids and this app's own bot user id. Every
     *            integration the event reaches is checked against it — see
     *            {@link ChannelTargetRouter#matchesInbound}.
     */
    public void handleEventAsync(String eventId, Map<String, Object> event, SlackEventEnvelope envelope) {
        // De-duplicate: Slack retries events up to 3 times
        if (eventDedup.get(eventId) != null) {
            LOGGER.debugf("Duplicate Slack event %s — skipping", sanitize(eventId));
            return;
        }
        eventDedup.put(eventId, Boolean.TRUE);

        executorService.submit(() -> {
            try {
                handleEvent(event, envelope);
            } catch (Exception e) {
                LOGGER.errorf(e, "Error handling Slack event %s", sanitize(eventId));

                // Best-effort error response to user (never leak internal details).
                // A timeout gets its own notice: the generic line reads as "the agent
                // broke" when what actually happened is that the turn is still running
                // and Slack stopped waiting, which is an operator-tunable limit rather
                // than a fault. Naming the property is the difference between a support
                // ticket and a one-line config change.
                String channelId = (String) event.get("channel");
                String threadTs = getThreadTs(event);
                // Only into a channel the sender's own integration serves: the
                // apology goes out with that channel's bot token.
                if (channelId != null && envelope != null && channelTargetRouter.channelMatchesInbound("slack", channelId,
                        envelope.verifiedSigningSecret(), envelope.inboundIds())) {
                    boolean timedOut = hasCause(e, TimeoutException.class);
                    String notice = timedOut
                            ? "⏳ That took longer than " + slackConfig.getRequestTimeoutSeconds()
                                    + " seconds, so I stopped waiting. The agent may still be working — "
                                    + "ask again in a moment, or raise eddi.slack.request-timeout-seconds."
                            : "⚠️ Sorry, I encountered an error processing your message. Please try again.";
                    try {
                        postMessage(channelId, threadTs, notice, null);
                    } catch (Exception ignored) {
                        // Can't post error — nothing more we can do
                    }
                }
            }
        });
    }

    /**
     * Whether {@code type} appears anywhere in the throwable's cause chain.
     * {@code sendAndWait}'s {@link TimeoutException} is wrapped by the layers
     * between it and the handler, so a top-level {@code instanceof} would miss it.
     */
    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    // Package-private for unit testing: the routing checks are verified directly
    // against this method rather than through the async executor.
    void handleEvent(Map<String, Object> event, SlackEventEnvelope envelope) throws Exception {
        if (envelope == null || envelope.verifiedSigningSecret() == null) {
            // Nothing authenticated this event to any integration — act on none.
            LOGGER.warn("[SLACK] Event without a verified signing secret — ignoring");
            return;
        }
        String eventType = (String) event.get("type");
        String eventSubtype = (String) event.get("subtype");
        String eventChannel = (String) event.get("channel");
        String eventThreadTs = (String) event.get("thread_ts");
        String textPreview = event.get("text") instanceof String t ? (t.length() > 50 ? t.substring(0, 50) + "..." : t) : "null";
        LOGGER.infof("[SLACK] Event received: type=%s, subtype=%s, channel=%s, thread_ts=%s, has_bot_id=%s, text=%s",
                sanitize(eventType), sanitize(eventSubtype), sanitize(eventChannel),
                sanitize(eventThreadTs), event.containsKey("bot_id"),
                sanitize(textPreview));

        // Filter bot's own messages (prevent infinite loop)
        if (event.containsKey("bot_id") || "bot_message".equals(event.get("subtype"))) {
            LOGGER.debugf("[SLACK] Ignoring bot message in channel %s", sanitize(String.valueOf(event.get("channel"))));
            return;
        }

        // Extract once — used for DM detection in both the message filter and
        // the DM fallback resolution (step 4 below)
        String channelType = (String) event.get("channel_type");
        boolean isDirectMessage = "im".equals(channelType);

        // For "message" events (from message.channels/groups/im subscriptions):
        // - DMs (channel_type: "im") → always process (no app_mention in DMs)
        // - Top-level channel messages → handled by app_mention, skip here
        // - Thread replies with @mention → handled by app_mention, skip here
        // - Thread replies without @mention → process here (thread continuity)
        if ("message".equals(eventType)) {
            if (eventThreadTs == null && !isDirectMessage) {
                // Top-level channel message. Only app_mention answers these — unless
                // an observer is configured for the channel, which is the one case
                // where the bot may speak without being addressed. A channel with no
                // observers behaves exactly as it did before observe mode existed.
                if (handleObservedMessage(event, eventChannel, envelope)) {
                    return;
                }
                LOGGER.debugf("[SLACK] Ignoring top-level message event (use @mention)");
                return;
            }
            String text = (String) event.get("text");
            if (text != null && BOT_MENTION_PATTERN.matcher(text).find()) {
                // Thread reply with @mention — app_mention event will handle it
                LOGGER.debugf("[SLACK] Ignoring @mentioned thread reply (handled by app_mention)");
                return;
            }
        }

        String text = (String) event.get("text");
        String slackUserId = (String) event.get("user");
        String channelId = (String) event.get("channel");

        if (text == null || text.isBlank() || slackUserId == null || channelId == null) {
            LOGGER.debugf("Incomplete Slack event — missing text/user/channel");
            return;
        }
        SlackUser user = slackUser(event, envelope);

        // Strip bot mention prefix: "<@U0123BOTID> hello" → "hello"
        text = stripBotMention(text);

        String threadTs = getThreadTs(event);

        if (text.isBlank()) {
            postHelpIfOwned(channelId, threadTs, envelope);
            return;
        }

        // 1. Check thread target lock (existing threads keep their target)
        String parentTs = (String) event.get("thread_ts");
        ResolvedTarget resolved = null;

        if (parentTs != null) {
            resolved = channelTargetRouter.resolveThreadTarget("slack", channelId, parentTs);
            if (resolved != null && isDirectMessage && resolved.integration() == null
                    && resolved.legacySigningSecret() == null) {
                // A DM channel is named by no integration, so the lock alone carries
                // no credentials. Attach those of an integration (or legacy
                // connector) that the sending app's secret authenticates AND that has
                // the locked target among its own targets. Anything looser would let
                // one app's secret continue a thread locked to another app's agent:
                // the inbound check below would then pass by construction.
                var withCredentials = channelTargetRouter.threadCredentialsForDm("slack", resolved.target(),
                        envelope.verifiedSigningSecret(), envelope.inboundIds());
                if (withCredentials == null) {
                    LOGGER.warnf("[SLACK] DM thread %s is locked to a target the sending app does not serve — ignoring",
                            sanitize(parentTs));
                    return;
                }
                resolved = withCredentials;
            }
        }

        // 2. Check group follow-up (thread root was a group discussion)
        if (resolved == null && parentTs != null
                && tryHandleAgentFollowUp(parentTs, channelId, user, text, threadTs, envelope)) {
            return;
        }

        // 3. Fresh resolution via ChannelTargetRouter
        if (resolved == null) {
            resolved = channelTargetRouter.resolveTarget("slack", channelId, text);
        }

        // 4. DM fallback: DMs use dynamic D-prefixed ids no integration names, so
        // fall back to the default target of the integration the SENDING app's
        // credentials belong to (H4d — never simply "the first one").
        if (resolved == null && isDirectMessage) {
            resolved = channelTargetRouter.resolveDefaultForDm("slack", text,
                    envelope.verifiedSigningSecret(), envelope.inboundIds());
        }

        if (resolved == null) {
            postHelpIfOwned(channelId, threadTs, envelope);
            return;
        }

        // 5. The route must belong to the app that sent the event (H4a). The body
        // names the channel, and the signature only proves that SOME configured app
        // signed it — so without this, anyone holding one integration's signing
        // secret could run turns, as any Slack user, against another integration's
        // agents, answered with that integration's bot token.
        if (!ChannelTargetRouter.matchesInbound(resolved, envelope.verifiedSigningSecret(), envelope.inboundIds())) {
            LOGGER.warnf("[SLACK] Event for channel %s was signed by a different integration's app — ignoring",
                    sanitize(channelId));
            return;
        }

        // Lock target for this thread
        if (threadTs != null) {
            channelTargetRouter.lockThreadTarget("slack", channelId, threadTs, resolved.target());
        }

        // Resolve bot token once — passed explicitly to all post methods
        String botToken = resolved.botToken();
        switch (resolved.target().getType()) {
            case AGENT -> handleAgentConversation(resolved, channelId, user, threadTs, text, botToken);
            case GROUP -> handleGroupDiscussion(resolved, channelId, user, threadTs, text, botToken);
            default -> LOGGER.warnf("Unsupported target type: %s", resolved.target().getType());
        }
    }

    /**
     * The Slack user behind an event, as EDDI identifies them.
     *
     * @param eddiUserId
     *            the userId conversations, memories and group discussions are keyed
     *            by
     * @param legacyUserId
     *            the bare Slack id this person's data was stored under before
     *            namespacing — set only when that bare id can only have meant this
     *            person (see {@link #slackUser}), else {@code null}. Used to find a
     *            thread's existing conversation and to carry long-term memories
     *            over on first contact.
     * @param slackUserId
     *            the raw Slack user id, as sent
     * @param slackTeamId
     *            the user's Slack team as sent, or {@code null}
     */
    record SlackUser(String eddiUserId, String legacyUserId, String slackUserId, String slackTeamId) {
    }

    /**
     * Resolve {@link SlackUser} for an event.
     * <p>
     * Slack user ids are unique within a workspace, not across workspaces. With
     * {@code eddi.slack.namespace-user-ids=true} a user is keyed as
     * {@code slack:<teamId>:<userId>}, the team being the event's {@code user_team}
     * (Slack Connect users from another org), else {@code team}, else the
     * envelope's {@code team_id}. These fields are signed by the sending app, not
     * verified by EDDI, so namespacing prevents ACCIDENTAL collisions between
     * workspaces — it is not an authentication of the user's workspace.
     * <p>
     * The bare id is aliased (legacyUserId) only when the user's team is the
     * deployment's legacy team — {@code eddi.slack.legacy-team-id}, or the one team
     * every routed integration pins while no legacy connector is routed
     * ({@link ChannelTargetRouter#commonPinnedTeamId}). In any other deployment a
     * bare id may already hold two people's data, and copying it to either would
     * leak it.
     */
    SlackUser slackUser(Map<String, Object> event, SlackEventEnvelope envelope) {
        String rawUserId = (String) event.get("user");
        String team = firstNonBlank(stringValue(event.get("user_team")),
                firstNonBlank(stringValue(event.get("team")), envelope != null ? envelope.teamId() : null));
        if (!slackConfig.isNamespaceUserIds() || team == null || team.isBlank()) {
            return new SlackUser(rawUserId, null, rawUserId, team);
        }
        String legacyTeam = slackConfig.getLegacyTeamId() != null
                ? slackConfig.getLegacyTeamId()
                : channelTargetRouter.commonPinnedTeamId("slack");
        String legacyUserId = team.equals(legacyTeam) ? rawUserId : null;
        return new SlackUser("slack:" + team + ":" + rawUserId, legacyUserId, rawUserId, team);
    }

    private static String stringValue(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * Post the help message only into a channel served by the integration whose app
     * sent the event — the reply goes out with that channel's bot token, and a
     * foreign app must not be able to make this bot speak in it.
     */
    private void postHelpIfOwned(String channelId, String threadTs, SlackEventEnvelope envelope) {
        if (channelTargetRouter.channelMatchesInbound("slack", channelId, envelope.verifiedSigningSecret(),
                envelope.inboundIds())) {
            postHelp(channelId, threadTs, null);
        }
    }

    /**
     * Give a passive observer the chance to answer a message it was not addressed
     * in.
     *
     * Ordinary routing never reaches an unmentioned top-level channel message. An
     * observer does, so what "the user @mentioned the bot" would normally imply has
     * to be checked explicitly here. The bot's own messages are filtered for every
     * event before this point, which is what stops two observers in one channel
     * answering each other forever.
     *
     * @return {@code true} when an observer took the message, so the caller stops
     */
    private boolean handleObservedMessage(Map<String, Object> event, String channelId,
                                          SlackEventEnvelope envelope) {
        try {
            return observeMessage(event, channelId, envelope);
        } catch (RuntimeException e) {
            // Selection runs synchronously inside handleEvent, whose catch posts
            // a user-visible apology into the channel. Nobody addressed the bot
            // here, so that apology would be the bot speaking uninvited about its
            // own internals. Fall through to the ordinary "ignore" instead.
            LOGGER.errorf(e, "[OBSERVE] Selection failed in channel %s", sanitize(channelId));
            return false;
        }
    }

    /**
     * The Slack message subtypes an observer may act on.
     * <p>
     * An absent subtype is an ordinary message and {@code file_share} is how a MIME
     * trigger is meant to fire. Everything else carried by this event is the
     * channel describing itself — joins, leaves, topic and name changes, pins --
     * and an observer watching all traffic would answer "@someone has joined the
     * channel" with an LLM turn, a thread, and a reply off its daily allowance. The
     * bot-message filter upstream does not cover these: they carry a human
     * {@code user} and no {@code bot_id}.
     */
    private static final Set<String> OBSERVABLE_SUBTYPES = Set.of("file_share");

    /** @see #OBSERVABLE_SUBTYPES */
    static boolean isObservableSubtype(String subtype) {
        return subtype == null || OBSERVABLE_SUBTYPES.contains(subtype);
    }

    private boolean observeMessage(Map<String, Object> event, String channelId,
                                   SlackEventEnvelope envelope) {
        if (channelId == null) {
            return false;
        }
        String botUserId = envelope.botUserId();
        String subtype = (String) event.get("subtype");
        if (!isObservableSubtype(subtype)) {
            LOGGER.debugf("[OBSERVE] Ignoring subtype %s in channel %s",
                    sanitize(subtype), sanitize(channelId));
            return false;
        }
        List<ChannelTarget> candidates = channelTargetRouter.observeCandidates("slack", channelId);
        if (candidates.isEmpty()) {
            return false;
        }
        // Same rule as an addressed message (H4a): only the app whose integration
        // serves this channel may make its observers speak.
        if (!channelTargetRouter.channelMatchesInbound("slack", channelId, envelope.verifiedSigningSecret(),
                envelope.inboundIds())) {
            LOGGER.warnf("[OBSERVE] Event for channel %s was signed by a different integration's app — ignoring",
                    sanitize(channelId));
            return false;
        }

        String text = (String) event.get("text");
        if (event.get("user") == null) {
            return false;
        }
        SlackUser user = slackUser(event, envelope);
        // Slack delivers a channel mention TWICE: once as `message` and once as
        // `app_mention`. `app_mention` is the one that routes, so observing the
        // `message` copy would answer the same sentence a second time, possibly
        // from a different target.
        if (mentionsThisBot(text, botUserId)) {
            LOGGER.debugf("[OBSERVE] Skipping a mention in channel %s — app_mention answers it",
                    sanitize(channelId));
            return false;
        }
        // Files are how an observer watching for, say, PDFs is meant to fire, so a
        // message that is only an upload still counts even with empty text.
        List<String> mimeTypes = attachedMimeTypes(event);
        if ((text == null || text.isBlank()) && mimeTypes.isEmpty()) {
            return false;
        }

        // Before `select`, not after: selection now books the reply in the same
        // compare-and-set that grants it, so discovering the channel has no
        // credentials afterwards would burn a reply — and the cooldown — on a
        // message the bot was never able to answer.
        String botToken = channelTargetRouter.getBotToken("slack", channelId);
        if (botToken == null || botToken.isBlank()) {
            LOGGER.warnf("[OBSERVE] No bot token for channel %s — observers cannot reply",
                    sanitize(channelId));
            return false;
        }

        var match = observeGate.select("slack", channelId, candidates, text, mimeTypes);
        if (match.isEmpty()) {
            return false;
        }
        ChannelTarget target = match.get().target();

        // An observer answers in a thread under the message it reacted to. Replying
        // at top level would read as the bot joining the conversation, and every
        // reply would be a new root nobody can follow.
        String threadTs = firstNonBlank((String) event.get("thread_ts"), (String) event.get("ts"));
        var integration = channelTargetRouter.integrationFor("slack", channelId);
        ResolvedTarget resolved = new ResolvedTarget(target, text, integration, null, null);
        String message = text != null ? text : "";

        // No `recordResponse` here: `select` already booked the reply in the
        // same compare-and-set that granted it, which is what stops two events
        // arriving together from both being told there is room for one more.
        // The allowance is therefore spent at the moment the observer commits —
        // a turn that fails still used it, so a failing observer cannot retry
        // all day — and the spend is added below, once the figure exists.

        LOGGER.infof("[OBSERVE] Target '%s' answering an unaddressed message in channel %s",
                sanitize(target.getName()), sanitize(channelId));

        executorService.submit(() -> {
            String conversationId = null;
            OptionalDouble costBefore = OptionalDouble.empty();
            try {
                conversationId = observedConversationId(resolved, channelId, user, threadTs);
                costBefore = conversationCost(conversationId);
                sendAndDeliver(resolved, conversationId, target.getTargetId(), channelId, threadTs,
                        message, botToken);
                // Locked only now, once the observer has actually spoken. The
                // lock exists so a human replying under the observer's answer
                // reaches the observer rather than the channel's DEFAULT target.
                // Taken before the turn, a failure that posted nothing still left
                // the thread bound: for the 24h the lock lives, a colleague
                // replying there with an explicit `architect:` trigger would have
                // been silently routed to an observer that never said anything.
                if (threadTs != null) {
                    channelTargetRouter.lockThreadTarget("slack", channelId, threadTs, target);
                }
            } catch (Exception e) {
                LOGGER.errorf(e, "[OBSERVE] Target '%s' failed to answer in channel %s",
                        sanitize(target.getName()), sanitize(channelId));
            } finally {
                // Both reads or neither. A baseline that failed and a total that
                // did not would make the delta the conversation's ENTIRE history
                // of tool spend, charged to this one turn — which would retire
                // the observer's daily budget on its first reply.
                OptionalDouble costAfter = conversationCost(conversationId);
                if (costBefore.isPresent() && costAfter.isPresent()) {
                    double spent = costAfter.getAsDouble() - costBefore.getAsDouble();
                    if (spent > 0) {
                        observeGate.addCost("slack", channelId, target, spent);
                    }
                } else if (conversationId != null) {
                    LOGGER.debugf("[OBSERVE] Cost for conversation %s is unknown — not charged",
                            sanitize(conversationId));
                }
            }
        });
        return true;
    }

    /**
     * Whether this message is addressed to THIS bot, anywhere in its text.
     *
     * With the envelope's bot user id this is exact: `<@U123>` matched anywhere, so
     * a mention after other text ("thanks @alice — @eddi can you look?") is
     * recognised, while a mention of somebody else is not.
     *
     * Without it — an envelope shape that carries no bot authorization — it falls
     * back to {@link #ANY_MENTION_PATTERN}: any mention of anyone, anywhere, is
     * treated as possibly this bot's. That errs towards silence in one direction
     * only. An observer stays quiet on "@alice can you check this?", which is a
     * miss; the alternative is answering a sentence `app_mention` is answering too,
     * which is the bot replying twice. The anchored {@link #BOT_MENTION_PATTERN}
     * used to serve here and could do neither: being anchored it missed a trailing
     * mention entirely, and produced exactly that double reply. It is still the
     * thread-reply branch's test, where `stripBotMention` depends on it being
     * prefix-only.
     */
    static boolean mentionsThisBot(String text, String botUserId) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (botUserId == null || botUserId.isBlank()) {
            return ANY_MENTION_PATTERN.matcher(text).find();
        }
        // Slack writes a mention as `<@U123>` and, where the client had a label
        // to hand, as `<@U123|eddi>`. Matching only the first form let the
        // labelled variant through as "not addressed to us".
        return text.contains("<@" + botUserId + ">") || text.contains("<@" + botUserId + "|");
    }

    /**
     * MIME types of the files on a Slack message.
     *
     * Slack puts them on {@code files[].mimetype}; an entry shaped any other way is
     * skipped rather than guessed at.
     */
    static List<String> attachedMimeTypes(Map<String, Object> event) {
        if (!(event.get("files") instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        for (Object file : list) {
            if (file instanceof Map<?, ?> map && map.get("mimetype") instanceof String mimeType
                    && !mimeType.isBlank()) {
                types.add(mimeType);
            }
        }
        return types;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /**
     * Tool spend accumulated on a conversation so far, or {@code 0.0} when nothing
     * has been tracked for it.
     *
     * Tool spend, not total spend: {@code ToolCostTracker} is the engine's only
     * cost tracker and it accumulates {@code @Tool} executions alone, so an
     * observer that only talks to an LLM reads 0.0 and is bounded by its daily
     * response count instead. Same quantity, and the same caveat, as the cost a
     * scheduled fire logs. Never throws — an observer must not fail because its
     * accounting did.
     */
    private OptionalDouble conversationCost(String conversationId) {
        if (conversationId == null) {
            return OptionalDouble.empty();
        }
        try {
            var metrics = toolCostTracker.getConversationCosts(conversationId);
            // No metrics yet is a real zero — a conversation that has called no
            // priced tool. Only a throw is "unknown".
            return OptionalDouble.of(metrics != null ? metrics.getTotalCost() : 0.0);
        } catch (RuntimeException e) {
            LOGGER.debugf(e, "[OBSERVE] Could not read tool cost for conversation %s", conversationId);
            return OptionalDouble.empty();
        }
    }

    /**
     * Handle a standard 1:1 agent conversation routed via ChannelTargetRouter.
     */
    private void handleAgentConversation(ResolvedTarget resolved, String channelId,
                                         SlackUser user, String threadTs, String originalText,
                                         String botToken)
            throws Exception {
        String agentId = resolved.target().getTargetId();
        String threadKey = threadTs != null ? threadTs : "main";

        // Compose a stable intent key for conversation tracking.
        // Uses channelId + targetId (agentId/groupId) — NOT mutable display names
        // like integration name or target name, which would break
        // IUserConversationStore lookups on rename.
        String intent = "channel:slack:" + channelId + ":" + agentId + ":" + threadKey;

        // Use strippedMessage (trigger keyword removed) or fall back to original text
        // (thread replies from resolveThreadTarget have strippedMessage=null)
        String message = resolved.strippedMessage() != null ? resolved.strippedMessage() : originalText;

        String conversationId = getOrCreateConversation(agentId, user, intent, integrationId(resolved));
        sendAndDeliver(resolved, conversationId, agentId, channelId, threadTs, message, botToken);
    }

    /**
     * The conversation an observed turn will run on — the same intent key an
     * addressed turn would use, so an observer and a mention in the same channel
     * and thread share one conversation rather than talking past each other.
     */
    private String observedConversationId(ResolvedTarget resolved, String channelId, SlackUser user,
                                          String threadTs)
            throws Exception {
        String agentId = resolved.target().getTargetId();
        String threadKey = threadTs != null ? threadTs : "main";
        String intent = "channel:slack:" + channelId + ":" + agentId + ":" + threadKey;
        return getOrCreateConversation(agentId, user, intent, integrationId(resolved));
    }

    private static String integrationId(ResolvedTarget resolved) {
        return resolved != null && resolved.integration() != null ? resolved.integration().getResourceId() : null;
    }

    /**
     * Send a message to a conversation, then deliver the outcome to Slack —
     * handling the HITL pause cases:
     * <ul>
     * <li>If {@code say} throws {@link ConversationAwaitingApprovalException} (a
     * follow-up while already paused), post the "still awaiting" notice.</li>
     * <li>If the returned snapshot is {@code AWAITING_HUMAN} (this turn paused),
     * post any output-so-far plus a pause notice, and — when configured — an
     * approval notification with Approve/Reject buttons.</li>
     * <li>Otherwise post the response normally.</li>
     * </ul>
     */
    private void sendAndDeliver(ResolvedTarget resolved, String conversationId, String agentId,
                                String channelId, String threadTs, String message, String botToken)
            throws Exception {
        SimpleConversationMemorySnapshot snapshot;
        try {
            snapshot = sendAndWait(conversationId, message);
        } catch (IConversationService.ConversationAwaitingApprovalException e) {
            // The conversation is already paused — input was NOT consumed. Never
            // surface the generic error message.
            postMessage(channelId, threadTs, SlackHitlSupport.STILL_AWAITING_NOTICE, botToken);
            // H8: a CONVERSATION_START (init-turn) pause happens inside
            // getOrCreateConversation → startConversation, so no approval card was
            // ever posted for it. If we have not notified for this pause yet, post
            // the approval card now (idempotent via approvalNotified).
            notifyApprovers(resolved, conversationId, agentId, loadHitlBookmark(conversationId));
            return;
        }

        // A queued-say skip (pause/busy committed by a prior turn) surfaces here as
        // the STILL_AWAITING marker — the input was dropped, not this turn pausing.
        // Post the "still awaiting" notice and do NOT re-notify approvers (no second
        // approval card for the same pending pause).
        if (snapshot == SKIPPED_STILL_AWAITING) {
            postMessage(channelId, threadTs, SlackHitlSupport.STILL_AWAITING_NOTICE, botToken);
            return;
        }
        // A skip for a non-active conversation (busy/interrupted/ended) — the
        // message was dropped; tell the user rather than replaying a stale turn.
        if (snapshot == SKIPPED_NOT_ACTIVE) {
            postMessage(channelId, threadTs, SlackHitlSupport.CONVERSATION_NOT_ACTIVE_NOTICE, botToken);
            return;
        }

        boolean paused = snapshot != null
                && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;

        // Post output-so-far (if any). extractSlackResponseText never returns null;
        // when paused with no output it returns a placeholder we suppress.
        String response = SlackHitlSupport.extractSlackResponseText(snapshot);
        if (paused) {
            // Load the HITL bookmark ONCE (with a brief retry until the pause is
            // persisted) and reuse it for both the in-thread notice and the approval
            // card — the say callback can fire before the bookmark is stored (H10),
            // so a naive re-read returns the previous turn's null pause fields.
            var bookmark = loadHitlBookmark(conversationId);
            if (response != null && !response.startsWith("_")) {
                postMessageChunked(channelId, threadTs, response, botToken);
            }
            postMessage(channelId, threadTs, buildPauseNotice(bookmark), botToken);
            notifyApprovers(resolved, conversationId, agentId, bookmark);
        } else {
            postMessageChunked(channelId, threadTs, response, botToken);
        }
    }

    /**
     * Load the HITL bookmark for a just-paused conversation, retrying briefly until
     * the stored snapshot reports {@code AWAITING_HUMAN}. The say callback
     * completes from the Conversation {@code finally} block BEFORE
     * ConversationService persists the bookmark, so an immediate re-read can return
     * the previous turn (pauseReason/timeout come back null, and the configurable
     * pause reason would be invisible on Slack). Best-effort: returns whatever the
     * last read produced — possibly {@code null} — so the notices degrade
     * gracefully.
     */
    private ConversationMemorySnapshot loadHitlBookmark(String conversationId) {
        ConversationMemorySnapshot last = null;
        for (int attempt = 0; attempt < HITL_BOOKMARK_READ_ATTEMPTS; attempt++) {
            try {
                last = conversationService.getConversationMemorySnapshot(conversationId);
                if (last != null && last.getConversationState() == ConversationState.AWAITING_HUMAN) {
                    return last;
                }
            } catch (Exception e) {
                LOGGER.debugf("Could not load HITL bookmark for %s (attempt %d): %s",
                        sanitize(conversationId), attempt + 1, e.getMessage());
            }
            if (attempt < HITL_BOOKMARK_READ_ATTEMPTS - 1) {
                try {
                    Thread.sleep(HITL_BOOKMARK_READ_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return last;
    }

    /**
     * Build the in-thread pause notice, appending the pause reason from the HITL
     * bookmark when available.
     */
    private String buildPauseNotice(ConversationMemorySnapshot bookmark) {
        String reason = bookmark != null ? bookmark.getHitlPauseReason() : null;
        if (reason != null && !reason.isBlank()) {
            return SlackHitlSupport.PAUSE_NOTICE + "\n> " + reason;
        }
        return SlackHitlSupport.PAUSE_NOTICE;
    }

    /**
     * Post an interactive approval notification to the configured approval channel
     * for a paused conversation. No-op when no {@code hitlApprovalChannel} is
     * configured. Fail-closed: buttons are only rendered when
     * {@code hitlApproverUserIds} is configured (otherwise notification-only).
     * <p>
     * Data minimization: only the pause reason (which the agent designer controls)
     * is included — never the user's raw message.
     */
    // Package-private for unit testing (F12): the idempotency keying and
    // failed-delivery marker-clear are verified directly against this method.
    void notifyApprovers(ResolvedTarget resolved, String conversationId,
                         String agentId, ConversationMemorySnapshot bookmark) {
        var integration = resolved.integration();
        if (integration == null || integration.getPlatformConfig() == null) {
            return; // legacy connectors have no HITL config
        }
        Map<String, String> platformConfig = integration.getPlatformConfig();
        String approvalChannel = platformConfig.get(SlackHitlSupport.CFG_HITL_APPROVAL_CHANNEL);
        if (approvalChannel == null || approvalChannel.isBlank()) {
            return; // notification-only disabled
        }

        // Idempotency (H8/F12): post exactly one approval card per pending pause.
        // The marker is keyed by PAUSE IDENTITY (conversationId + the bookmark's
        // hitlPausedAt), NOT by conversationId alone — so a second distinct pause in
        // the same conversation (pause → resume → pause) is NOT suppressed. When the
        // best-effort bookmark load returns a non-AWAITING snapshot (hitlPausedAt
        // null), fall back to conversationId-only keying rather than blocking the
        // card. A normal-turn pause and an init-turn pause detected on the next say
        // both route here — the first wins the slot; re-message-while-paused is a
        // no-op. Marker is cleared below if delivery fails, so a stale eviction only
        // risks one duplicate card, never a missed one.
        String notifyKey = conversationId + '|'
                + (bookmark != null && bookmark.getHitlPausedAt() != null
                        ? bookmark.getHitlPausedAt().toEpochMilli()
                        : "");
        if (approvalNotified.putIfAbsent(notifyKey, Boolean.TRUE) != null) {
            return;
        }

        String approverIds = platformConfig.get(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS);
        boolean hasApprovers = !SlackHitlSupport.parseApproverUserIds(approverIds).isEmpty();
        // H4b: the buttons carry the id of THIS pause, and the interactivity handler
        // refuses a click whose pause is no longer current — so a card left in the
        // channel cannot approve a later, different request. When the bookmark read
        // could not identify the pause, a button could only mean "whatever is paused
        // now"; none is rendered and the card says to decide elsewhere.
        String pauseId = bookmark != null && bookmark.getConversationState() == ConversationState.AWAITING_HUMAN
                ? HitlDecision.pauseIdOf(bookmark.getHitlPausedAt())
                : null;
        // Buttons only where a click would be accepted: a conversation this
        // integration did not start (one started before the binding existed, say)
        // is refused by the interactivity handler, so live buttons would only end
        // in "not authorized" with no hint of where to decide instead.
        boolean boundHere = SlackInteractivityHandler.conversationBelongsTo(integration, bookmark);
        boolean includeButtons = hasApprovers && pauseId != null && boundHere;
        String noButtonsNotice = !hasApprovers
                ? SlackHitlSupport.NO_APPROVERS_NOTICE
                : pauseId == null ? SlackHitlSupport.PAUSE_UNIDENTIFIED_NOTICE : SlackHitlSupport.NOT_BOUND_NOTICE;

        String pauseReason = bookmark != null ? bookmark.getHitlPauseReason() : null;
        String timeoutInfo = bookmark != null
                ? formatTimeoutInfo(
                        bookmark.getHitlTimeoutPolicy() != null ? bookmark.getHitlTimeoutPolicy().name() : null,
                        bookmark.getHitlApprovalTimeout())
                : null;

        // The button value carries the owning integration name so the decision is
        // bound to THIS integration at the interactivity endpoint (IDOR-safe).
        String actionValue = SlackHitlSupport.buildActionValue(integration.getName(), conversationId, pauseId);
        String pauseType = bookmark != null ? bookmark.getHitlPauseType() : null;
        var pendingToolCalls = bookmark != null ? bookmark.getHitlPendingToolCalls() : null;
        var blocks = SlackHitlSupport.buildApprovalBlocks(
                "⏸️ Conversation awaiting approval", "Conversation", conversationId,
                agentId, pauseReason, timeoutInfo, actionValue, includeButtons,
                pauseType, pendingToolCalls,
                noButtonsNotice);
        String fallback = "Conversation " + conversationId + " is awaiting human approval.";

        // H6: never send "Bearer null". If neither the resolved integration token
        // nor the approval-channel lookup yields a non-blank token, skip the call
        // and log an explicit non-delivery error (a bare invalid_auth warn hides it).
        String botToken = resolved.botToken();
        if (botToken == null || botToken.isBlank()) {
            botToken = channelTargetRouter.getBotToken("slack", approvalChannel);
        }
        if (botToken == null || botToken.isBlank()) {
            LOGGER.errorf("No bot token — HITL approval notification NOT delivered for %s",
                    sanitize(conversationId));
            return;
        }
        String auth = "Bearer " + botToken;
        try {
            slackApi.postBlocksMessage(auth, approvalChannel, null, blocks, fallback);
        } catch (SlackDeliveryException e) {
            // F12: delivery failed — clear the marker so a later retry can re-attempt
            // instead of being suppressed for the full 24h TTL.
            approvalNotified.remove(notifyKey);
            LOGGER.warnf("Failed to post HITL approval notification for %s: %s",
                    sanitize(conversationId), e.getMessage());
        }
    }

    /**
     * Format the HITL timeout policy + duration into a readable one-liner for the
     * approval notification.
     */
    static String formatTimeoutInfo(String policy, String approvalTimeout) {
        if (policy == null || policy.isBlank()) {
            return null;
        }
        if (approvalTimeout != null && !approvalTimeout.isBlank()) {
            return policy + " (" + approvalTimeout + ")";
        }
        return policy;
    }

    // ─── Group Discussion ───

    /**
     * Handle a group discussion trigger routed via ChannelTargetRouter.
     */
    private void handleGroupDiscussion(ResolvedTarget resolved, String channelId,
                                       SlackUser user, String threadTs, String originalText,
                                       String botToken) {
        String groupId = resolved.target().getTargetId();

        if (botToken == null || botToken.isEmpty()) {
            LOGGER.errorf("No bot token configured for Slack channel %s — cannot run group discussion.", sanitize(channelId));
            return;
        }

        String token = "Bearer " + botToken;

        // HITL approval config (optional) — flows into the listener so a group
        // pause can notify approvers with buttons.
        String hitlApprovalChannel = null;
        String hitlApproverUserIds = null;
        String integrationName = null;
        var integration = resolved.integration();
        if (integration != null && integration.getPlatformConfig() != null) {
            hitlApprovalChannel = integration.getPlatformConfig().get(SlackHitlSupport.CFG_HITL_APPROVAL_CHANNEL);
            hitlApproverUserIds = integration.getPlatformConfig().get(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS);
            integrationName = integration.getName();
        }

        // Create the listener that streams discussion into Slack. The integration
        // name is carried into the approval button value so a group HITL decision
        // binds to THIS integration at the interactivity endpoint (IDOR-safe).
        var listener = new SlackGroupDiscussionListener(slackApi, token, channelId, threadTs,
                hitlApprovalChannel, hitlApproverUserIds, integrationName);

        String question = resolved.strippedMessage() != null ? resolved.strippedMessage() : originalText;
        try {
            LOGGER.infof("Starting group discussion in channel %s, group %s, question: %s",
                    sanitize(channelId), sanitize(groupId), sanitize(question.substring(0, Math.min(80, question.length()))));

            var gc = groupConversationService.startAndDiscussAsync(groupId, question, user.eddiUserId(), listener);
            recordGroupOrigin(gc != null ? gc.getId() : null, groupId, integrationId(resolved));

            // Only register for follow-up routing in expanded mode (compact has no
            // channel-level messages)
            if (listener.isExpandedMode()) {
                executorService.submit(() -> registerAgentThreadMappings(listener, resolved, channelId));
            }

        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to start group discussion: %s", e.getMessage());
            postMessage(channelId, threadTs,
                    "⚠️ Sorry, I couldn't start the group discussion. Please try again.",
                    botToken);
        }
    }

    /**
     * Record which integration started a group discussion, so that a Slack decision
     * on it is accepted only from that integration (H4c). Stored as a mapping in
     * {@link IUserConversationStore} — a durable, cross-pod record that needs no
     * change to the discussion document. Best-effort: without it the discussion's
     * pauses simply cannot be decided from Slack (fail closed), and remain
     * decidable from the Manager or the API.
     */
    private void recordGroupOrigin(String groupConversationId, String groupId, String integrationId) {
        if (groupConversationId == null || integrationId == null || integrationId.isBlank()) {
            return;
        }
        try {
            userConversationStore.createUserConversation(new UserConversation(GROUP_ORIGIN_INTENT_PREFIX + groupConversationId,
                    GROUP_ORIGIN_USER_PREFIX + integrationId, Deployment.Environment.production, groupId, groupConversationId));
        } catch (Exception e) {
            LOGGER.warnf("Could not record the integration that started group discussion %s: %s",
                    sanitize(groupConversationId), e.getMessage());
        }
    }

    /**
     * Wait for the group discussion to complete, then register all agent message ts
     * mappings for follow-up routing.
     */
    // Package-private for unit testing: the follow-up binding is verified directly.
    void registerAgentThreadMappings(SlackGroupDiscussionListener listener, ResolvedTarget resolved,
                                     String channelId) {
        // Wait for the group discussion to complete via the listener's latch
        int groupTimeout = slackConfig.getGroupCompletionTimeoutSeconds();
        boolean completed = listener.awaitCompletion(groupTimeout, TimeUnit.SECONDS);
        if (!completed) {
            // Name the limit: without it the operator cannot tell a hung discussion from
            // one that simply needed longer than
            // eddi.slack.group-completion-timeout-seconds.
            LOGGER.warnf("Group discussion did not complete within %ds (eddi.slack.group-completion-timeout-seconds) "
                    + "— follow-up routing may be incomplete", groupTimeout);
        }

        // Register all agent message ts → listener for follow-up detection, together
        // with the route that started the discussion: a follow-up is accepted only in
        // the same channel and only from the app that route belongs to.
        var followUp = new GroupFollowUp(listener, resolved, channelId);
        for (String ts : listener.getAgentMessageTsMap().values()) {
            activeGroupListeners.put(ts, followUp);
            LOGGER.debugf("Registered agent thread ts=%s for follow-up routing", ts);
        }
    }

    /**
     * A group discussion's agent thread, open for follow-ups.
     *
     * @param resolved
     *            the route that started the discussion — its integration (or legacy
     *            credentials) decide which app may continue the thread, and which
     *            bot token answers
     * @param channelId
     *            the channel the discussion ran in
     */
    record GroupFollowUp(SlackGroupDiscussionListener listener, ResolvedTarget resolved, String channelId) {
    }

    // ─── Agent Thread Follow-up ───

    /**
     * Check if the user's reply is in an agent's thread from a group discussion. If
     * so, route to that agent with group context.
     *
     * @return true if this was handled as a follow-up, false otherwise
     */
    private boolean tryHandleAgentFollowUp(String parentTs, String channelId,
                                           SlackUser user, String text, String threadTs,
                                           SlackEventEnvelope envelope)
            throws Exception {
        GroupFollowUp followUp = activeGroupListeners.get(parentTs);
        if (followUp == null) {
            return false; // Not a thread from a group discussion
        }
        // The thread key is a bare Slack timestamp, so the event must also be in the
        // discussion's channel and come from the app whose route started it (H4a).
        if (!channelId.equals(followUp.channelId())
                || !ChannelTargetRouter.matchesInbound(followUp.resolved(), envelope.verifiedSigningSecret(),
                        envelope.inboundIds())) {
            LOGGER.warnf("[SLACK] Follow-up for thread %s does not match the discussion's channel or app — ignoring",
                    sanitize(parentTs));
            return true;
        }
        SlackGroupDiscussionListener listener = followUp.listener();
        String botToken = followUp.resolved().botToken();
        String userId = user.eddiUserId();

        String agentId = listener.getAgentIdForMessageTs(parentTs);
        if (agentId == null) {
            return false;
        }

        SlackGroupDiscussionListener.AgentContext ctx = listener.getAgentContext(agentId);
        if (ctx == null) {
            return false;
        }

        LOGGER.infof("Follow-up in agent %s thread from user %s: %s",
                sanitize(ctx.displayName()), sanitize(userId), sanitize(text.substring(0, Math.min(60, text.length()))));

        // Build context-enriched input
        String enrichedInput = buildFollowUpInput(ctx, text);

        // Route to the specific agent from the group discussion
        String intent = "channel:followup:" + channelId + ":" + parentTs;
        String conversationId = getOrCreateConversation(agentId, user, intent, integrationId(followUp.resolved()));

        // Follow-ups post no approver notification — but the pause notice and "still
        // awaiting" handling still apply so the user is never left with a generic
        // error.
        try {
            SimpleConversationMemorySnapshot snapshot = sendAndWait(conversationId, enrichedInput);
            if (snapshot == SKIPPED_STILL_AWAITING) {
                postMessage(channelId, threadTs, SlackHitlSupport.STILL_AWAITING_NOTICE, botToken);
                return true;
            }
            if (snapshot == SKIPPED_NOT_ACTIVE) {
                postMessage(channelId, threadTs, SlackHitlSupport.CONVERSATION_NOT_ACTIVE_NOTICE, botToken);
                return true;
            }
            boolean paused = snapshot != null
                    && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;
            String response = SlackHitlSupport.extractSlackResponseText(snapshot);
            if (paused) {
                if (response != null && !response.startsWith("_")) {
                    postMessageChunked(channelId, threadTs, response, botToken);
                }
                postMessage(channelId, threadTs, buildPauseNotice(loadHitlBookmark(conversationId)), botToken);
            } else {
                postMessageChunked(channelId, threadTs, response, botToken);
            }
        } catch (IConversationService.ConversationAwaitingApprovalException e) {
            postMessage(channelId, threadTs, SlackHitlSupport.STILL_AWAITING_NOTICE, botToken);
        }

        return true;
    }

    /**
     * Build a context-enriched input for an agent follow-up. Prepends the group
     * discussion context so the agent understands what was discussed.
     */
    private String buildFollowUpInput(SlackGroupDiscussionListener.AgentContext ctx, String userMessage) {
        var sb = new StringBuilder();
        sb.append("[Context: You previously participated in a group discussion]\n");
        sb.append("Discussion question: \"").append(ctx.groupQuestion()).append("\"\n");
        sb.append("Your contribution: \"").append(truncate(ctx.contribution(), 500)).append("\"\n");
        if (ctx.feedbackReceived() != null && !ctx.feedbackReceived().isEmpty()) {
            sb.append("Peer feedback you received:\n").append(truncate(ctx.feedbackReceived(), 500)).append("\n");
        }
        sb.append("---\n");
        sb.append("User follow-up question: ").append(userMessage);
        return sb.toString();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null)
            return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /**
     * Map a Slack thread to an EDDI conversation. Uses
     * {@link IUserConversationStore} with intent key composed from integration +
     * target + thread.
     */
    private String getOrCreateConversation(String agentId, SlackUser user, String intent, String integrationId)
            throws Exception {
        String slackUserId = user.eddiUserId();
        // Try existing — readUserConversation returns null when not found,
        // throws ResourceStoreException only on real DB errors (which should propagate)
        UserConversation existing = userConversationStore.readUserConversation(intent, slackUserId);
        if (existing != null) {
            return existing.getConversationId();
        }
        // A thread that was already running before user ids were namespaced is
        // mapped under the bare Slack id. Keep using that conversation rather than
        // forking the thread; every NEW conversation is namespaced. Only where the
        // bare id can only have meant this person (legacyUserId is set).
        if (user.legacyUserId() != null) {
            UserConversation legacy = userConversationStore.readUserConversation(intent, user.legacyUserId());
            if (legacy != null) {
                return legacy.getConversationId();
            }
            carryOverLegacyMemories(user);
        }

        // Create new conversation. channelIntegrationId records which integration
        // started it — by resource id, which cannot be renamed onto or reused by
        // another integration: a Slack approval decision is accepted only from
        // THAT integration (SlackInteractivityHandler), so one integration's
        // approvers cannot decide another's conversations, or ones Slack never
        // started. The raw Slack ids ride along so a template can use them
        // whatever userId scheme is in force.
        Map<String, Context> context = new HashMap<>();
        context.put(CONTEXT_CHANNEL_INTENT, new Context(Context.ContextType.string, intent));
        if (integrationId != null && !integrationId.isBlank()) {
            context.put(CONTEXT_CHANNEL_INTEGRATION_ID, new Context(Context.ContextType.string, integrationId));
        }
        if (user.slackUserId() != null) {
            context.put(CONTEXT_SLACK_USER_ID, new Context(Context.ContextType.string, user.slackUserId()));
        }
        if (user.slackTeamId() != null) {
            context.put(CONTEXT_SLACK_TEAM_ID, new Context(Context.ContextType.string, user.slackTeamId()));
        }
        var result = conversationService.startConversation(
                Deployment.Environment.production, agentId, slackUserId, context);

        // Store mapping
        var mapping = new UserConversation(intent, slackUserId,
                Deployment.Environment.production, agentId, result.conversationId());
        try {
            userConversationStore.createUserConversation(mapping);
        } catch (IResourceStore.ResourceAlreadyExistsException e) {
            // Someone else created the mapping between the read above and this
            // write. Returning our own id would leave the two callers talking to
            // two different conversations about the same thread — previously
            // rare, and reachable now that an observer runs asynchronously
            // alongside the mention and thread-reply paths. The stored mapping
            // is the winner; ours is an orphan.
            UserConversation winner = userConversationStore.readUserConversation(intent, slackUserId);
            if (winner != null && winner.getConversationId() != null) {
                LOGGER.debugf("Lost the create race for %s/%s — using the stored conversation",
                        sanitize(intent), sanitize(slackUserId));
                endOrphanedConversation(result.conversationId());
                return winner.getConversationId();
            }
            // The mapping existed a moment ago and cannot be read now. Ours is
            // the only conversation we can name, so use it rather than fail.
            LOGGER.warnf("Create conflict for %s/%s but no stored mapping could be read",
                    sanitize(intent), sanitize(slackUserId));
        }

        return result.conversationId();
    }

    /**
     * Copy a legacy-team user's long-term memories from their bare Slack id to the
     * namespaced id, the first time the namespaced id is seen.
     * <p>
     * Idempotent: it runs only while the namespaced id holds no entries (and once
     * per id per node), and it copies rather than moves, so conversations still
     * running under the bare id keep their memories. The bare-id entries remain — a
     * GDPR request for such a user must cover both ids (documented). Best-effort: a
     * failure is logged and the turn proceeds with whatever the namespaced id
     * already has.
     */
    void carryOverLegacyMemories(SlackUser user) {
        String target = user.eddiUserId();
        String source = user.legacyUserId();
        if (source == null || source.equals(target) || legacyMemoriesCarried.get(target) != null) {
            return;
        }
        try {
            if (userMemoryStore.countEntries(target) == 0) {
                int copied = 0;
                for (UserMemoryEntry entry : userMemoryStore.getAllEntries(source)) {
                    userMemoryStore.upsert(new UserMemoryEntry(null, target, entry.key(), entry.value(), entry.category(),
                            entry.visibility(), entry.sourceAgentId(), entry.groupIds(), entry.sourceConversationId(),
                            entry.conflicted(), entry.accessCount(), entry.createdAt(), entry.updatedAt()));
                    copied++;
                }
                if (copied > 0) {
                    LOGGER.infof("Copied %d long-term memories from a legacy bare Slack id to its namespaced id", copied);
                }
            }
            legacyMemoriesCarried.put(target, Boolean.TRUE);
        } catch (Exception e) {
            LOGGER.warnf("Could not carry legacy Slack memories over to the namespaced id: %s", e.getMessage());
        }
    }

    /**
     * Close the conversation this caller created before discovering it had lost the
     * mapping race.
     * <p>
     * Nothing points at it: the stored mapping names the winner, so this one would
     * sit in Mongo as a live conversation nobody can reach, counted by every query
     * that looks for open conversations. Ending it is best-effort — the caller
     * already has a usable conversation, so a failure here must not turn a
     * recovered race into a failed turn.
     */
    private void endOrphanedConversation(String conversationId) {
        if (conversationId == null) {
            return;
        }
        try {
            conversationService.endConversation(conversationId, "system:lost-create-race");
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Could not end the orphaned conversation %s", sanitize(conversationId));
        }
    }

    /**
     * Send a message to EDDI and wait synchronously for the response snapshot.
     * <p>
     * Throws {@link IConversationService.ConversationAwaitingApprovalException}
     * (synchronously, from {@code say}) when the conversation is already paused —
     * the caller must translate that into a "still awaiting" notice rather than a
     * generic error. When THIS turn pauses, {@code say} completes normally
     * ({@code onComplete}) and the returned snapshot carries state
     * {@code AWAITING_HUMAN}.
     * <p>
     * A queued-say skip ({@code onSkipped}) means the input was DROPPED without
     * being processed — the conversation was paused/busy/ended by the time the
     * queued turn ran. It is NOT a fresh pause and NOT a fresh response: it is
     * mapped to a sentinel ({@link #SKIPPED_STILL_AWAITING} when the drop was a
     * pause, else {@link #SKIPPED_NOT_ACTIVE}) so callers post the right notice
     * instead of a duplicate approval card (H4) or a stale replay (H7).
     */
    private SimpleConversationMemorySnapshot sendAndWait(String conversationId, String message) throws Exception {
        var inputData = new InputData();
        inputData.setInput(message);
        inputData.setContext(Map.of("slack", new Context(Context.ContextType.string, "true")));

        var responseFuture = new CompletableFuture<SimpleConversationMemorySnapshot>();

        conversationService.say(conversationId, false, true, Collections.emptyList(), inputData, false,
                new IConversationService.ConversationResponseHandler() {
                    @Override
                    public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                        if (snapshot != null) {
                            responseFuture.complete(snapshot);
                        } else {
                            responseFuture.completeExceptionally(new RuntimeException("Agent returned null response"));
                        }
                    }

                    @Override
                    public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                        // Dropped turn — do NOT deliver the (stale) snapshot as a
                        // fresh outcome. AWAITING_HUMAN → still-awaiting; anything
                        // else (IN_PROGRESS/ENDED/EXECUTION_INTERRUPTED) → not-active.
                        boolean stillAwaiting = snapshot != null
                                && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;
                        responseFuture.complete(stillAwaiting ? SKIPPED_STILL_AWAITING : SKIPPED_NOT_ACTIVE);
                    }
                });

        return responseFuture.get(slackConfig.getRequestTimeoutSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Post a message to Slack, chunking if it exceeds Slack's 4000-char limit.
     *
     * @param botToken
     *            explicit bot token (if {@code null}, falls back to router lookup)
     */
    private void postMessageChunked(String channelId, String threadTs, String text,
                                    String botToken) {
        if (text == null || text.isEmpty())
            return;
        if (text.length() <= MAX_SLACK_MESSAGE_LENGTH) {
            postMessage(channelId, threadTs, text, botToken);
            return;
        }

        // Chunk at paragraph or line boundaries
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(offset + MAX_SLACK_MESSAGE_LENGTH, text.length());
            if (end < text.length()) {
                // Try to break at a newline
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > offset) {
                    end = lastNewline;
                }
            }
            // Safety: ensure forward progress even if end == offset
            if (end <= offset) {
                end = Math.min(offset + MAX_SLACK_MESSAGE_LENGTH, text.length());
            }
            postMessage(channelId, threadTs, text.substring(offset, end), botToken);
            offset = end;
        }
    }

    /**
     * Post a single message to Slack via the Web API.
     *
     * @param botToken
     *            explicit bot token; if {@code null}, falls back to
     *            {@link ChannelTargetRouter#getBotToken}
     */
    private void postMessage(String channelId, String threadTs, String text,
                             String botToken) {
        // Resolve bot token: prefer explicit parameter, fallback to router
        String resolvedToken = botToken;
        if (resolvedToken == null || resolvedToken.isEmpty()) {
            resolvedToken = channelTargetRouter.getBotToken("slack", channelId);
        }

        if (resolvedToken == null || resolvedToken.isEmpty()) {
            LOGGER.warnf("No bot token configured for Slack channel %s — cannot post message", sanitize(channelId));
            return;
        }

        String auth = "Bearer " + resolvedToken;

        int maxRetries = slackConfig.getApiMaxRetries();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                slackApi.postMessage(auth, channelId, threadTs, text);
                return;
            } catch (SlackDeliveryException e) {
                if (attempt < maxRetries) {
                    long backoff = slackConfig.getApiRetryBaseMs() * (1L << (attempt - 1));
                    LOGGER.warnf("Slack API call failed (attempt %d/%d), retrying in %dms: %s",
                            attempt, maxRetries, backoff, e.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    LOGGER.errorf("SLACK_DELIVERY_FAILED | channel=%s | threadTs=%s | textLength=%d | attempts=%d | error=%s",
                            sanitize(channelId), sanitize(threadTs), text != null ? text.length() : 0,
                            maxRetries, e.getMessage());
                }
            }
        }
    }

    /**
     * Post a help message listing available targets for this channel.
     *
     * @param botToken
     *            explicit bot token (if {@code null}, falls back to router lookup)
     */
    private void postHelp(String channelId, String threadTs, String botToken) {
        var integration = channelTargetRouter.getIntegration("slack", channelId);
        if (integration.isEmpty()) {
            postMessage(channelId, threadTs,
                    "👋 Hi! Send me a message and I'll respond.", botToken);
            return;
        }

        var config = integration.get();
        var sb = new StringBuilder();
        sb.append("👋 *Available targets in this channel:*\n\n");

        for (ChannelTarget target : config.getTargets()) {
            String name = target.getName() != null ? target.getName() : "(unnamed)";
            String type = target.getType() == ChannelTarget.TargetType.GROUP ? "group" : "agent";
            String isDefault = config.getDefaultTargetName() != null
                    && name.equalsIgnoreCase(config.getDefaultTargetName())
                            ? " _(default)_"
                            : "";
            sb.append("• *").append(name).append("*").append(isDefault);
            sb.append(" [").append(type).append("]\n");
            if (target.getTriggers() != null && !target.getTriggers().isEmpty()) {
                sb.append("  Triggers: ");
                sb.append(String.join(", ", target.getTriggers().stream()
                        .map(t -> "`" + t + "`" + ":")
                        .toList()));
                sb.append("\n");
            }
        }

        sb.append("\n_Type a message to talk to the default target, or use a trigger keyword._");
        postMessage(channelId, threadTs, sb.toString(), botToken);
    }

    /**
     * Get the thread timestamp for threading replies. Returns the original
     * message's ts for new threads, or the existing thread_ts for replies within a
     * thread.
     */
    private String getThreadTs(Map<String, Object> event) {
        String threadTs = (String) event.get("thread_ts");
        if (threadTs != null) {
            return threadTs;
        }
        // For new @mentions, use the event ts so the reply starts a thread
        return (String) event.get("ts");
    }

    /**
     * Strip the bot mention prefix from message text.
     * {@code "<@U0123BOTID> what is EDDI?" → "what is EDDI?"}
     */
    static String stripBotMention(String text) {
        Matcher matcher = BOT_MENTION_PATTERN.matcher(text);
        return matcher.replaceFirst("").trim();
    }
}
