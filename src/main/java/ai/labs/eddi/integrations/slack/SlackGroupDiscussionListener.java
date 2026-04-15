package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Streams a multi-agent group discussion into a Slack channel in real-time.
 * Implements {@link GroupDiscussionEventListener} to receive callbacks as
 * agents speak, and posts each contribution to Slack.
 * <p>
 * Two UX modes based on discussion style:
 * <ul>
 * <li><b>COMPACT</b> (ROUND_TABLE, DELPHI) — all messages in a single thread
 * under the user's original message. Clean and contained.</li>
 * <li><b>EXPANDED</b> (PEER_REVIEW, DEVIL_ADVOCATE, DEBATE) — each agent's
 * primary contribution is a channel-level message. Peer feedback is posted as a
 * thread reply under the target agent's message. Revisions thread under the
 * agent's own original message.</li>
 * </ul>
 *
 * @since 6.0.0
 */
public class SlackGroupDiscussionListener implements GroupDiscussionEventListener {

    private static final Logger LOGGER = Logger.getLogger(SlackGroupDiscussionListener.class);

    /**
     * Discussion styles that use EXPANDED mode (channel-level messages with peer
     * threading).
     */
    private static final Set<String> EXPANDED_STYLES = Set.of("PEER_REVIEW", "DEVIL_ADVOCATE", "DEBATE");

    private final SlackWebApiClient slackApi;
    private final String authToken;
    private final String channelId;
    private final String userThreadTs;

    /** agentId → Slack message ts of their first contribution (for threading). */
    private final Map<String, String> agentMessageTs = new ConcurrentHashMap<>();

    /** Reverse map: Slack message ts → agentId (O(1) lookup for follow-ups). */
    private final Map<String, String> messageTsToAgentId = new ConcurrentHashMap<>();

    /** agentId → group discussion context (for follow-up conversations). */
    private final Map<String, AgentContext> agentContexts = new ConcurrentHashMap<>();

    /** Signals when the group discussion is fully complete. */
    private final CountDownLatch completionLatch = new CountDownLatch(1);

    private volatile boolean expandedMode = false;
    private volatile boolean isSynthesisPhase = false;
    private volatile boolean synthesisPosted = false;
    private volatile String groupQuestion;
    private volatile String groupConversationId;

    /**
     * Context snapshot for an agent's participation — used for follow-up
     * conversations when a user replies in an agent's thread.
     */
    public record AgentContext(
            String agentId,
            String displayName,
            String contribution,
            String feedbackReceived,
            String groupQuestion,
            String groupConversationId) {
    }

    public SlackGroupDiscussionListener(SlackWebApiClient slackApi, String authToken,
            String channelId, String userThreadTs) {
        this.slackApi = slackApi;
        this.authToken = authToken;
        this.channelId = channelId;
        this.userThreadTs = userThreadTs;
    }

    @Override
    public void onGroupStart(GroupConversationEventSink.GroupStartEvent event) {
        this.groupQuestion = event.question();
        this.groupConversationId = event.groupConversationId();
        this.expandedMode = EXPANDED_STYLES.contains(event.style());

        String modeLabel = expandedMode ? "threaded" : "compact";
        String msg = String.format("🗣️ *Starting %s discussion* (%s mode, %d agents)\n_%s_",
                event.style().replace("_", " "), modeLabel,
                event.memberAgentIds().size(), event.question());

        // Always post the start message in the user's thread
        slackApi.postMessage(authToken, channelId, userThreadTs, msg);
    }

    @Override
    public void onPhaseStart(GroupConversationEventSink.PhaseStartEvent event) {
        if (expandedMode) {
            // In expanded mode, phase transitions are implicit from the message flow
            return;
        }
        // In compact mode, add a phase separator in the thread
        String separator = String.format("─── *%s* ───", event.phaseName());
        slackApi.postMessage(authToken, channelId, userThreadTs, separator);
    }

