/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ObserveGate;
import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore;
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
    /**
     * The queued turn was dropped because the conversation had ENDED by the time it
     * ran. Unlike {@link #SKIPPED_NOT_ACTIVE} this is not worth retrying on the
     * same conversation, so it is recovered from like an ended conversation found
     * up front: the thread gets a fresh one.
     */
    private static final SimpleConversationMemorySnapshot SKIPPED_ENDED = new SimpleConversationMemorySnapshot();

    /**
     * Posted in a thread before its first message goes to a fresh conversation,
     * when the conversation it replaces was ended because the agent version it ran
     * on was retired. Any other end is replaced silently — the thread's history is
     * still on screen, so nothing is lost for the user.
     */
    static final String AGENT_UPDATED_NOTICE = "🔄 I've been updated, so I'm starting a fresh conversation in this thread.";

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
     * Persisted record of every approval card posted, keyed by
     * {@code (integrationName, conversationId, pause identity)}. It is both the
     * idempotency marker — one card per pending pause: a card is posted once when a
     * pause is first observed (a normal-turn pause, or an
     * init-turn/CONVERSATION_START pause detected on the next say — H8), and
     * re-message-while-paused must not re-post, while a later distinct pause in the
     * same conversation still gets its own card (F12) — and the binding the
     * interactivity endpoint checks before it accepts a decision (see
     * {@link ISlackApprovalRecordStore}). Persisted rather than cached, so neither
     * guarantee is lost on a restart. The record is removed on FAILED delivery so a
     * retry can re-attempt.
     */
    private final ISlackApprovalRecordStore approvalRecords;

    /**
     * Retained but intentionally NOT used to migrate memories out of the shared
     * bare Slack-id namespace (Finding B): the raw id carries no workspace, the
     * namespace is shared across every source, and team_id is attacker-supplied in
     * a validly-signed event — so any standalone move could relocate a victim's
     * legacy memories into an attacker's namespace. Kept so the no-migration
     * invariant is enforceable in tests and for a future workspace-safe migration.
     */
    @SuppressWarnings("unused")
    private final IUserMemoryStore userMemoryStore;

    @Inject
    public SlackEventHandler(ChannelTargetRouter channelTargetRouter,
            ObserveGate observeGate,
            ToolCostTracker toolCostTracker,
            SlackWebApiClient slackApi,
            IConversationService conversationService,
            IGroupConversationService groupConversationService,
            IUserConversationStore userConversationStore,
            ICacheFactory cacheFactory,
            SlackConfig slackConfig,
            ISlackApprovalRecordStore approvalRecords,
            IUserMemoryStore userMemoryStore) {
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
        this.approvalRecords = approvalRecords;
        this.userMemoryStore = userMemoryStore;
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
     * @param botUserId
     *            this app's own Slack user id, from the event envelope, or
     *            {@code null} when it did not carry one. Used only to tell a
     *            message addressed to this bot from one that merely mentions
     *            somebody — see {@code handleObservedMessage}.
     */
    public void handleEventAsync(String eventId, Map<String, Object> event, String botUserId) {
        handleEventAsync(eventId, event, botUserId, EventOrigin.UNKNOWN);
    }

    /**
     * What the webhook established about an event beyond its body.
     *
     * @param teamId
     *            the workspace from the envelope's {@code team_id} — qualifies the
     *            EDDI user id (see {@link SlackUserIdentity}); {@code null} falls
     *            back to the event's own {@code team} field
     * @param signingIntegrationName
     *            for an event in a channel no integration owns (a DM), the
     *            integration whose signing secret authenticated it — the DM is
     *            routed to that integration's default target rather than to an
     *            arbitrary one. {@code null} when routing goes by channel, or the
     *            signer was a legacy connector
     * @param verifiedSigningSecret
     *            the signing secret that verified the event (the channel owner's,
     *            or for an unowned channel the signer's). Every route the event
     *            takes — including a thread lock or a group follow-up, both found
     *            by a timestamp the sender chooses — must belong to an integration
     *            or legacy connector holding this secret. {@code null} only for an
     *            event with no channel (which is dropped anyway); with no secret no
     *            route matches. A resolved secret, so never logged
     */
    public record EventOrigin(String teamId, String signingIntegrationName, String verifiedSigningSecret) {
        /** Nothing verified: such an event routes nowhere. */
        public static final EventOrigin UNKNOWN = new EventOrigin(null, null, null);

        @Override
        public String toString() {
            return "EventOrigin[teamId=" + teamId + ", signingIntegrationName=" + signingIntegrationName + "]";
        }
    }

    /**
     * A group discussion's agent message, registered for follow-up routing: the
     * listener that holds its context, the route that started the discussion (its
     * credentials, and the app a follow-up must come from) and the channel it ran
     * in.
     */
    record GroupFollowUp(SlackGroupDiscussionListener listener, ResolvedTarget route, String channelId) {
    }

    /**
     * @param origin
     *            workspace and signing integration established by the webhook — see
     *            {@link EventOrigin}
     */
    public void handleEventAsync(String eventId, Map<String, Object> event, String botUserId, EventOrigin origin) {
        EventOrigin effectiveOrigin = origin != null ? origin : EventOrigin.UNKNOWN;
        // De-duplicate: Slack retries events up to 3 times
        if (eventDedup.get(eventId) != null) {
            LOGGER.debugf("Duplicate Slack event %s — skipping", sanitize(eventId));
            return;
        }
        eventDedup.put(eventId, Boolean.TRUE);

        executorService.submit(() -> {
            try {
                handleEvent(event, botUserId, effectiveOrigin);
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
                if (channelId != null) {
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
    /**
     * Whether {@code route} belongs to the app whose signing secret verified the
     * event: its integration's (or legacy connector's) secret equals the verified
     * one. Fails closed when nothing was verified.
     */
    static boolean routeMatchesSigner(ResolvedTarget route, EventOrigin origin) {
        return route != null && origin != null
                && ChannelTargetRouter.secretsEqual(route.signingSecret(), origin.verifiedSigningSecret());
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private void handleEvent(Map<String, Object> event, String botUserId, EventOrigin origin) throws Exception {
        String eventType = (String) event.get("type");
        String eventSubtype = (String) event.get("subtype");
        String eventChannel = (String) event.get("channel");
        String eventThreadTs = (String) event.get("thread_ts");
        // Never log the message BODY, at any level — a Slack message is end-user
        // content and may carry secrets pasted into a channel, and sanitize() strips
        // control characters, not credentials. Only its length is logged.
        int textLength = event.get("text") instanceof String t ? t.length() : 0;
        LOGGER.infof("[SLACK] Event received: type=%s, subtype=%s, channel=%s, thread_ts=%s, has_bot_id=%s, text_len=%d",
                sanitize(eventType), sanitize(eventSubtype), sanitize(eventChannel),
                sanitize(eventThreadTs), event.containsKey("bot_id"), textLength);

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
                if (handleObservedMessage(event, eventChannel, botUserId, origin)) {
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
        SlackUser user = slackUser(origin, slackUserId);

        // Strip bot mention prefix: "<@U0123BOTID> hello" → "hello"
        text = stripBotMention(text);

        String threadTs = getThreadTs(event);

        if (text.isBlank()) {
            postHelp(channelId, threadTs, null);
            return;
        }

        // 1. Check thread target lock (existing threads keep their target)
        String parentTs = (String) event.get("thread_ts");
        ResolvedTarget resolved = null;

        if (parentTs != null) {
            resolved = channelTargetRouter.resolveThreadTarget("slack", channelId, parentTs);
            if (resolved != null && resolved.integration() == null && resolved.legacySigningSecret() == null) {
                // A channel no integration owns (a DM) gives the lock no credentials.
                // Take those of an integration or legacy connector that holds the
                // verifying secret AND has the locked target among its own targets;
                // otherwise one app's secret could continue a thread locked to
                // another app's agent.
                resolved = channelTargetRouter.threadCredentialsForDm("slack", resolved.target(),
                        origin.verifiedSigningSecret());
                if (resolved == null) {
                    LOGGER.warnf("[SLACK] Thread %s in channel %s is locked to a target the sending app does not "
                            + "serve — ignoring", sanitize(parentTs), sanitize(channelId));
                    return;
                }
            }
        }

        // 2. Check group follow-up (thread root was a group discussion)
        if (resolved == null && parentTs != null
                && tryHandleAgentFollowUp(parentTs, channelId, user, text, threadTs, origin)) {
            return;
        }

        // 3. Fresh resolution via ChannelTargetRouter
        if (resolved == null) {
            resolved = channelTargetRouter.resolveTarget("slack", channelId, text);
        }

        // 4. DM fallback: if no explicit config for this channel (DMs use dynamic
        // D-prefixed IDs), fall back to any configured Slack integration's default
        // target
        if (resolved == null && isDirectMessage) {
            // Route to the integration whose secret signed this DM (the webhook
            // established it), not to whichever integration happens to be first. A
            // DM signed by a legacy connector goes to a legacy connector with that
            // secret — never to the first new-style integration.
            if (origin.signingIntegrationName() == null && origin.verifiedSigningSecret() != null) {
                resolved = channelTargetRouter.resolveLegacyDefaultForDm(text, origin.verifiedSigningSecret());
            } else {
                resolved = channelTargetRouter.resolveDefaultForDm("slack", text, origin.signingIntegrationName());
            }
        }

        if (resolved == null) {
            postHelp(channelId, threadTs, null);
            return;
        }

        // 5. The route must belong to the app that signed the event. The webhook
        // bound the signature to the channel's owner, but a route can come from
        // elsewhere — the DM fallback, a thread lock — so without this an event
        // signed by one app could run another app's target with its bot token.
        if (!routeMatchesSigner(resolved, origin)) {
            LOGGER.warnf("[SLACK] Event for channel %s resolved to a route of a different app — ignoring",
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
            case GROUP -> handleGroupDiscussion(resolved, channelId, user.eddiUserId(), threadTs, text, botToken);
            default -> LOGGER.warnf("Unsupported target type: %s", resolved.target().getType());
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
                                          String botUserId, EventOrigin origin) {
        try {
            return observeMessage(event, channelId, botUserId, origin);
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
                                   String botUserId, EventOrigin origin) {
        if (channelId == null) {
            return false;
        }
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

        String text = (String) event.get("text");
        String slackUserId = (String) event.get("user");
        if (slackUserId == null) {
            return false;
        }
        SlackUser user = slackUser(origin, slackUserId);
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
                String intent = threadIntent(channelId, target.getTargetId(), threadTs);
                ThreadConversation thread = openThreadConversation(target.getTargetId(), user, intent);
                conversationId = thread.conversationId();
                costBefore = conversationCost(conversationId);
                String deliveredTo = sendInThread(resolved, target.getTargetId(), user, intent, thread, channelId,
                        threadTs, message, botToken);
                if (!deliveredTo.equals(conversationId)) {
                    // The conversation ended between opening and sending and the
                    // thread was given a fresh one. Its spend so far is this turn's,
                    // so it is measured from zero.
                    conversationId = deliveredTo;
                    costBefore = OptionalDouble.of(0.0);
                }
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
        String intent = threadIntent(channelId, agentId, threadTs);

        // Use strippedMessage (trigger keyword removed) or fall back to original text
        // (thread replies from resolveThreadTarget have strippedMessage=null)
        String message = resolved.strippedMessage() != null ? resolved.strippedMessage() : originalText;

        ThreadConversation thread = openThreadConversation(agentId, user, intent);
        sendInThread(resolved, agentId, user, intent, thread, channelId, threadTs, message, botToken);
    }

    /**
     * The stable key a thread's conversation is tracked under. An observed turn
     * uses the same key an addressed turn would, so an observer and a mention in
     * the same channel and thread share one conversation rather than talking past
     * each other.
     * <p>
     * Uses channelId + targetId (agentId/groupId) — NOT mutable display names like
     * integration name or target name, which would break IUserConversationStore
     * lookups on rename.
     */
    private static String threadIntent(String channelId, String agentId, String threadTs) {
        String threadKey = threadTs != null ? threadTs : "main";
        return "channel:slack:" + channelId + ":" + agentId + ":" + threadKey;
    }

    /**
     * {@link #sendAndDeliver}, recovering once from a conversation that turns out
     * to have ended between being opened and being sent to — an undeploy or the
     * idle sweep can end it at any moment. The thread gets a fresh conversation and
     * the message goes there; a second end in a row propagates.
     *
     * @return the conversation the message was delivered to
     */
    // Package-private for unit testing.
    String sendInThread(ResolvedTarget resolved, String agentId, SlackUser user, String intent,
                        ThreadConversation thread, String channelId, String threadTs, String message,
                        String botToken)
            throws Exception {
        announceReplacement(thread, channelId, threadTs, botToken);
        try {
            sendAndDeliver(resolved, thread.conversationId(), agentId, channelId, threadTs, message, botToken);
            return thread.conversationId();
        } catch (IConversationService.ConversationEndedException e) {
            ThreadConversation fresh = replaceEndedThreadConversation(agentId, user, intent, thread.conversationId(),
                    endReasonOf(thread.conversationId()));
            announceReplacement(fresh, channelId, threadTs, botToken);
            sendAndDeliver(resolved, fresh.conversationId(), agentId, channelId, threadTs, message, botToken);
            return fresh.conversationId();
        }
    }

    /**
     * Tell the thread that its conversation starts over, if the one it replaced
     * ended because the agent was updated. Best-effort: a failed notice must not
     * cost the user the answer that follows it.
     */
    private void announceReplacement(ThreadConversation thread, String channelId, String threadTs, String botToken) {
        if (thread.replaced() && IConversationService.END_REASON_AGENT_VERSION_RETIRED.equals(thread.replacedEndReason())) {
            try {
                postMessage(channelId, threadTs, AGENT_UPDATED_NOTICE, botToken);
            } catch (RuntimeException e) {
                LOGGER.debugf("Could not post the agent-updated notice in channel %s: %s", sanitize(channelId), e.getMessage());
            }
        }
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
            // the approval card now (idempotent via the persisted approval record).
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
        // Ended while the turn was queued: the same situation as say() refusing an
        // ended conversation, so it is reported the same way and the caller gives
        // the thread a fresh conversation.
        if (snapshot == SKIPPED_ENDED) {
            throw new IConversationService.ConversationEndedException("Conversation has ended!");
        }
        // A skip for a non-active conversation (busy/interrupted) — the message was
        // dropped; tell the user rather than replaying a stale turn.
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

        String approverIds = platformConfig.get(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS);
        boolean includeButtons = !SlackHitlSupport.parseApproverUserIds(approverIds).isEmpty();

        // Idempotency (H8/F12) and decision binding: record the card BEFORE posting
        // it, keyed by PAUSE IDENTITY (the bookmark's hitlPausedAt), NOT by
        // conversationId alone — so a second distinct pause in the same conversation
        // (pause → resume → pause) is NOT suppressed, and a card can only ever
        // resolve the pause it was posted for. When the best-effort bookmark load
        // returns a non-AWAITING snapshot (hitlPausedAt null) the record carries
        // UNKNOWN_PAUSE rather than blocking the card; such a record matches only a
        // pause that began before it was written. A normal-turn pause and an
        // init-turn pause detected on the next say both route here — the first wins
        // the record; re-message-while-paused is a no-op. The record is removed
        // below if delivery fails. The record also carries a fresh card id that goes
        // into this card's buttons, so only THIS card can resolve this pause — an
        // older card for the same conversation cannot approve it.
        String integrationName = integration.getName();
        String cardId = ISlackApprovalRecordStore.newCardId();
        String pauseEpoch = ISlackApprovalRecordStore.pauseEpochOf(
                bookmark != null ? bookmark.getHitlPausedAt() : null);
        boolean recorded = false;
        if (!SlackHitlSupport.isBindableIntegrationName(integrationName)) {
            // Unbindable — a decision on it would be refused. A name containing '|'
            // (stored before the save-time rule) would split the button value.
            if (integrationName != null && !integrationName.isBlank()) {
                LOGGER.warnf("Slack integration '%s' has a name containing '|', which approval buttons cannot carry "
                        + "— posting the approval card without buttons; rename the integration", sanitize(integrationName));
            }
            includeButtons = false;
        } else {
            try {
                if (!approvalRecords.tryRecord(integrationName, conversationId, pauseEpoch, cardId, approvalChannel)) {
                    return;
                }
                recorded = true;
            } catch (RuntimeException e) {
                // Fail closed: without a record no decision on this card could be
                // accepted, so post it as a notification only.
                LOGGER.errorf("Could not record the HITL approval card for %s — posting it without buttons: %s",
                        sanitize(conversationId), e.getMessage());
                includeButtons = false;
            }
        }

        String pauseReason = bookmark != null ? bookmark.getHitlPauseReason() : null;
        String timeoutInfo = bookmark != null
                ? formatTimeoutInfo(
                        bookmark.getHitlTimeoutPolicy() != null ? bookmark.getHitlTimeoutPolicy().name() : null,
                        bookmark.getHitlApprovalTimeout())
                : null;

        // The button value carries the owning integration name and this card's id,
        // so the decision is bound to THIS integration and THIS card.
        String actionValue = SlackHitlSupport.buildActionValue(integrationName, conversationId, cardId);
        String pauseType = bookmark != null ? bookmark.getHitlPauseType() : null;
        var pendingToolCalls = bookmark != null ? bookmark.getHitlPendingToolCalls() : null;
        var blocks = SlackHitlSupport.buildApprovalBlocks(
                "⏸️ Conversation awaiting approval", "Conversation", conversationId,
                agentId, pauseReason, timeoutInfo, actionValue, includeButtons,
                pauseType, pendingToolCalls);
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
            if (recorded) {
                forgetApprovalRecord(integrationName, conversationId, pauseEpoch);
            }
            return;
        }
        String auth = "Bearer " + botToken;
        try {
            slackApi.postBlocksMessage(auth, approvalChannel, null, blocks, fallback);
        } catch (SlackDeliveryException e) {
            // F12: delivery failed — drop the record so a later retry can re-attempt
            // instead of being suppressed until the record expires.
            if (recorded) {
                forgetApprovalRecord(integrationName, conversationId, pauseEpoch);
            }
            LOGGER.warnf("Failed to post HITL approval notification for %s: %s",
                    sanitize(conversationId), e.getMessage());
        }
    }

    private void forgetApprovalRecord(String integrationName, String subject, String pauseEpoch) {
        try {
            approvalRecords.delete(integrationName, subject, pauseEpoch);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not delete the record of an undelivered HITL card for %s: %s",
                    sanitize(subject), e.getMessage());
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
                                       String userId, String threadTs, String originalText,
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
                hitlApprovalChannel, hitlApproverUserIds, integrationName, approvalRecords);

        String question = resolved.strippedMessage() != null ? resolved.strippedMessage() : originalText;
        try {
            // The question is end-user message content — log its length, not its text.
            LOGGER.infof("Starting group discussion in channel %s, group %s (question_len=%d)",
                    sanitize(channelId), sanitize(groupId), question.length());

            groupConversationService.startAndDiscussAsync(groupId, question, userId, listener);

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
     * Wait for the group discussion to complete, then register all agent message ts
     * mappings for follow-up routing.
     */
    private void registerAgentThreadMappings(SlackGroupDiscussionListener listener, ResolvedTarget route,
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

        // Register all agent message ts → listener for follow-up detection
        for (String ts : listener.getAgentMessageTsMap().values()) {
            activeGroupListeners.put(ts, new GroupFollowUp(listener, route, channelId));
            LOGGER.debugf("Registered agent thread ts=%s for follow-up routing", ts);
        }
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
                                           EventOrigin origin)
            throws Exception {
        GroupFollowUp followUp = activeGroupListeners.get(parentTs);
        if (followUp == null) {
            return false; // Not a thread from a group discussion
        }
        // The key is a bare Slack timestamp the sender chooses, so the reply must
        // also be in the discussion's channel and come from the app whose route
        // started it — otherwise another app could run the discussion's agent, with
        // its context, in a channel of its own.
        if (!channelId.equals(followUp.channelId()) || !routeMatchesSigner(followUp.route(), origin)) {
            LOGGER.warnf("[SLACK] Follow-up for thread %s does not match the discussion's channel or app — ignoring",
                    sanitize(parentTs));
            return true;
        }
        SlackGroupDiscussionListener listener = followUp.listener();
        String botToken = followUp.route().botToken();

        String agentId = listener.getAgentIdForMessageTs(parentTs);
        if (agentId == null) {
            return false;
        }

        SlackGroupDiscussionListener.AgentContext ctx = listener.getAgentContext(agentId);
        if (ctx == null) {
            return false;
        }

        // Log routing identity but never the message body, at any level — it is
        // end-user content that may contain secrets. Length only.
        LOGGER.infof("Follow-up in agent %s thread from user %s (text_len=%d)",
                sanitize(ctx.displayName()), sanitize(user.slackUserId()), text.length());

        // Build context-enriched input
        String enrichedInput = buildFollowUpInput(ctx, text);

        // Route to the specific agent from the group discussion
        String intent = "channel:followup:" + channelId + ":" + parentTs;
        ThreadConversation thread = openThreadConversation(agentId, user, intent);
        announceReplacement(thread, channelId, threadTs, botToken);
        try {
            deliverFollowUp(thread.conversationId(), channelId, threadTs, enrichedInput, botToken);
        } catch (IConversationService.ConversationEndedException e) {
            // Ended between opening and sending — once, like sendInThread.
            ThreadConversation fresh = replaceEndedThreadConversation(agentId, user, intent, thread.conversationId(),
                    endReasonOf(thread.conversationId()));
            announceReplacement(fresh, channelId, threadTs, botToken);
            deliverFollowUp(fresh.conversationId(), channelId, threadTs, enrichedInput, botToken);
        }

        return true;
    }

    /**
     * Send a follow-up and post its outcome, as the app that started the
     * discussion. Follow-ups carry no approver configuration, so no approver
     * notification is sent — but the pause notice and "still awaiting" handling
     * still apply so the user is never left with a generic error.
     *
     * @throws IConversationService.ConversationEndedException
     *             if the conversation has ended, so the caller can replace it
     */
    private void deliverFollowUp(String conversationId, String channelId, String threadTs, String enrichedInput,
                                 String botToken)
            throws Exception {
        try {
            SimpleConversationMemorySnapshot snapshot = sendAndWait(conversationId, enrichedInput);
            if (snapshot == SKIPPED_STILL_AWAITING) {
                postMessage(channelId, threadTs, SlackHitlSupport.STILL_AWAITING_NOTICE, botToken);
                return;
            }
            if (snapshot == SKIPPED_ENDED) {
                throw new IConversationService.ConversationEndedException("Conversation has ended!");
            }
            if (snapshot == SKIPPED_NOT_ACTIVE) {
                postMessage(channelId, threadTs, SlackHitlSupport.CONVERSATION_NOT_ACTIVE_NOTICE, botToken);
                return;
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
     * A Slack user: the raw id Slack sends, and the namespaced id EDDI stores (see
     * {@link SlackUserIdentity}). The raw id is kept only for display, approver
     * checks and the one-time compatibility lookups below.
     */
    record SlackUser(String slackUserId, String eddiUserId) {
    }

    private static SlackUser slackUser(EventOrigin origin, String slackUserId) {
        // The workspace comes ONLY from the webhook-pinned origin.teamId() (the
        // signing/owning integration's declared teamId, or null when unbindable).
        // The event's own `team` field is attacker-supplied in a validly-signed
        // event and is deliberately NOT trusted here (review residual #1) — trusting
        // it let a forged team map a caller onto a victim's slack:<team>:<user>
        // memories. A null teamId yields a team-less identity that reaches no victim.
        return new SlackUser(slackUserId, SlackUserIdentity.eddiUserId(origin.teamId(), slackUserId));
    }

    /**
     * Map a Slack thread to an EDDI conversation. Uses
     * {@link IUserConversationStore} with intent key composed from integration +
     * target + thread, owned by the NAMESPACED user id.
     * <p>
     * Compatibility (see {@link SlackUserIdentity}): a mapping stored under the raw
     * Slack id by an earlier release is honoured and re-keyed to the namespaced id,
     * so an ongoing thread keeps its conversation. Nothing else is migrated: a
     * brand-new conversation does not inherit long-term memories stored under the
     * raw id.
     */
    // Package-private for unit testing (the identity-namespacing compatibility
    // paths).
    String getOrCreateConversation(String agentId, SlackUser user, String intent)
            throws Exception {
        return openThreadConversation(agentId, user, intent).conversationId();
    }

    /**
     * A thread's conversation, and whether this call replaced an ended one — in
     * which case {@code replacedEndReason} is that conversation's end reason, if it
     * had one.
     */
    record ThreadConversation(String conversationId, boolean replaced, String replacedEndReason) {
        static ThreadConversation existing(String conversationId) {
            return new ThreadConversation(conversationId, false, null);
        }
    }

    /**
     * The conversation a thread's next message goes to.
     * <p>
     * A mapped conversation that has ENDED — or no longer exists at all — cannot
     * take another turn, and used to strand the thread for good: every further
     * message was refused. The idle sweep ends conversations, and so does an
     * undeploy with {@code endAllActiveConversations}. Such a mapping is replaced
     * by a fresh conversation. The user's long-term memories are keyed on the user,
     * not the conversation, so what the agent knows about them carries over; only
     * the conversation's own state starts over, which is what ended means.
     */
    // Package-private for unit testing.
    ThreadConversation openThreadConversation(String agentId, SlackUser user, String intent)
            throws Exception {
        String eddiUserId = user.eddiUserId();
        // Try existing — readUserConversation returns null when not found,
        // throws ResourceStoreException only on real DB errors (which should propagate)
        UserConversation existing = userConversationStore.readUserConversation(intent, eddiUserId);
        if (existing != null) {
            EndedConversation ended = endedConversation(existing.getConversationId());
            if (ended == null) {
                return ThreadConversation.existing(existing.getConversationId());
            }
            return replaceEndedThreadConversation(agentId, user, intent, existing.getConversationId(), ended.endReason());
        }

        String legacyUserId = user.slackUserId();
        if (SlackUserIdentity.isLegacySlackUserId(legacyUserId)) {
            UserConversation legacy = userConversationStore.readUserConversation(intent, legacyUserId);
            if (legacy != null && legacy.getConversationId() != null) {
                // Adopt the pre-namespacing mapping for THIS intent: the conversation
                // keeps its raw-id owner, so its long-term memories load without any
                // move. We deliberately do NOT move memories out of the bare Slack-id
                // namespace here (Finding B): the raw id carries no workspace, the
                // bare-id namespace is shared across every source, and team_id is
                // attacker-supplied in a validly-signed event — so a standalone move
                // would let a second integration's operator relocate a victim's
                // legacy memories into their own namespace. Adoption is safe because
                // it is scoped to a conversation MAPPING under this exact intent.
                return ThreadConversation.existing(rekeyLegacyMapping(legacy, eddiUserId));
            }
        }

        return ThreadConversation.existing(createThreadConversation(agentId, eddiUserId, intent).conversationId());
    }

    /**
     * Give a thread a fresh conversation in place of {@code endedConversationId}.
     * <p>
     * The mapping is removed only while it still names the ended conversation. Two
     * messages that find the same ended conversation both get here; the second
     * one's delete then matches nothing — the first has already replaced the
     * mapping — and its create collides with the first one's, so both end up on one
     * conversation instead of each starting its own. Only the caller whose
     * conversation won reports {@code replaced}, so the thread is told about it
     * once.
     */
    // Package-private for unit testing.
    ThreadConversation replaceEndedThreadConversation(String agentId, SlackUser user, String intent,
                                                      String endedConversationId, String endReason)
            throws Exception {
        String eddiUserId = user.eddiUserId();
        userConversationStore.deleteUserConversationIfMatches(intent, eddiUserId, endedConversationId);
        CreatedConversation created = createThreadConversation(agentId, eddiUserId, intent);
        if (created.won()) {
            LOGGER.infof("Replaced ended conversation %s in %s with %s (end reason: %s)",
                    sanitize(endedConversationId), sanitize(intent), sanitize(created.conversationId()),
                    endReason != null ? sanitize(endReason) : "none");
        }
        return new ThreadConversation(created.conversationId(), created.won(), created.won() ? endReason : null);
    }

    /**
     * A conversation this thread may use, and whether it is the one this call
     * started ({@code won}) rather than one a concurrent message stored first.
     */
    private record CreatedConversation(String conversationId, boolean won) {
    }

    private CreatedConversation createThreadConversation(String agentId, String eddiUserId, String intent)
            throws Exception {
        var result = conversationService.startConversation(
                Deployment.Environment.production, agentId, eddiUserId,
                Map.of("channelIntent", new Context(Context.ContextType.string, intent)));

        // Store mapping
        var mapping = new UserConversation(intent, eddiUserId,
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
            UserConversation winner = userConversationStore.readUserConversation(intent, eddiUserId);
            if (winner != null && winner.getConversationId() != null) {
                LOGGER.debugf("Lost the create race for %s/%s — using the stored conversation",
                        sanitize(intent), sanitize(eddiUserId));
                endOrphanedConversation(result.conversationId());
                return new CreatedConversation(winner.getConversationId(), false);
            }
            // The mapping existed a moment ago and cannot be read now. Ours is
            // the only conversation we can name, so use it rather than fail.
            LOGGER.warnf("Create conflict for %s/%s but no stored mapping could be read",
                    sanitize(intent), sanitize(eddiUserId));
        }

        return new CreatedConversation(result.conversationId(), true);
    }

    /** A mapped conversation that can take no further turn, and why it ended. */
    private record EndedConversation(String endReason) {
    }

    /**
     * {@code null} while the conversation can still take a turn; otherwise why it
     * cannot. A conversation that has vanished (retention, erasure) counts as
     * ended. A state that cannot be read does NOT: a store hiccup must never cost a
     * thread its conversation, so it is left to the turn to succeed or fail as
     * before.
     */
    private EndedConversation endedConversation(String conversationId) {
        ConversationState state;
        try {
            state = conversationService.getConversationState(conversationId);
        } catch (IConversationService.ConversationNotFoundException e) {
            return new EndedConversation(null);
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not read the state of conversation %s — keeping it: %s", sanitize(conversationId),
                    e.getMessage());
            return null;
        }
        return state == ConversationState.ENDED ? new EndedConversation(endReasonOf(conversationId)) : null;
    }

    /**
     * The recorded end reason, or {@code null} when there is none or it cannot be
     * read.
     */
    private String endReasonOf(String conversationId) {
        try {
            ConversationMemorySnapshot snapshot = conversationService.getConversationMemorySnapshot(conversationId);
            return snapshot != null ? snapshot.getEndReason() : null;
        } catch (Exception e) {
            LOGGER.debugf("Could not read the end reason of conversation %s: %s", sanitize(conversationId), e.getMessage());
            return null;
        }
    }

    /**
     * Move a thread mapping stored under the raw Slack id to the namespaced id and
     * return its conversation. The conversation itself is not touched — it keeps
     * the owner it was started with, and with it the memories it has always loaded.
     * Best-effort: if re-keying fails the legacy mapping stays, and the next
     * message simply finds it again.
     */
    private String rekeyLegacyMapping(UserConversation legacy, String eddiUserId) {
        String intent = legacy.getIntent();
        try {
            userConversationStore.createUserConversation(new UserConversation(intent, eddiUserId,
                    legacy.getEnvironment(), legacy.getAgentId(), legacy.getConversationId()));
        } catch (IResourceStore.ResourceAlreadyExistsException e) {
            // A concurrent message re-keyed it first; fall through and drop the
            // legacy mapping all the same.
            LOGGER.debugf("Namespaced mapping for %s already exists", sanitize(intent));
        } catch (Exception e) {
            LOGGER.warnf("Could not re-key the legacy Slack mapping for %s: %s", sanitize(intent), e.getMessage());
            return legacy.getConversationId();
        }
        try {
            userConversationStore.deleteUserConversation(intent, legacy.getUserId());
        } catch (Exception e) {
            LOGGER.debugf("Could not delete the legacy Slack mapping for %s: %s", sanitize(intent), e.getMessage());
        }
        LOGGER.infof("Re-keyed legacy Slack conversation mapping %s to the namespaced user id", sanitize(intent));
        return legacy.getConversationId();
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
                        // fresh outcome. AWAITING_HUMAN → still-awaiting; ENDED →
                        // ended (the caller replaces the conversation); anything
                        // else (IN_PROGRESS/EXECUTION_INTERRUPTED) → not-active.
                        ConversationState state = snapshot != null ? snapshot.getConversationState() : null;
                        responseFuture.complete(state == ConversationState.AWAITING_HUMAN
                                ? SKIPPED_STILL_AWAITING
                                : state == ConversationState.ENDED ? SKIPPED_ENDED : SKIPPED_NOT_ACTIVE);
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
