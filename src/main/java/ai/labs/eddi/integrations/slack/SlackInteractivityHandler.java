/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
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
 * {@code hitl_reject}. The button value carries the owning integration name
 * followed by the subject: {@code <integrationName>|<conversationId>} for a
 * single conversation resume, or {@code <integrationName>|group:<gcId>} for a
 * group discussion resume. Legacy bare values (no integration name) are treated
 * as unbindable and rejected.
 * <p>
 * The owning integration — resolved by NAME from the button value, not by a
 * channel lookup — governs both signature verification (see
 * {@link #resolveSigningSecretForDecision}) and authorization. This binds every
 * decision to a specific integration even when several share one approval
 * channel, closing the cross-integration IDOR and the shared-channel
 * nondeterminism.
 * <p>
 * Authorization is <b>fail-closed</b>: the acting Slack user must appear in the
 * owning integration's {@code hitlApproverUserIds}. {@code decidedBy} is always
 * derived server-side ({@code slack:<userId>}) — never trusted from the
 * payload.
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

    /**
     * Resolve the signing secret of the integration that OWNS the decision in this
     * payload — the one carried by the button value ({@code <name>|<subject>}). The
     * interactivity endpoint verifies the raw body against ONLY this secret so a
     * decision can never be authenticated with a different integration's secret.
     * <p>
     * Returns {@code null} (→ reject) when the payload is not an actionable HITL
     * decision, or when it cannot be bound to a new-style integration (legacy bare
     * value, or an unknown integration name). Never throws — a malformed payload
     * resolves to {@code null}.
     */
    public String resolveSigningSecretForDecision(String payloadJson) {
        try {
            var parsed = parseAction(payloadJson);
            if (parsed == null) {
                return null;
            }
            var integration = resolveOwningIntegration(parsed);
            if (integration.isEmpty() || integration.get().getPlatformConfig() == null) {
                return null;
            }
            String secret = integration.get().getPlatformConfig().get("signingSecret");
            return (secret != null && !secret.isBlank()) ? secret : null;
        } catch (Exception e) {
            LOGGER.debugf("Could not resolve owning integration secret for Slack decision: %s", e.getMessage());
            return null;
        }
    }

    void handlePayload(String payloadJson) throws Exception {
        ParsedAction parsed = parseAction(payloadJson);
        if (parsed == null) {
            return;
        }

        // Resolve the OWNING integration (by name from the button value) — it
        // governs the approver list and bot token. This is the same integration
        // whose secret verified the request signature at the endpoint, so authz
        // and authentication are bound to one integration (no cross-integration
        // IDOR, no shared-channel nondeterminism).
        Optional<ChannelIntegrationConfiguration> integrationOpt = resolveOwningIntegration(parsed);
        if (integrationOpt.isEmpty()) {
            LOGGER.warnf("No integration owns Slack HITL decision (channel %s) — ignoring",
                    sanitize(parsed.approvalChannelId()));
            return;
        }
        ChannelIntegrationConfiguration integration = integrationOpt.get();
        var platformConfig = integration.getPlatformConfig();
        String botToken = platformConfig != null ? platformConfig.get("botToken") : null;
        String approverIds = platformConfig != null
                ? platformConfig.get(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS)
                : null;

        // AUTHZ (fail-closed): the acting user must be an approver.
        if (!SlackHitlSupport.isAuthorizedApprover(parsed.slackUserId(), approverIds)) {
            LOGGER.warnf("Unauthorized Slack HITL decision attempt by user %s on channel %s",
                    sanitize(parsed.slackUserId()), sanitize(parsed.approvalChannelId()));
            postAuthzDenied(botToken, parsed.approvalChannelId(), parsed.slackUserId());
            return;
        }

        String auth = botToken != null && !botToken.isBlank() ? "Bearer " + botToken : null;
        if (parsed.value().isGroup()) {
            resolveGroup(parsed.value().groupConversationId(), parsed.verdict(), parsed.slackUserId(),
                    auth, parsed.approvalChannelId(), parsed.messageTs());
        } else {
            resolveConversation(parsed.value().subject(), parsed.verdict(), parsed.slackUserId(),
                    auth, parsed.approvalChannelId(), parsed.messageTs());
        }
    }

    /**
     * Parse a block_actions payload into its actionable HITL fields, or
     * {@code null} if it is not an actionable HITL decision (wrong type, unknown
     * action id, empty/blank value).
     */
    private ParsedAction parseAction(String payloadJson) throws Exception {
        JsonNode payload = objectMapper.readTree(payloadJson);
        String type = payload.path("type").asText("");
        if (!"block_actions".equals(type)) {
            LOGGER.debugf("Ignoring Slack interactivity payload of type %s", sanitize(type));
            return null;
        }

        JsonNode actions = payload.path("actions");
        if (!actions.isArray() || actions.isEmpty()) {
            return null;
        }
        JsonNode action = actions.get(0);
        String actionId = action.path("action_id").asText("");
        String rawValue = action.path("value").asText("");
        String slackUserId = payload.path("user").path("id").asText("");
        String approvalChannelId = payload.path("channel").path("id").asText("");
        String messageTs = payload.path("message").path("ts").asText(null);

        HitlVerdict verdict = verdictFor(actionId);
        if (verdict == null) {
            LOGGER.debugf("Ignoring unknown Slack action_id %s", sanitize(actionId));
            return null;
        }
        SlackHitlSupport.ActionValue value = SlackHitlSupport.parseActionValue(rawValue);
        if (value == null || value.subject() == null || value.subject().isBlank()) {
            LOGGER.warn("Slack HITL action missing value — ignoring");
            return null;
        }
        return new ParsedAction(verdict, value, slackUserId, approvalChannelId, messageTs);
    }

    /**
     * Resolve the owning integration for a parsed decision STRICTLY by the
     * integration NAME carried in the button value (deterministic, IDOR-safe). A
     * legacy/bare value with no name is UNBINDABLE and resolves to empty — it must
     * NOT fall back to a by-approval-channel lookup: in a shared approval channel
     * that would non-deterministically bind the decision to whichever integration
     * happens to own the channel, reintroducing the cross-integration ambiguity the
     * integration-bound value exists to close. Empty here → the endpoint's
     * signature-secret resolution fails → 403 (a bare value is rejected, not
     * routed).
     */
    private Optional<ChannelIntegrationConfiguration> resolveOwningIntegration(ParsedAction parsed) {
        String integrationName = parsed.value().integrationName();
        if (integrationName == null || integrationName.isBlank()) {
            return Optional.empty();
        }
        return channelTargetRouter.getIntegrationByName(CHANNEL_TYPE_SLACK, integrationName);
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
        } catch (IResourceStore.ResourceNotFoundException e) {
            // Conversation gone — nothing to resume; treat as resolved (idempotent).
            LOGGER.debugf("HITL conversation %s not found on resume: %s",
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
        } catch (IllegalStateException
                | GroupDiscussionException
                | IResourceStore.ResourceModifiedException
                | IResourceStore.ResourceNotFoundException
                | GroupConversationGoneException e) {
            // Already resolved / not awaiting / CAS lost / gone — idempotent.
            // resumeDiscussion signals a non-paused group with a (checked)
            // GroupDiscussionException, a concurrent race with
            // ResourceModifiedException, and a deleted group with the unchecked
            // GroupConversationGoneException. A double-click must NOT warn-spam or
            // leave live buttons — reflect the resolved state instead.
            LOGGER.debugf("HITL group %s already resolved/gone: %s",
                    sanitize(groupConversationId), e.getMessage());
            finalizeAlreadyResolved(auth, approvalChannelId, messageTs);
        } catch (Exception e) {
            LOGGER.warnf("Failed to resume group discussion %s from Slack: %s",
                    sanitize(groupConversationId), e.getMessage());
        }
    }

    /**
     * A parsed, actionable HITL decision from a block_actions payload.
     */
    private record ParsedAction(HitlVerdict verdict, SlackHitlSupport.ActionValue value,
            String slackUserId, String approvalChannelId, String messageTs) {
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
