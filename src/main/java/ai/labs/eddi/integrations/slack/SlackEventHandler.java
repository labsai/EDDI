package ai.labs.eddi.integrations.slack;

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
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core Slack event handler. Receives parsed events from
 * {@link ai.labs.eddi.integrations.slack.rest.RestSlackWebhook}, routes them to
 * the correct EDDI agent (via {@link SlackChannelRouter}), manages conversation
 * state (via {@link IUserConversationStore}), and posts responses back to Slack
 * (via {@link SlackWebApiClient}).
 * <p>
 * Key behaviors:
 * <ul>
 * <li>De-duplicates events by {@code event_id} (Slack retries up to 3x)</li>
 * <li>Filters out bot's own messages to prevent infinite loops</li>
 * <li>Strips bot mention prefix from message text</li>
 * <li>Detects {@code group:} prefix to trigger multi-agent group
 * discussions</li>
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

    /** Pattern for group discussion trigger: "group: question" */
    private static final Pattern GROUP_PREFIX = Pattern.compile("^group:\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final SlackIntegrationConfig config;
    private final SlackChannelRouter channelRouter;
    private final SlackWebApiClient slackApi;
    private final IConversationService conversationService;
    private final IGroupConversationService groupConversationService;
    private final IUserConversationStore userConversationStore;
    private final ICache<String, Boolean> eventDedup;
    private final ExecutorService executorService;

    /**
     * Tracks active group discussion listeners by channelId. Used to detect user
     * replies in agent threads for follow-up conversations. key = Slack message ts
     * of an agent's contribution → value = listener that has the context.
     */
    private final Map<String, SlackGroupDiscussionListener> activeGroupListeners = new ConcurrentHashMap<>();

    @Inject
    public SlackEventHandler(SlackIntegrationConfig config,
            SlackChannelRouter channelRouter,
            SlackWebApiClient slackApi,
            IConversationService conversationService,
            IGroupConversationService groupConversationService,
            IUserConversationStore userConversationStore,
            ICacheFactory cacheFactory) {
        this.config = config;
        this.channelRouter = channelRouter;
        this.slackApi = slackApi;
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.userConversationStore = userConversationStore;
        this.eventDedup = cacheFactory.getCache("slack-event-dedup");
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();

        // Startup validation
        if (config.enabled() && (config.botToken().isEmpty() || config.botToken().get().isBlank())) {
            LOGGER.error("Slack integration is ENABLED but eddi.slack.bot-token is NOT SET. "
                    + "Responses will fail to post to Slack!");
        }
    }

    /**
     * Handle an incoming Slack event asynchronously. Called from the webhook
     * endpoint after signature verification. Returns immediately — processing
     * happens on a virtual thread.
     *
     * @param eventId
     *            the unique event ID (for dedup)
     * @param eventType
     *            the event type (e.g., "app_mention", "message")
     * @param event
     *            the parsed event JSON as a Map
     */
    public void handleEventAsync(String eventId, String eventType, Map<String, Object> event) {
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
                handleEvent(eventType, event);
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

    private void handleEvent(String eventType, Map<String, Object> event) throws Exception {
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

        if (text.isBlank()) {
            postMessage(channelId, getThreadTs(event),
                    "👋 Hi! Send me a message and I'll respond.\n"
                            + "_Tip: Use `group: your question` to start a multi-agent discussion._");
            return;
        }

        String threadTs = getThreadTs(event);

        // 1. Check if this is a reply in an agent's thread (follow-up)
        String parentTs = (String) event.get("thread_ts");
        if (parentTs != null && tryHandleAgentFollowUp(parentTs, channelId, userId, text, threadTs)) {
            return;
        }

        // 2. Check for group discussion trigger: "group: question"
        Matcher groupMatcher = GROUP_PREFIX.matcher(text);
        if (groupMatcher.matches()) {
            handleGroupDiscussion(channelId, userId, groupMatcher.group(1).trim(), threadTs);
            return;
        }

        // 3. Standard 1:1 agent conversation
        handleAgentConversation(channelId, userId, text, threadTs);
    }

    /**
     * Handle a standard 1:1 agent conversation.
     */
    private void handleAgentConversation(String channelId, String userId,
                                         String text, String threadTs)
            throws Exception {
        Optional<String> agentIdOpt = channelRouter.resolveAgentId(channelId);
        if (agentIdOpt.isEmpty()) {
            LOGGER.warnf("No agent mapped for Slack channel %s", channelId);
            postMessage(channelId, threadTs,
                    "⚠️ No agent is configured for this channel. Ask an admin to set up a `ChannelConnector` with type `slack` and `channelId: "
                            + channelId + "`.");
            return;
        }

        String agentId = agentIdOpt.get();
        String conversationId = getOrCreateConversation(agentId, userId, channelId, threadTs);
        String response = sendAndWait(conversationId, text);
        postMessageChunked(channelId, threadTs, response);
    }

    // ─── Group Discussion ───

    /**
     * Handle a group discussion trigger. Resolves the group ID from the channel
     * config, creates a {@link SlackGroupDiscussionListener}, and starts the
     * discussion asynchronously.
     */
    private void handleGroupDiscussion(String channelId, String userId,
                                       String question, String threadTs) {
        Optional<String> groupIdOpt = channelRouter.resolveGroupId(channelId);
        if (groupIdOpt.isEmpty()) {
            postMessage(channelId, threadTs,
                    "⚠️ No group is configured for this channel.\n"
                            + "Set `eddi.slack.default-group-id` or add a ChannelConnector with `groupId`.");
            return;
        }

        String groupId = groupIdOpt.get();
        String token = "Bearer " + config.botToken().orElse("");

        // Create the listener that streams discussion into Slack
        var listener = new SlackGroupDiscussionListener(slackApi, token, channelId, threadTs);

        try {
            LOGGER.infof("Starting group discussion in channel %s, group %s, question: %s",
                    channelId, groupId, question.substring(0, Math.min(80, question.length())));

            groupConversationService.startAndDiscussAsync(groupId, question, userId, listener);

            // Track the listener for follow-up routing.
            // After async completes, the listener has agentId→ts mappings.
            // We use a snapshot approach: poll after a short delay to register mappings.
            executorService.submit(() -> registerAgentThreadMappings(listener, channelId));

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
    private void registerAgentThreadMappings(SlackGroupDiscussionListener listener, String channelId) {
        // Poll until the listener has agent mappings (discussion is async)
        // The discussion typically takes 10-60 seconds
        int maxWaitSeconds = 300;
        int waited = 0;
        while (waited < maxWaitSeconds) {
            try {
                Thread.sleep(2000);
                waited += 2;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!listener.getAgentMessageTsMap().isEmpty()) {
                break;
            }
        }

        // Register all agent message ts → listener for follow-up detection
        if (listener.isExpandedMode()) {
            for (String ts : listener.getAgentMessageTsMap().values()) {
                activeGroupListeners.put(ts, listener);
                LOGGER.debugf("Registered agent thread ts=%s for follow-up routing", ts);
            }
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

        // Get or create a follow-up conversation for this agent thread
        Optional<String> agentIdOpt = channelRouter.resolveAgentId(channelId);
        String resolvedAgentId = agentIdOpt.orElse(agentId);

        // Use the agent from the group discussion, not the channel default
        String conversationId = getOrCreateConversation(agentId, userId, channelId, parentTs);
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
     * {@link IUserConversationStore} with intent = "slack:{channelId}:{threadTs}"
     * and userId = slackUserId.
     */
    private String getOrCreateConversation(String agentId, String slackUserId,
                                           String channelId, String threadTs)
            throws Exception {
        // Use thread_ts for threaded conversations, channel for top-level
        String threadKey = threadTs != null ? threadTs : "main";
        String intent = "slack:" + channelId + ":" + threadKey;

        // Try existing — readUserConversation returns null when not found,
        // throws ResourceStoreException only on real DB errors (which should propagate)
        UserConversation existing = userConversationStore.readUserConversation(intent, slackUserId);
        if (existing != null) {
            return existing.getConversationId();
        }

        // Create new conversation
        var result = conversationService.startConversation(
                Deployment.Environment.production, agentId, slackUserId,
                Map.of("slackChannel", new Context(Context.ContextType.string, channelId),
                        "slackThread", new Context(Context.ContextType.string, threadKey)));

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
    @SuppressWarnings("unchecked")
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
            postMessage(channelId, threadTs, text.substring(offset, end));
            offset = end;
        }
    }

    /**
     * Post a single message to Slack via the Web API.
     */
    private void postMessage(String channelId, String threadTs, String text) {
        try {
            String token = config.botToken().orElse("");
            slackApi.postMessage("Bearer " + token, channelId, threadTs, text);
        } catch (Exception e) {
            LOGGER.errorf("Failed to post Slack message to channel %s: %s", channelId, e.getMessage());
        }
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
