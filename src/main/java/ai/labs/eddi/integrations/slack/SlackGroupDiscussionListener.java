/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import org.jboss.logging.Logger;

import java.util.List;
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
 * All discussion styles use <b>EXPANDED</b> mode: each agent's first
 * contribution is a channel-level header with a short preview, and the full
 * response lives in a thread reply. Peer feedback threads under the target
 * agent's message; revisions thread under the agent's own message.
 * <p>
 * Compact mode code paths remain as a fallback for styles not in
 * {@code EXPANDED_STYLES} (e.g. {@code CUSTOM}).
 *
 * @since 6.0.0
 */
public class SlackGroupDiscussionListener implements GroupDiscussionEventListener {

    private static final Logger LOGGER = Logger.getLogger(SlackGroupDiscussionListener.class);

    /**
     * Discussion styles that use EXPANDED mode (channel-level messages with peer
     * threading). All styles use expanded mode in Slack for readability — compact
     * mode (single thread) is too hard to follow with multiple agents.
     */
    private static final Set<String> EXPANDED_STYLES = Set.of(
            "ROUND_TABLE", "PEER_REVIEW", "DEVIL_ADVOCATE", "DEBATE", "DELPHI", "TASK_FORCE");

    private final SlackWebApiClient slackApi;
    private final String authToken;
    private final String channelId;
    private final String userThreadTs;