    @Override
    public void onSpeakerComplete(GroupConversationEventSink.SpeakerCompleteEvent event) {
        String agentId = event.agentId();
        String displayName = event.displayName() != null ? event.displayName() : agentId;
        String response = event.response();

        if (response == null || response.isBlank()) {
            return;
        }

        if (isSynthesisPhase) {
            // Synthesis — always prominent
            postSynthesis(displayName, response);
            return;
        }

        if (!expandedMode) {
            // COMPACT mode: everything in the user's thread
            String msg = formatContribution(displayName, response, event.targetAgentId(),
                    event.targetDisplayName());
            slackApi.postMessage(authToken, channelId, userThreadTs, msg);
            trackAgentContext(event);
            return;
        }

        // EXPANDED mode: routing logic
        if (event.targetAgentId() != null) {
            // Peer feedback — thread under target agent's channel message
            postPeerFeedback(event);
        } else if (agentMessageTs.containsKey(agentId)) {
            // Agent has spoken before — revision — thread under own message
            postRevision(event);
        } else {
            // First contribution — channel-level message
            postPrimaryContribution(event);
        }

        trackAgentContext(event);
    }

    @Override
    public void onSynthesisStart(GroupConversationEventSink.SynthesisStartEvent event) {
        isSynthesisPhase = true;
        if (expandedMode) {
            // Visual separator before synthesis in the channel
            slackApi.postMessage(authToken, channelId, null, "───────────────────────────");
        }
    }

    @Override
    public void onGroupComplete(GroupConversationEventSink.GroupCompleteEvent event) {
        try {
            isSynthesisPhase = false;
            // Fallback: if synthesis was delivered in onGroupComplete but not via
            // onSpeakerComplete
            if (!synthesisPosted && event.synthesizedAnswer() != null && !event.synthesizedAnswer().isBlank()) {
                postSynthesis("Moderator", event.synthesizedAnswer());
            }
            LOGGER.debugf("Group discussion complete: %s", event.state());
        } finally {
            completionLatch.countDown();
        }
    }

    @Override
    public void onGroupError(GroupConversationEventSink.GroupErrorEvent event) {
        try {
            isSynthesisPhase = false;
            String msg = "⚠️ Group discussion encountered an error. Please try again.";
            if (expandedMode) {
                slackApi.postMessage(authToken, channelId, null, msg);
            } else {
                slackApi.postMessage(authToken, channelId, userThreadTs, msg);
            }
        } finally {
            completionLatch.countDown();
        }
    }

    // ─── Posting strategies ───

    /**
     * Post an agent's first contribution as a channel-level message (EXPANDED
     * mode). Saves the message ts for future threading.
     */
    private void postPrimaryContribution(GroupConversationEventSink.SpeakerCompleteEvent event) {
        String displayName = event.displayName() != null ? event.displayName() : event.agentId();
        String msg = String.format("🟢 *%s*\n%s", displayName, event.response());

        String ts = slackApi.postMessage(authToken, channelId, null, msg);
        if (ts != null) {
            agentMessageTs.put(event.agentId(), ts);
            messageTsToAgentId.put(ts, event.agentId());
            LOGGER.debugf("Tracked agent %s message ts=%s", event.agentId(), ts);
        }
    }

    /**
     * Post peer feedback as a thread reply under the target agent's channel
     * message.
     */
    private void postPeerFeedback(GroupConversationEventSink.SpeakerCompleteEvent event) {
        String targetTs = agentMessageTs.get(event.targetAgentId());
        String displayName = event.displayName() != null ? event.displayName() : event.agentId();
        String targetName = event.targetDisplayName() != null ? event.targetDisplayName() : event.targetAgentId();

        String msg = String.format("💬 *%s* → *%s*\n%s", displayName, targetName, event.response());

        if (targetTs != null) {
            // Thread under the target agent's message
            slackApi.postMessage(authToken, channelId, targetTs, msg);
        } else {
            // Fallback: post in user's thread with label
            LOGGER.debugf("No ts for target agent %s, falling back to user thread", event.targetAgentId());
            slackApi.postMessage(authToken, channelId, userThreadTs, msg);
        }

        // Track feedback for follow-up context
        appendFeedbackToContext(event.targetAgentId(), displayName, event.response());
    }

