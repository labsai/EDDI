/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Processes Slack interactivity payloads for HITL (human-in-the-loop) approval
 * buttons. Invoked (async) by
 * {@link ai.labs.eddi.integrations.slack.rest.RestSlackWebhook} after the
 * raw-body signature has been verified.
 * <p>
 * Handles {@code block_actions} with action ids {@code hitl_approve} /
 * {@code hitl_reject}. The button value is either a conversationId (single
 * conversation resume) or {@code group:<groupConversationId>} (group discussion
 * resume).
 * <p>
 * Authorization is <b>fail-closed</b>: the acting Slack user must appear in the
 * {@code hitlApproverUserIds} of the integration that owns the approval channel
 * the message was posted to. {@code decidedBy} is always derived server-side
 * ({@code slack:<userId>}) — never trusted from the payload.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class SlackInteractivityHandler {

    private static final Logger LOGGER = Logger.getLogger(SlackInteractivityHandler.class);
    private static final String CHANNEL_TYPE_SLACK = "slack";

    private final ChannelTargetRouter channelTargetRouter;
    private final IConversationService conversationService;
    private final IGroupConversationService groupConversationService;
    private final SlackWebApiClient slackApi;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;

    @Inject
    public SlackInteractivityHandler(ChannelTargetRouter channelTargetRouter,
            IConversationService conversationService,
            IGroupConversationService groupConversationService,
            SlackWebApiClient slackApi,
            ObjectMapper objectMapper) {
        this.channelTargetRouter = channelTargetRouter;
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.slackApi = slackApi;
        this.objectMapper = objectMapper;
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
     * Handle a verified interactivity payload asynchronously (Slack's 3-second rule
     * — the webhook responds 200 immediately). {@code payloadJson} is the decoded
     * value of the {@code payload} form parameter.
     */
    public void handlePayloadAsync(String payloadJson) {
        executorService.submit(() -> {
            try {
                handlePayload(payloadJson);
            } catch (Exception e) {
                LOGGER.errorf(e, "Error handling Slack interactivity payload: %s", e.getMessage());
            }
        });
    }

    void handlePayload(String payloadJson) throws Exception {
        JsonNode payload = objectMapper.readTree(payloadJson);
        String type = payload.path("type").asText("");
        if (!"block_actions".equals(type)) {
            LOGGER.debugf("Ignoring Slack interactivity payload of type %s", sanitize(type));
            return;
        }

        JsonNode actions = payload.path("actions");
        if (!actions.isArray() || actions.isEmpty()) {
            return;
        }
        JsonNode action = actions.get(0);
        String actionId = action.path("action_id").asText("");
        String value = action.path("value").asText("");
        String slackUserId = payload.path("user").path("id").asText("");
        String approvalChannelId = payload.path("channel").path("id").asText("");
        String messageTs = payload.path("message").path("ts").asText(null);

        HitlVerdict verdict = verdictFor(actionId);
        if (verdict == null) {
            LOGGER.debugf("Ignoring unknown Slack action_id %s", sanitize(actionId));
            return;
        }
        if (value.isBlank()) {
            LOGGER.warn("Slack HITL action missing value — ignoring");
            return;
        }

        // Resolve the integration that owns this approval channel — it governs
        // the approver list and bot token.
        Optional<ChannelIntegrationConfiguration> integrationOpt = channelTargetRouter.getIntegrationByApprovalChannel(CHANNEL_TYPE_SLACK,
                approvalChannelId);
        if (integrationOpt.isEmpty()) {
            LOGGER.warnf("No integration owns approval channel %s — ignoring HITL action",
                    sanitize(approvalChannelId));
            return;
        }
        ChannelIntegrationConfiguration integration = integrationOpt.get();
        var platformConfig = integration.getPlatformConfig();
        String botToken = platformConfig != null ? platformConfig.get("botToken") : null;
        String approverIds = platformConfig != null
                ? platformConfig.get(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS)
                : null;

        // AUTHZ (fail-closed): the acting user must be an approver.
        if (!SlackHitlSupport.isAuthorizedApprover(slackUserId, approverIds)) {
            LOGGER.warnf("Unauthorized Slack HITL decision attempt by user %s on channel %s",
                    sanitize(slackUserId), sanitize(approvalChannelId));
            postAuthzDenied(botToken, approvalChannelId, slackUserId);
            return;
        }

        String auth = botToken != null && !botToken.isBlank() ? "Bearer " + botToken : null;
        if (value.startsWith(SlackHitlSupport.GROUP_VALUE_PREFIX)) {
            String groupConversationId = value.substring(SlackHitlSupport.GROUP_VALUE_PREFIX.length());
            resolveGroup(groupConversationId, verdict, slackUserId, auth, approvalChannelId, messageTs);
        } else {
            resolveConversation(value, verdict, slackUserId, auth, approvalChannelId, messageTs);
        }
    }

    private void resolveConversation(String conversationId, HitlVerdict verdict, String slackUserId,
                                     String auth, String approvalChannelId, String messageTs) {
        HitlDecision decision = buildDecision(verdict, slackUserId);
        try {
            conversationService.resumeConversation(conversationId, decision, null);
            finalizeMessage(auth, approvalChannelId, messageTs, verdict, slackUserId);
        } catch (IllegalStateException e) {
            // Already decided / timed out / not paused — idempotent. Do NOT
            // error-spam; reflect the resolved state on the original message.
            LOGGER.debugf("HITL conversation %s already resolved: %s",
                    sanitize(conversationId), e.getMessage());
            finalizeAlreadyResolved(auth, approvalChannelId, messageTs);
        } catch (Exception e) {
            LOGGER.warnf("Failed to resume conversation %s from Slack: %s",
                    sanitize(conversationId), e.getMessage());
            // Leave the message intact so a reviewer can retry.
        }
    }

    private void resolveGroup(String groupConversationId, HitlVerdict verdict, String slackUserId,
                              String auth, String approvalChannelId, String messageTs) {
        var request = new GroupApprovalRequest();
        request.setDecision(buildDecision(verdict, slackUserId));
        try {
            groupConversationService.resumeDiscussion(groupConversationId, request, null);
            finalizeMessage(auth, approvalChannelId, messageTs, verdict, slackUserId);
        } catch (IllegalStateException e) {
            LOGGER.debugf("HITL group %s already resolved: %s",
                    sanitize(groupConversationId), e.getMessage());
            finalizeAlreadyResolved(auth, approvalChannelId, messageTs);
        } catch (Exception e) {
            LOGGER.warnf("Failed to resume group discussion %s from Slack: %s",
                    sanitize(groupConversationId), e.getMessage());
        }
    }

    /**
     * Build a decision. {@code decidedBy} is ALWAYS derived from the verified Slack
     * user id ({@code slack:<userId>}) — never trusted from the payload.
     */
    private HitlDecision buildDecision(HitlVerdict verdict, String slackUserId) {
        var decision = new HitlDecision();
        decision.setVerdict(verdict);
        decision.setDecidedBy("slack:" + slackUserId);
        decision.setNote("Decided via Slack by " + slackUserId);
        return decision;
    }

    private HitlVerdict verdictFor(String actionId) {
        if (SlackHitlSupport.ACTION_APPROVE.equals(actionId)) {
            return HitlVerdict.APPROVED;
        }
        if (SlackHitlSupport.ACTION_REJECT.equals(actionId)) {
            return HitlVerdict.REJECTED;
        }
        return null;
    }

    private void finalizeMessage(String auth, String channelId, String messageTs,
                                 HitlVerdict verdict, String slackUserId) {
        if (auth == null || messageTs == null) {
            return; // cannot update without a token/message ts
        }
        String text = verdict == HitlVerdict.APPROVED
                ? "✅ Approved by <@" + slackUserId + ">"
                : "⛔ Rejected by <@" + slackUserId + ">";
        try {
            slackApi.updateMessage(auth, channelId, messageTs, text,
                    SlackHitlSupport.buildResolvedBlocks(text));
        } catch (SlackDeliveryException e) {
            LOGGER.warnf("Failed to update HITL approval message %s: %s", sanitize(messageTs), e.getMessage());
        }
    }

    private void finalizeAlreadyResolved(String auth, String channelId, String messageTs) {
        if (auth == null || messageTs == null) {
            return;
        }
        String text = "☑️ This request has already been resolved.";
        try {
            slackApi.updateMessage(auth, channelId, messageTs, text,
                    SlackHitlSupport.buildResolvedBlocks(text));
        } catch (SlackDeliveryException e) {
            LOGGER.warnf("Failed to update resolved HITL message %s: %s", sanitize(messageTs), e.getMessage());
        }
    }

    private void postAuthzDenied(String botToken, String channelId, String slackUserId) {
        if (botToken == null || botToken.isBlank() || channelId == null || channelId.isBlank()) {
            return;
        }
        try {
            slackApi.postMessage("Bearer " + botToken, channelId, null,
                    "⚠️ <@" + slackUserId + "> is not authorized to approve or reject this request.");
        } catch (SlackDeliveryException e) {
            LOGGER.debugf("Failed to post authz-denied notice: %s", e.getMessage());
        }
    }
}
