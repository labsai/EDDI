/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
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
 * The stored intent has the shape
 * {@code channel:slack:{channelId}:{agentId}:{threadKey}} ("main" = no thread).
 * If the conversation is not Slack-routed, this is a no-op.
 * <p>
 * Timeout and cancellation outcomes flow through the same event (with a
 * {@code system:*} {@code decidedBy}), so the thread also learns about those.
 * The observer is async and best-effort — its failures never affect the engine.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class SlackHitlResumeObserver {

    private static final Logger LOGGER = Logger.getLogger(SlackHitlResumeObserver.class);
    private static final String SLACK_INTENT_PREFIX = "channel:slack:";
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
        if (mapping == null || mapping.getIntent() == null
                || !mapping.getIntent().startsWith(SLACK_INTENT_PREFIX)) {
            // Not a Slack-routed conversation — nothing to do.
            return;
        }

        SlackRoute route = parseIntent(mapping.getIntent());
        if (route == null) {
            LOGGER.debugf("Could not parse Slack intent for HITL resume delivery: %s", sanitize(mapping.getIntent()));
            return;
        }

        String botToken = channelTargetRouter.getBotToken("slack", route.channelId());
        if (botToken == null || botToken.isBlank()) {
            LOGGER.debugf("No bot token for Slack channel %s — cannot deliver HITL outcome", sanitize(route.channelId()));
            return;
        }
        String auth = "Bearer " + botToken;

        String summary = decisionSummary(event.verdict(), event.decidedBy());
        String continuation = SlackHitlSupport.extractSlackResponseText(event.snapshot());

        var sb = new StringBuilder(summary);
        // Only append genuine agent output (placeholders start with "_").
        if (continuation != null && !continuation.startsWith("_")) {
            sb.append("\n\n").append(continuation);
        }

        postSafe(auth, route.channelId(), route.threadTs(), sb.toString());
    }

    /**
     * Build a human-readable one-liner for the decision. Distinguishes automated
     * outcomes ({@code system:*}) from human ones.
     */
    static String decisionSummary(HitlVerdict verdict, String decidedBy) {
        boolean automated = decidedBy != null && decidedBy.startsWith("system:");
        String who = automated
                ? "(" + decidedBy + ")"
                : (decidedBy != null ? "by " + slackMention(decidedBy) : "");
        if (verdict == HitlVerdict.REJECTED) {
            return ("⛔ Approval rejected " + who).trim() + " — the paused action was not performed.";
        }
        if (verdict == HitlVerdict.APPROVED) {
            return ("✅ Approved " + who).trim() + " — continuing.";
        }
        // Null verdict (e.g. cancellation) — still inform the thread.
        return ("ℹ️ The pending approval was resolved " + who).trim() + ".";
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
     * Parse {@code channel:slack:{channelId}:{agentId}:{threadKey}} into channel +
     * thread. threadKey "main" means no thread (top-level channel post). Returns
     * {@code null} if the intent is malformed.
     */
    static SlackRoute parseIntent(String intent) {
        // Split into at most 5 parts: ["channel", "slack", channelId, agentId,
        // threadKey]
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
