/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelTarget;
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
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.datastore.IResourceStore;
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
    private static final int CONVERSATION_TIMEOUT_SECONDS = 60;

    /** Pattern to strip bot mention: {@code <@U0123BOTID> actual message} */
    private static final Pattern BOT_MENTION_PATTERN = Pattern.compile("^<@[A-Z0-9]+>\\s*");

    /** Maximum Slack message length (safe limit under 4000). */
    private static final int MAX_SLACK_MESSAGE_LENGTH = 3900;

    private final ChannelTargetRouter channelTargetRouter;
    private final SlackWebApiClient slackApi;
    private final IConversationService conversationService;
    private final IGroupConversationService groupConversationService;
    private final IUserConversationStore userConversationStore;
    private final ICache<String, Boolean> eventDedup;
    private final ExecutorService executorService;

    /** Max retries for Slack API calls with exponential backoff. */
    private static final int SLACK_API_MAX_RETRIES = 3;
    private static final long SLACK_API_RETRY_BASE_MS = 500;

    /**
     * Tracks active group discussion listeners keyed by Slack message ts. Used to
     * detect user thread-replies for follow-up conversations. Uses ICache with TTL
     * to prevent unbounded growth — follow-ups are only useful shortly after a
     * discussion finishes.
     */
    private final ICache<String, SlackGroupDiscussionListener> activeGroupListeners;

    @Inject
    public SlackEventHandler(ChannelTargetRouter channelTargetRouter,
            SlackWebApiClient slackApi,
            IConversationService conversationService,
            IGroupConversationService groupConversationService,
            IUserConversationStore userConversationStore,
            ICacheFactory cacheFactory) {
        this.channelTargetRouter = channelTargetRouter;
        this.slackApi = slackApi;
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.userConversationStore = userConversationStore;
        this.eventDedup = cacheFactory.getCache("slack-event-dedup", Duration.ofMinutes(10));
        this.activeGroupListeners = cacheFactory.getCache("slack-group-listeners", Duration.ofHours(2));
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
    public void handleEventAsync(String eventId, Map<String, Object> event) {
        // De-duplicate: Slack retries events up to 3 times
        if (eventDedup.get(eventId) != null) {
            LOGGER.debugf("Duplicate Slack event %s — skipping", sanitize(eventId));
            return;
        }
        eventDedup.put(eventId, Boolean.TRUE);

        executorService.submit(() -> {
            try {
                handleEvent(event);
            } catch (Exception e) {
                LOGGER.errorf(e, "Error handling Slack event %s", sanitize(eventId));

                // Best-effort error response to user (never leak internal details)
                String channelId = (String) event.get("channel");
                String threadTs = getThreadTs(event);
                if (channelId != null) {
                    try {
                        postMessage(channelId, threadTs,
                                "⚠️ Sorry, I encountered an error processing your message. Please try again.",
                                null);
                    } catch (Exception ignored) {
                        // Can't post error — nothing more we can do
                    }
                }
            }
        });
    }

    private void handleEvent(Map<String, Object> event) throws Exception {
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
                // Top-level channel message — only app_mention should handle these
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
        String userId = (String) event.get("user");
        String channelId = (String) event.get("channel");

        if (text == null || text.isBlank() || userId == null || channelId == null) {
            LOGGER.debugf("Incomplete Slack event — missing text/user/channel");
            return;
        }

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
        }

        // 2. Check group follow-up (thread root was a group discussion)
        if (resolved == null && parentTs != null
                && tryHandleAgentFollowUp(parentTs, channelId, userId, text, threadTs)) {
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
            resolved = channelTargetRouter.resolveDefaultForDm("slack", text);
        }

        if (resolved == null) {
            postHelp(channelId, threadTs, null);
            return;
        }

        // Lock target for this thread
        if (threadTs != null) {
            channelTargetRouter.lockThreadTarget("slack", channelId, threadTs, resolved.target());
        }

        // Resolve bot token once — passed explicitly to all post methods
        String botToken = resolved.botToken();
        switch (resolved.target().getType()) {
            case AGENT -> handleAgentConversation(resolved, channelId, userId, threadTs, text, botToken);
            case GROUP -> handleGroupDiscussion(resolved, channelId, userId, threadTs, text, botToken);
            default -> LOGGER.warnf("Unsupported target type: %s", resolved.target().getType());
        }
    }

    /**
     * Handle a standard 1:1 agent conversation routed via ChannelTargetRouter.
     */
    private void handleAgentConversation(ResolvedTarget resolved, String channelId,
                                         String userId, String threadTs, String originalText,
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

        String conversationId = getOrCreateConversation(agentId, userId, intent);
        sendAndDeliver(resolved, conversationId, agentId, channelId, threadTs, message, botToken);
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
            // Subsequent message while the conversation is already paused — input
            // was NOT consumed. Never surface the generic error message.
            postMessage(channelId, threadTs, SlackHitlSupport.STILL_AWAITING_NOTICE, botToken);
            return;
        }

        boolean paused = snapshot != null
                && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;

        // Post output-so-far (if any). extractSlackResponseText never returns null;
        // when paused with no output it returns a placeholder we suppress.
        String response = SlackHitlSupport.extractSlackResponseText(snapshot);
        if (paused) {
            if (response != null && !response.startsWith("_")) {
                postMessageChunked(channelId, threadTs, response, botToken);
            }
            postMessage(channelId, threadTs, buildPauseNotice(conversationId), botToken);
            notifyApprovers(resolved, conversationId, agentId, threadTs);
        } else {
            postMessageChunked(channelId, threadTs, response, botToken);
        }
    }

    /**
     * Build the in-thread pause notice, appending the pause reason from the HITL
     * bookmark when available.
     */
    private String buildPauseNotice(String conversationId) {
        String reason = null;
        try {
            var full = conversationService.getConversationMemorySnapshot(conversationId);
            if (full != null) {
                reason = full.getHitlPauseReason();
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not load pause reason for %s: %s",
                    sanitize(conversationId), e.getMessage());
        }
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
    private void notifyApprovers(ResolvedTarget resolved, String conversationId,
                                 String agentId, String userThreadTs) {
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

        String pauseReason = null;
        String timeoutInfo = null;
        try {
            var full = conversationService.getConversationMemorySnapshot(conversationId);
            if (full != null) {
                pauseReason = full.getHitlPauseReason();
                timeoutInfo = formatTimeoutInfo(full.getHitlTimeoutPolicy(), full.getHitlApprovalTimeout());
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not load HITL bookmark for %s: %s", sanitize(conversationId), e.getMessage());
        }

        var blocks = SlackHitlSupport.buildApprovalBlocks(
                "⏸️ Conversation awaiting approval", "Conversation", conversationId,
                agentId, pauseReason, timeoutInfo, conversationId, includeButtons);
        String fallback = "Conversation " + conversationId + " is awaiting human approval.";

        String botToken = resolved.botToken();
        String auth = botToken != null && !botToken.isBlank()
                ? "Bearer " + botToken
                : "Bearer " + channelTargetRouter.getBotToken("slack", approvalChannel);
        try {
            slackApi.postBlocksMessage(auth, approvalChannel, null, blocks, fallback);
        } catch (SlackDeliveryException e) {
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
        var integration = resolved.integration();
        if (integration != null && integration.getPlatformConfig() != null) {
            hitlApprovalChannel = integration.getPlatformConfig().get(SlackHitlSupport.CFG_HITL_APPROVAL_CHANNEL);
            hitlApproverUserIds = integration.getPlatformConfig().get(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS);
        }

        // Create the listener that streams discussion into Slack
        var listener = new SlackGroupDiscussionListener(slackApi, token, channelId, threadTs,
                hitlApprovalChannel, hitlApproverUserIds);

        String question = resolved.strippedMessage() != null ? resolved.strippedMessage() : originalText;
        try {
            LOGGER.infof("Starting group discussion in channel %s, group %s, question: %s",
                    sanitize(channelId), sanitize(groupId), sanitize(question.substring(0, Math.min(80, question.length()))));

            groupConversationService.startAndDiscussAsync(groupId, question, userId, listener);

            // Only register for follow-up routing in expanded mode (compact has no
            // channel-level messages)
            if (listener.isExpandedMode()) {
                executorService.submit(() -> registerAgentThreadMappings(listener));
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
    private void registerAgentThreadMappings(SlackGroupDiscussionListener listener) {
        // Wait for the group discussion to complete via the listener's latch
        boolean completed = listener.awaitCompletion(300, java.util.concurrent.TimeUnit.SECONDS);
        if (!completed) {
            LOGGER.warnf("Group discussion did not complete within timeout — follow-up routing may be incomplete");
        }

        // Register all agent message ts → listener for follow-up detection
        for (String ts : listener.getAgentMessageTsMap().values()) {
            activeGroupListeners.put(ts, listener);
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
                                           String userId, String text, String threadTs)
            throws Exception {
        SlackGroupDiscussionListener listener = activeGroupListeners.get(parentTs);
        if (listener == null) {
            return false; // Not a thread from a group discussion
        }

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
        String conversationId = getOrCreateConversation(agentId, userId, intent);

        // Follow-ups have no ResolvedTarget/integration config, so no approver
        // notification is sent — but the pause notice and "still awaiting" handling
        // still apply so the user is never left with a generic error.
        try {
            SimpleConversationMemorySnapshot snapshot = sendAndWait(conversationId, enrichedInput);
            boolean paused = snapshot != null
                    && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;
            String response = SlackHitlSupport.extractSlackResponseText(snapshot);
            if (paused) {
                if (response != null && !response.startsWith("_")) {
                    postMessageChunked(channelId, threadTs, response, null);
                }
                postMessage(channelId, threadTs, buildPauseNotice(conversationId), null);
            } else {
                postMessageChunked(channelId, threadTs, response, null);
            }
        } catch (IConversationService.ConversationAwaitingApprovalException e) {
            postMessage(channelId, threadTs, SlackHitlSupport.STILL_AWAITING_NOTICE, null);
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
    private String getOrCreateConversation(String agentId, String slackUserId,
                                           String intent)
            throws Exception {
        // Try existing — readUserConversation returns null when not found,
        // throws ResourceStoreException only on real DB errors (which should propagate)
        UserConversation existing = userConversationStore.readUserConversation(intent, slackUserId);
        if (existing != null) {
            return existing.getConversationId();
        }

        // Create new conversation
        var result = conversationService.startConversation(
                Deployment.Environment.production, agentId, slackUserId,
                Map.of("channelIntent", new Context(Context.ContextType.string, intent)));

        // Store mapping
        var mapping = new UserConversation(intent, slackUserId,
                Deployment.Environment.production, agentId, result.conversationId());
        try {
            userConversationStore.createUserConversation(mapping);
        } catch (IResourceStore.ResourceAlreadyExistsException e) {
            LOGGER.debugf("Race condition: conversation mapping already exists for %s/%s", sanitize(intent), sanitize(slackUserId));
        }

        return result.conversationId();
    }

    /**
     * Send a message to EDDI and wait synchronously for the response snapshot.
     * <p>
     * Throws {@link IConversationService.ConversationAwaitingApprovalException}
     * (synchronously, from {@code say}) when the conversation is already paused —
     * the caller must translate that into a "still awaiting" notice rather than a
     * generic error. When THIS turn pauses, {@code say} completes normally and the
     * returned snapshot carries state {@code AWAITING_HUMAN}.
     */
    private SimpleConversationMemorySnapshot sendAndWait(String conversationId, String message) throws Exception {
        var inputData = new InputData();
        inputData.setInput(message);
        inputData.setContext(Map.of("slack", new Context(Context.ContextType.string, "true")));

        var responseFuture = new CompletableFuture<SimpleConversationMemorySnapshot>();

        conversationService.say(conversationId, false, true, Collections.emptyList(), inputData, false, snapshot -> {
            if (snapshot != null) {
                responseFuture.complete(snapshot);
            } else {
                responseFuture.completeExceptionally(new RuntimeException("Agent returned null response"));
            }
        });

        return responseFuture.get(CONVERSATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

        for (int attempt = 1; attempt <= SLACK_API_MAX_RETRIES; attempt++) {
            try {
                slackApi.postMessage(auth, channelId, threadTs, text);
                return;
            } catch (SlackDeliveryException e) {
                if (attempt < SLACK_API_MAX_RETRIES) {
                    long backoff = SLACK_API_RETRY_BASE_MS * (1L << (attempt - 1));
                    LOGGER.warnf("Slack API call failed (attempt %d/%d), retrying in %dms: %s",
                            attempt, SLACK_API_MAX_RETRIES, backoff, e.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    LOGGER.errorf("SLACK_DELIVERY_FAILED | channel=%s | threadTs=%s | textLength=%d | attempts=%d | error=%s",
                            sanitize(channelId), sanitize(threadTs), text != null ? text.length() : 0,
                            SLACK_API_MAX_RETRIES, e.getMessage());
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