    /** Slack channel id for HITL approval notifications, or {@code null}. */
    private final String hitlApprovalChannel;
    /** Comma-separated approver Slack user ids, or {@code null}. */
    private final String hitlApproverUserIds;
    /**
     * Owning integration name — carried in the approval button value so a group
     * HITL decision binds to THIS integration at the interactivity endpoint. May be
     * {@code null} (e.g. no integration context), yielding a legacy bare value.
     */
    private final String integrationName;

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
        this(slackApi, authToken, channelId, userThreadTs, null, null, null);
    }

    /**
     * Constructor with HITL approval configuration. {@code hitlApprovalChannel} and
     * {@code hitlApproverUserIds} are read from the integration's
     * {@code platformConfig} — both may be {@code null} (no approval channel /
     * fail-closed no buttons).
     */
    public SlackGroupDiscussionListener(SlackWebApiClient slackApi, String authToken,
            String channelId, String userThreadTs,
            String hitlApprovalChannel, String hitlApproverUserIds) {
        this(slackApi, authToken, channelId, userThreadTs, hitlApprovalChannel, hitlApproverUserIds, null);
    }

    /**
     * Constructor carrying the owning integration name (for IDOR-safe approval
     * button values) in addition to the HITL approval configuration.
     */
    public SlackGroupDiscussionListener(SlackWebApiClient slackApi, String authToken,
            String channelId, String userThreadTs,
            String hitlApprovalChannel, String hitlApproverUserIds, String integrationName) {
        this.slackApi = slackApi;
        this.authToken = authToken;
        this.channelId = channelId;
        this.userThreadTs = userThreadTs;
        this.hitlApprovalChannel = hitlApprovalChannel;
        this.hitlApproverUserIds = hitlApproverUserIds;
        this.integrationName = integrationName;
    }

    @Override
    public void onGroupStart(GroupConversationEventSink.GroupStartEvent event) {
        this.groupQuestion = event.question();
        this.groupConversationId = event.groupConversationId();
        this.expandedMode = EXPANDED_STYLES.contains(event.style());

        String styleName = event.style().replace("_", " ").toLowerCase();
        String msg = String.format(
                "🗣️ *%s discussion started* — %d agents participating\n\n> _%s_",
                styleName, event.memberAgentIds().size(), event.question());

        // Always post the start message in the user's thread
        postSafe(channelId, userThreadTs, msg);
    }

    @Override
    public void onPhaseStart(GroupConversationEventSink.PhaseStartEvent event) {
        if (expandedMode) {
            // In expanded mode, phase transitions are implicit from the message flow
            return;
        }
        // In compact mode, add a phase separator in the thread
        String separator = String.format("─── *%s* ───", event.phaseName());
        postSafe(channelId, userThreadTs, separator);
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
            postSafe(channelId, userThreadTs, msg);
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
        // No separator needed — the synthesis header stands out on its own
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
                postSafe(channelId, null, msg);
            } else {
                postSafe(channelId, userThreadTs, msg);
            }
        } finally {
            completionLatch.countDown();
        }
    }

    @Override
    public void onTaskPlanCreated(GroupConversationEventSink.TaskPlanCreatedEvent event) {
        List<GroupConversationEventSink.TaskSummary> tasks = event.tasks();
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        var sb = new StringBuilder();
        sb.append(event.preConfigured()
                ? "📝 *Task plan loaded* (pre-configured)\n"
                : "📝 *Task plan created*\n");

        for (int i = 0; i < tasks.size(); i++) {
            var task = tasks.get(i);
            sb.append(String.format("%d. *%s*", i + 1, task.subject()));
            if (task.assignedTo() != null && !task.assignedTo().isBlank()) {
                sb.append(String.format(" — assigned to _%s_", task.assignedTo()));
            }
            if (task.priority() > 0) {
                sb.append(String.format(" [P%d]", task.priority()));
            }
            sb.append('\n');
        }

        String threadTs = expandedMode ? null : userThreadTs;
        postSafe(channelId, threadTs, sb.toString().stripTrailing());
    }

    @Override
    public void onTaskVerified(GroupConversationEventSink.TaskVerifiedEvent event) {
        String emoji = event.passed() ? "✅" : "❌";
        String status = event.passed() ? "passed" : "failed";

        var sb = new StringBuilder();
        sb.append(String.format("%s *Task %s* — %s\n", emoji, event.taskSubject(), status));

        if (event.feedback() != null && !event.feedback().isBlank()) {
            sb.append(String.format("> %s\n", event.feedback().replace("\n", "\n> ")));
        }

        String threadTs = expandedMode ? null : userThreadTs;
        postSafe(channelId, threadTs, sb.toString().stripTrailing());
    }

    // ─── HITL (human-in-the-loop) ───

    @Override
    public void onHitlPause(GroupConversationEventSink.HitlPauseEvent event) {
        try {
            // In-thread notice so the discussion participants know it's blocked.
            String reason = event.reason() != null && !event.reason().isBlank()
                    ? "\n> " + event.reason()
                    : "";
            postSafe(channelId, userThreadTs, "⏸️ *Discussion awaiting approval*" + reason);

            // Optional interactive approval notification with buttons. The action value
            // carries "group:<groupConversationId>" so the interactivity handler routes
            // it to resumeDiscussion. Fail-closed: no approver list → no buttons.
            if (hitlApprovalChannel == null || hitlApprovalChannel.isBlank() || groupConversationId == null) {
                return;
            }
            boolean includeButtons = !SlackHitlSupport.parseApproverUserIds(hitlApproverUserIds).isEmpty();
            String phase = event.phaseName() != null ? event.phaseName() : ("phase " + event.phaseIndex());
            // The button value carries the owning integration name so the group
            // decision is bound to THIS integration at the interactivity endpoint.
            String actionValue = SlackHitlSupport.buildActionValue(integrationName,
                    SlackHitlSupport.GROUP_VALUE_PREFIX + groupConversationId);
            var blocks = SlackHitlSupport.buildApprovalBlocks(
                    "⏸️ Discussion awaiting approval", "Discussion", groupConversationId,
                    phase, event.reason(), null,
                    actionValue, includeButtons);
            String fallback = "Group discussion " + groupConversationId + " is awaiting human approval.";
            try {
                slackApi.postBlocksMessage(authToken, hitlApprovalChannel, null, blocks, fallback);
            } catch (SlackDeliveryException e) {
                LOGGER.warnf("Failed to post group HITL approval notification for %s: %s",
                        groupConversationId, e.getMessage());
            }
        } finally {
            // A HITL pause is TERMINAL for this listener's lifecycle: the discussion
            // suspends here and any resume runs through a DIFFERENT listener instance,
            // so this one will never receive onGroupComplete/onCancelled. Release the
            // completion latch now — otherwise registerAgentThreadMappings() parks for
            // the full awaitCompletion timeout on every paused expanded-mode discussion
            // (leaking a virtual thread), and follow-up routing for the agents that
            // already spoke stays unregistered for that whole window.
            completionLatch.countDown();
        }
    }

    @Override
    public void onHitlResume(GroupConversationEventSink.HitlResumeEvent event) {
        String verdict = event.verdict() != null ? event.verdict() : "resolved";
        String who = event.decidedBy() != null ? " by " + event.decidedBy() : "";
        String emoji = "APPROVED".equalsIgnoreCase(verdict)
                ? "✅"
                : ("REJECTED".equalsIgnoreCase(verdict) ? "⛔" : "ℹ️");
        var sb = new StringBuilder();
        sb.append(String.format("%s *Discussion %s*%s", emoji, verdict.toLowerCase(), who));
        if (event.note() != null && !event.note().isBlank()) {
            sb.append("\n> ").append(event.note());
        }
        postSafe(channelId, userThreadTs, sb.toString());
    }

    @Override
    public void onMemberPauseSkipped(GroupConversationEventSink.MemberPauseSkippedEvent event) {
        String displayName = event.displayName() != null ? event.displayName() : event.agentId();
        String reason = event.reason() != null && !event.reason().isBlank()
                ? " (" + event.reason() + ")"
                : "";
        postSafe(channelId, userThreadTs, String.format(
                "⚠️ *%s* requested human approval mid-turn — not supported inside a group discussion; "
                        + "its turn was skipped%s.",
                displayName, reason));
    }

    @Override
    public void onCancelled(GroupConversationEventSink.CancelledEvent event) {
        try {
            String who = event.cancelledBy() != null ? " by " + event.cancelledBy() : "";
            String reason = event.reason() != null && !event.reason().isBlank()
                    ? " — " + event.reason()
                    : "";
            postSafe(channelId, userThreadTs, "🛑 *Discussion cancelled*" + who + reason);
        } finally {
            completionLatch.countDown();
        }
    }

    // ─── Posting strategies ───

    /**
     * Post an agent's first contribution as a channel-level header with the full
     * response as a thread reply (EXPANDED mode). This prevents long agent
     * responses from flooding the channel — the header shows agent name and a brief
     * preview, while the full content lives in the thread.
     */
    private void postPrimaryContribution(GroupConversationEventSink.SpeakerCompleteEvent event) {
        String displayName = event.displayName() != null ? event.displayName() : event.agentId();
        String response = event.response();

        // Build a short preview for the channel-level header (first meaningful line,
        // truncated)
        String preview = buildPreview(response, 150);
        String header = String.format("🟢 *%s*\n_%s_", displayName, preview);

        String ts = postSafe(channelId, null, header);
        if (ts != null) {
            agentMessageTs.put(event.agentId(), ts);
            messageTsToAgentId.put(ts, event.agentId());

            // Post the full response as a thread reply
            postSafe(channelId, ts, response);
            LOGGER.debugf("Tracked agent %s message ts=%s", event.agentId(), ts);
        }
    }

    /**
     * Build a short preview from a response — first non-empty, non-heading line,
     * truncated to maxLength.
     */
    private static String buildPreview(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "…";
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            // Skip empty lines, headings, separators, code fences
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("```")
                    || trimmed.matches("^[-─═*_]{3,}$")) {
                continue;
            }
            // Strip markdown bold for cleaner preview
            trimmed = trimmed.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
            if (trimmed.length() > maxLength) {
                return trimmed.substring(0, maxLength) + "…";
            }
            return trimmed;
        }
        return text.substring(0, Math.min(text.length(), maxLength)) + "…";
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
            postSafe(channelId, targetTs, msg);
        } else {
            // Fallback: post in user's thread with label
            LOGGER.debugf("No ts for target agent %s, falling back to user thread", event.targetAgentId());
            postSafe(channelId, userThreadTs, msg);
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
            postSafe(channelId, ownTs, msg);
        } else {
            postSafe(channelId, userThreadTs, msg);
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
     * Post the synthesis — prominent header at channel level with full content in
     * thread. The synthesis is the final deliverable of the discussion.
     */
    private void postSynthesis(String displayName, String response) {
        synthesisPosted = true;
        isSynthesisPhase = false;

        String preview = buildPreview(response, 200);
        String header = String.format("📋 *Panel Synthesis* (by %s)\n_%s_", displayName, preview);

        if (expandedMode) {
            String ts = postSafe(channelId, null, header);
            if (ts != null) {
                // Full synthesis in thread
                postSafe(channelId, ts, response);
            }
        } else {
            postSafe(channelId, userThreadTs, header);
            postSafe(channelId, userThreadTs, response);
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

    /**
     * Fire-and-forget Slack post. Catches {@link SlackDeliveryException} so that a
     * transient Slack API failure does not abort the entire group discussion.
     *
     * @return the message ts on success, or null on any failure
     */
    private String postSafe(String channel, String threadTs, String text) {
        try {
            return slackApi.postMessage(authToken, channel, threadTs, text);
        } catch (SlackDeliveryException e) {
            LOGGER.warnf("Slack post failed (channel=%s, thread=%s): %s",
                    channel, threadTs, e.getMessage());
            return null;
        }
    }
}