    /**
     * Post a revision as a thread reply under the agent's own original message.
     */
    private void postRevision(GroupConversationEventSink.SpeakerCompleteEvent event) {
        String ownTs = agentMessageTs.get(event.agentId());
        String displayName = event.displayName() != null ? event.displayName() : event.agentId();
        String msg = String.format("🔄 *%s (revised)*\n%s", displayName, event.response());

        if (ownTs != null) {
            slackApi.postMessage(authToken, channelId, ownTs, msg);
        } else {
            slackApi.postMessage(authToken, channelId, userThreadTs, msg);
        }

        // Update the agent's contribution in context
        var existing = agentContexts.get(event.agentId());
        if (existing != null) {
            agentContexts.put(event.agentId(), new AgentContext(
                    existing.agentId(), existing.displayName(),
                    event.response(), // updated contribution
                    existing.feedbackReceived(),
                    existing.groupQuestion(), existing.groupConversationId()));
        }
    }

    /**
     * Post the synthesis — always prominent and visible.
     */
    private void postSynthesis(String displayName, String response) {
        synthesisPosted = true;
        isSynthesisPhase = false;
        String msg = String.format("📋 *Synthesis* (by %s)\n%s", displayName, response);

        if (expandedMode) {
            slackApi.postMessage(authToken, channelId, null, msg);
        } else {
            slackApi.postMessage(authToken, channelId, userThreadTs, msg);
        }
    }

    // ─── Formatting helpers ───

    /**
     * Format a contribution for COMPACT mode (all in one thread).
     */
    private String formatContribution(String displayName, String response,
                                      String targetAgentId, String targetDisplayName) {
        if (targetAgentId != null) {
            String targetName = targetDisplayName != null ? targetDisplayName : targetAgentId;
            return String.format("💬 *%s* → *%s*\n%s", displayName, targetName, response);
        }
        return String.format("🟢 *%s*\n%s", displayName, response);
    }

    // ─── Context tracking for follow-ups ───

    /**
     * Track an agent's context for potential follow-up conversations.
     */
    private void trackAgentContext(GroupConversationEventSink.SpeakerCompleteEvent event) {
        if (event.targetAgentId() != null) {
            // This is feedback, not a primary contribution — tracked via
            // appendFeedbackToContext
            return;
        }

        agentContexts.put(event.agentId(), new AgentContext(
                event.agentId(),
                event.displayName() != null ? event.displayName() : event.agentId(),
                event.response(),
                "", // no feedback yet
                groupQuestion,
                groupConversationId));
    }

    /**
     * Append feedback to a target agent's context (for follow-up awareness).
     */
    private void appendFeedbackToContext(String targetAgentId, String fromName, String feedback) {
        var existing = agentContexts.get(targetAgentId);
        if (existing != null) {
            String existingFeedback = existing.feedbackReceived();
            String combined = existingFeedback.isEmpty()
                    ? String.format("From %s: %s", fromName, feedback)
                    : existingFeedback + "\n" + String.format("From %s: %s", fromName, feedback);

            agentContexts.put(targetAgentId, new AgentContext(
                    existing.agentId(), existing.displayName(),
                    existing.contribution(), combined,
                    existing.groupQuestion(), existing.groupConversationId()));
        }
    }

    // ─── Accessors ───

    /** Get the message ts map — used by SlackEventHandler for follow-up routing. */
    public Map<String, String> getAgentMessageTsMap() {
        return Map.copyOf(agentMessageTs);
    }

    /** Get the context for an agent — used for follow-up conversations. */
    public AgentContext getAgentContext(String agentId) {
        return agentContexts.get(agentId);
    }

    /** Get the agentId for a given Slack message ts. O(1) via reverse map. */
    public String getAgentIdForMessageTs(String messageTs) {
        return messageTsToAgentId.get(messageTs);
    }

    /** Whether this discussion used expanded (channel-level) mode. */
    public boolean isExpandedMode() {
        return expandedMode;
    }

    /**
     * Block until the discussion completes (onGroupComplete or onGroupError). Used
     * by SlackEventHandler to wait before registering follow-up mappings.
     *
     * @return true if completed within timeout, false if timed out
     */
    public boolean awaitCompletion(long timeout, TimeUnit unit) {
        try {
            return completionLatch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
