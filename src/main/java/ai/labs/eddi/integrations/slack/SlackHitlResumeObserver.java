/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Slack-side observer of {@link HitlResumeCompletedEvent}. When a paused
 * conversation resumes to a non-paused state, this pushes the outcome (verdict
 * + continuation output) to the Slack thread the conversation originated from —
 * so the chat user sees the result without polling.
 * <p>
 * The conversation is resolved back to its Slack routing via
 * {@link IUserConversationStore#readUserConversationByConversationId(String)}.
 * Two intent shapes route to Slack:
 * <ul>
 * <li>{@code channel:slack:{channelId}:{agentId}:{threadKey}} — 1:1 agent
 * conversations ("main" = no thread)</li>
 * <li>{@code channel:followup:{channelId}:{parentTs}} — agent-thread follow-ups
 * from a group discussion (the parentTs is the thread)</li>
 * </ul>
 * If the conversation is not Slack-routed, this is a no-op.
 * <p>
 * Timeout and cancellation outcomes flow through the same event: a verdict-less
 * event ({@code verdict == null}, terminal state
 * {@code EXECUTION_INTERRUPTED}/{@code ENDED}) is rendered as "approval
 * cancelled/expired", and a resumed turn that ended in {@code ERROR} is
 * rendered as a failure rather than "continuing". The observer is async and
 * best-effort — its failures never affect the engine.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class SlackHitlResumeObserver {

    private static final Logger LOGGER = Logger.getLogger(SlackHitlResumeObserver.class);
    private static final String SLACK_INTENT_PREFIX = "channel:slack:";
    private static final String FOLLOWUP_INTENT_PREFIX = "channel:followup:";
    private static final String THREAD_KEY_MAIN = "main";

    private final IUserConversationStore userConversationStore;
    private final ChannelTargetRouter channelTargetRouter;
    private final SlackWebApiClient slackApi;

    @Inject
    public SlackHitlResumeObserver(IUserConversationStore userConversationStore,
            ChannelTargetRouter channelTargetRouter,
            SlackWebApiClient slackApi) {
        this.userConversationStore = userConversationStore;
        this.channelTargetRouter = channelTargetRouter;
        this.slackApi = slackApi;
    }

    public void onResumeCompleted(@ObservesAsync HitlResumeCompletedEvent event) {
        try {
            deliver(event);
        } catch (Exception e) {
            // Best-effort: never let a delivery failure escape the observer.
            LOGGER.warnf("Failed to deliver HITL resume outcome to Slack for %s: %s",
                    sanitize(event.conversationId()), e.getMessage());
        }
    }

    private void deliver(HitlResumeCompletedEvent event) throws Exception {
        String conversationId = event.conversationId();
        UserConversation mapping = userConversationStore.readUserConversationByConversationId(conversationId);
        if (mapping == null || mapping.getIntent() == null) {
            return;
        }
        String intent = mapping.getIntent();
        if (!intent.startsWith(SLACK_INTENT_PREFIX) && !intent.startsWith(FOLLOWUP_INTENT_PREFIX)) {
            // Not a Slack-routed conversation — nothing to do.
            return;
        }

        SlackRoute route = parseIntent(intent);
        if (route == null) {
            LOGGER.debugf("Could not parse Slack intent for HITL resume delivery: %s", sanitize(intent));
            return;
        }

        String botToken = channelTargetRouter.getBotToken("slack", route.channelId());
        if (botToken == null || botToken.isBlank()) {
            LOGGER.debugf("No bot token for Slack channel %s — cannot deliver HITL outcome", sanitize(route.channelId()));
            return;
        }
        String auth = "Bearer " + botToken;

        String summary = decisionSummary(event.verdict(), event.decidedBy(), event.snapshot());

        var sb = new StringBuilder(summary);
        // Append genuine agent continuation only when the resume actually continued
        // (APPROVED and not ERROR). A rejection/cancellation/failure carries no
        // continuation to show; placeholders (leading "_") are always suppressed.
        if (event.verdict() == HitlVerdict.APPROVED && !isError(event.snapshot())) {
            String continuation = SlackHitlSupport.extractSlackResponseText(event.snapshot());
            if (continuation != null && !continuation.startsWith("_")) {
                sb.append("\n\n").append(continuation);
            }
        }

        postSafe(auth, route.channelId(), route.threadTs(), sb.toString());
    }

    private static boolean isError(SimpleConversationMemorySnapshot snapshot) {
        return snapshot != null && snapshot.getConversationState() == ConversationState.ERROR;
    }

    /**
     * Build a human-readable one-liner for the resume outcome. Distinguishes
     * automated outcomes ({@code system:*}) from human ones, and renders the
     * post-resume state:
     * <ul>
     * <li>{@code ERROR} — the approved continuation failed (config drift etc.);
     * never claim "continuing"</li>
     * <li>{@code null} verdict / terminal state (cancel / timeout-abort / end) —
     * the pending approval was cancelled or expired</li>
     * <li>APPROVED / REJECTED — the normal decision variants</li>
     * </ul>
     */
    static String decisionSummary(HitlVerdict verdict, String decidedBy,
                                  SimpleConversationMemorySnapshot snapshot) {
        boolean automated = decidedBy != null && decidedBy.startsWith("system:");
        String who = automated
                ? "(" + decidedBy + ")"
                : (decidedBy != null ? "by " + slackMention(decidedBy) : "");

        // A resumed turn that failed — do NOT render the verdict as "continuing".
        if (isError(snapshot)) {
            return ("⚠️ Approval processed " + who).trim()
                    + " — but the continuation failed. Please check the agent configuration or logs.";
        }

        if (verdict == HitlVerdict.REJECTED) {
            return ("⛔ Approval rejected " + who).trim() + " — the paused action was not performed.";
        }
        if (verdict == HitlVerdict.APPROVED) {
            return ("✅ Approved " + who).trim() + " — continuing.";
        }

        // Null verdict — cancellation / timeout-abort / end (terminal state, no
        // decision). Make the dead branch live and correct (H-consume).
        return ("⛔ The pending approval was cancelled or expired " + who).trim()
                + " — the paused action was not performed.";
    }

    /**
     * Render a {@code slack:U123} decidedBy as a Slack mention {@code <@U123>};
     * otherwise return it verbatim.
     */
    private static String slackMention(String decidedBy) {
        if (decidedBy.startsWith("slack:")) {
            return "<@" + decidedBy.substring("slack:".length()) + ">";
        }
        return decidedBy;
    }

    /**
     * Parse a Slack-routed managed-conversation intent into channel + thread.
     * Supports both shapes:
     * <ul>
     * <li>{@code channel:slack:{channelId}:{agentId}:{threadKey}} — threadKey
     * "main" means no thread (top-level channel post)</li>
     * <li>{@code channel:followup:{channelId}:{parentTs}} — the parentTs is the
     * group-discussion thread the follow-up lives in</li>
     * </ul>
     * Returns {@code null} if the intent is malformed.
     */
    static SlackRoute parseIntent(String intent) {
        if (intent == null) {
            return null;
        }
        if (intent.startsWith(FOLLOWUP_INTENT_PREFIX)) {
            // ["channel", "followup", channelId, parentTs] — parentTs may itself
            // contain no ':' (a Slack ts is "<seconds>.<micros>"), so 4 parts.
            String[] parts = intent.split(":", 4);
            if (parts.length < 4 || parts[2].isBlank() || parts[3].isBlank()) {
                return null;
            }
            return new SlackRoute(parts[2], parts[3]);
        }
        // ["channel", "slack", channelId, agentId, threadKey]
        String[] parts = intent.split(":", 5);
        if (parts.length < 5 || !"channel".equals(parts[0]) || !"slack".equals(parts[1])) {
            return null;
        }
        String channelId = parts[2];
        String threadKey = parts[4];
        if (channelId.isBlank()) {
            return null;
        }
        String threadTs = THREAD_KEY_MAIN.equals(threadKey) ? null : threadKey;
        return new SlackRoute(channelId, threadTs);
    }

    private void postSafe(String auth, String channelId, String threadTs, String text) {
        try {
            slackApi.postMessage(auth, channelId, threadTs, text);
        } catch (SlackDeliveryException e) {
            LOGGER.warnf("Slack HITL outcome post failed (channel=%s): %s", sanitize(channelId), e.getMessage());
        }
    }

    /** Parsed Slack routing from a stored managed-conversation intent. */
    record SlackRoute(String channelId, String threadTs) {
    }
}
