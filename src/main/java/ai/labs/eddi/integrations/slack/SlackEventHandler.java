package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
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

    private final SlackIntegrationConfig config;
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
    public SlackEventHandler(SlackIntegrationConfig config,
            ChannelTargetRouter channelTargetRouter,
            SlackWebApiClient slackApi,
            IConversationService conversationService,
            IGroupConversationService groupConversationService,
            IUserConversationStore userConversationStore,
            ICacheFactory cacheFactory) {
        this.config = config;
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
        if (!config.enabled()) {
            return;
        }

        // De-duplicate: Slack retries events up to 3 times
        if (eventDedup.get(eventId) != null) {
            LOGGER.debugf("Duplicate Slack event %s — skipping", eventId);
            return;
        }
        eventDedup.put(eventId, Boolean.TRUE);

        executorService.submit(() -> {
            try {
                handleEvent(event);
            } catch (Exception e) {
                LOGGER.errorf(e, "Error handling Slack event %s", eventId);

                // Best-effort error response to user (never leak internal details)
                String channelId = (String) event.get("channel");
                String threadTs = getThreadTs(event);
                if (channelId != null) {
                    try {
                        postMessage(channelId, threadTs,
                                "⚠️ Sorry, I encountered an error processing your message. Please try again.");
                    } catch (Exception ignored) {
                        // Can't post error — nothing more we can do
                    }
                }
            }
        });
    }

    private void handleEvent(Map<String, Object> event) throws Exception {
        // Filter bot's own messages (prevent infinite loop)
        if (event.containsKey("bot_id") || "bot_message".equals(event.get("subtype"))) {
            LOGGER.debugf("Ignoring bot message in channel %s", event.get("channel"));
            return;
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
            postHelp(channelId, threadTs);
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

        if (resolved == null) {
            postHelp(channelId, threadTs);
            return;
        }

        // Lock target for this thread
        if (threadTs != null) {
            channelTargetRouter.lockThreadTarget("slack", channelId, threadTs, resolved.target());
        }

        // Store resolved target for credential resolution in postMessage
        currentResolvedTarget.set(resolved);
        try {
            switch (resolved.target().getType()) {
                case AGENT -> handleAgentConversation(resolved, channelId, userId, threadTs, text);
                case GROUP -> handleGroupDiscussion(resolved, channelId, userId, threadTs, text);
            }
        } finally {
            currentResolvedTarget.remove();
        }
    }

    /**
     * Handle a standard 1:1 agent conversation routed via ChannelTargetRouter.
     */
    private void handleAgentConversation(ResolvedTarget resolved, String channelId,
                                         String userId, String threadTs, String originalText)
            throws Exception {
        String agentId = resolved.target().getTargetId();
        String targetName = resolved.target().getName();
        String threadKey = threadTs != null ? threadTs : "main";

        // Compose intent key for conversation tracking
        String integrationId = resolved.integration() != null
                ? resolved.integration().getName()
                : "legacy";
        String intent = "channel:" + integrationId + ":" + targetName + ":" + threadKey;

        // Use strippedMessage (trigger keyword removed) or fall back to original text
        // (thread replies from resolveThreadTarget have strippedMessage=null)
        String message = resolved.strippedMessage() != null ? resolved.strippedMessage() : originalText;

        String conversationId = getOrCreateConversation(agentId, userId, intent);
        String response = sendAndWait(conversationId, message);
        postMessageChunked(channelId, threadTs, response);
    }

    // ─── Group Discussion ───

    /**
     * Handle a group discussion trigger routed via ChannelTargetRouter.
     */
    private void handleGroupDiscussion(ResolvedTarget resolved, String channelId,
                                       String userId, String threadTs, String originalText) {
        String groupId = resolved.target().getTargetId();
        String botToken = resolved.botToken();

        if (botToken == null || botToken.isEmpty()) {
            LOGGER.errorf("No bot token configured for Slack channel %s — cannot run group discussion.", channelId);
            return;
        }

        String token = "Bearer " + botToken;

        // Create the listener that streams discussion into Slack
        var listener = new SlackGroupDiscussionListener(slackApi, token, channelId, threadTs);

        String question = resolved.strippedMessage() != null ? resolved.strippedMessage() : originalText;
        try {
            LOGGER.infof("Starting group discussion in channel %s, group %s, question: %s",
                    channelId, groupId, question.substring(0, Math.min(80, question.length())));

            groupConversationService.startAndDiscussAsync(groupId, question, userId, listener);

            // Only register for follow-up routing in expanded mode (compact has no
            // channel-level messages)
            if (listener.isExpandedMode()) {
                executorService.submit(() -> registerAgentThreadMappings(listener));
            }

        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to start group discussion: %s", e.getMessage());
            postMessage(channelId, threadTs,
                    "⚠️ Failed to start group discussion. Please try again.");
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
                ctx.displayName(), userId, text.substring(0, Math.min(60, text.length())));

        // Build context-enriched input
        String enrichedInput = buildFollowUpInput(ctx, text);

        // Route to the specific agent from the group discussion
        String intent = "channel:followup:" + channelId + ":" + parentTs;
        String conversationId = getOrCreateConversation(agentId, userId, intent);
        String response = sendAndWait(conversationId, enrichedInput);
        postMessageChunked(channelId, threadTs, response);

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
            LOGGER.debugf("Race condition: conversation mapping already exists for %s/%s", intent, slackUserId);
        }

        return result.conversationId();
    }

    /**
     * Send a message to EDDI and wait synchronously for the response.
     */
    private String sendAndWait(String conversationId, String message) throws Exception {
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

        var snapshot = responseFuture.get(CONVERSATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return extractResponseText(snapshot);
    }

    /**
     * Extract the text response from a conversation snapshot.
     */
    private String extractResponseText(SimpleConversationMemorySnapshot snapshot) {
        var outputs = snapshot.getConversationOutputs();
        if (outputs == null || outputs.isEmpty()) {
            return "_No response from agent._";
        }

        var lastOutput = outputs.get(outputs.size() - 1);
        var outputItems = lastOutput.get("output");
        if (outputItems instanceof List<?> items) {
            var texts = new ArrayList<String>();
            for (var item : items) {
                if (item instanceof Map<?, ?> map && map.containsKey("text")) {
                    texts.add(String.valueOf(map.get("text")));
                }
            }
            if (!texts.isEmpty()) {
                return String.join("\n", texts);
            }
        }

        return "_Agent completed but produced no text output._";
    }

    /**
     * Post a message to Slack, chunking if it exceeds Slack's 4000-char limit.
     */
    private void postMessageChunked(String channelId, String threadTs, String text) {
        if (text.length() <= MAX_SLACK_MESSAGE_LENGTH) {
            postMessage(channelId, threadTs, text);
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
            postMessage(channelId, threadTs, text.substring(offset, end));
            offset = end;
        }
    }

    /**
     * Thread-local storage for the current resolved target. Used by postMessage to
     * resolve the bot token without passing it through every method.
     */
    private final ThreadLocal<ResolvedTarget> currentResolvedTarget = new ThreadLocal<>();

    /**
     * Post a single message to Slack via the Web API, using the bot token from the
     * current resolved target or from the channel target router.
     */
    private void postMessage(String channelId, String threadTs, String text) {
        // Resolve bot token: prefer current resolved target, fallback to router
        String botToken = null;
        ResolvedTarget resolved = currentResolvedTarget.get();
        if (resolved != null) {
            botToken = resolved.botToken();
        }
        if (botToken == null || botToken.isEmpty()) {
            botToken = channelTargetRouter.getBotToken("slack", channelId);
        }

        if (botToken == null || botToken.isEmpty()) {
            LOGGER.warnf("No bot token configured for Slack channel %s — cannot post message", channelId);
            return;
        }

        String auth = "Bearer " + botToken;

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
                            channelId, threadTs, text != null ? text.length() : 0,
                            SLACK_API_MAX_RETRIES, e.getMessage());
                }
            }
        }
    }

    /**
     * Post a help message listing available targets for this channel.
     */
    private void postHelp(String channelId, String threadTs) {
        var integration = channelTargetRouter.getIntegration("slack", channelId);
        if (integration.isEmpty()) {
            postMessage(channelId, threadTs,
                    "👋 Hi! Send me a message and I'll respond.");
            return;
        }

        var config = integration.get();
        var sb = new StringBuilder();
        sb.append("👋 *Available targets in this channel:*\n\n");

        for (ChannelTarget target : config.getTargets()) {
            String name = target.getName() != null ? target.getName() : "(unnamed)";
            String type = target.getType() == ChannelTarget.TargetType.GROUP ? "group" : "agent";
            String isDefault = name.equalsIgnoreCase(config.getDefaultTargetName())
                    ? " _(default)_"
                    : "";
            sb.append("• *").append(name).append("*").append(isDefault);
            sb.append(" [").append(type).append("]\n");
            if (target.getTriggers() != null && !target.getTriggers().isEmpty()) {
                sb.append("  Triggers: ");
                sb.append(String.join(", ", target.getTriggers().stream()
                        .map(t -> "`" + t + ":`")
                        .toList()));
                sb.append("\n");
            }
        }

        sb.append("\n_Type a message to talk to the default target, or use a trigger keyword._");
        postMessage(channelId, threadTs, sb.toString());
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
