/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore;
import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore.SlackApprovalRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
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
 * {@code hitl_reject}. The button value carries the owning integration name,
 * the subject and the id of the card it was posted on:
 * {@code <integrationName>|<conversationId>|<cardId>} for a single conversation
 * resume, or {@code <integrationName>|group:<gcId>|<cardId>} for a group
 * discussion resume. Legacy bare values (no integration name) are treated as
 * unbindable and rejected, and so are values without a card id.
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
 * <p>
 * <b>Subject binding.</b> The signature and the approver list prove the
 * decision came from the owning integration, not that the SUBJECT belongs to
 * it: the value is just a string in a payload. So a decision is accepted only
 * if that integration posted an approval card for the subject — recorded in
 * {@link ISlackApprovalRecordStore} when the card was posted — AND the card was
 * posted for the pause the subject is in right now. A subject this integration
 * never notified is refused and logged; a card from an earlier pause of the
 * same subject is treated as already resolved. This holds for group decisions
 * too.
 * <p>
 * <b>Card binding.</b> Subject and pause alone would let an OLD card of the
 * same subject resolve a NEW pause — an approver clicking a stale "delete file
 * A" card would approve a later "delete everything" they never saw. So the
 * decision must also carry the card id recorded for the current pause; a stale
 * card's id belongs to an earlier pause's record, and a button with no card id
 * is refused.
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
    private final ISlackApprovalRecordStore approvalRecords;
    private final ExecutorService executorService;

    @Inject
    public SlackInteractivityHandler(ChannelTargetRouter channelTargetRouter,
            IConversationService conversationService,
            IGroupConversationService groupConversationService,
            SlackWebApiClient slackApi,
            ObjectMapper objectMapper,
            ISlackApprovalRecordStore approvalRecords) {
        this.approvalRecords = approvalRecords;
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

        // SUBJECT + CARD BINDING: the decision must come from a card THIS integration
        // posted for the subject, and that card must be for the pause the subject is
        // in now. A button without a card id predates card binding and cannot be tied
        // to one card, so it is refused.
        String subject = parsed.value().subject();
        String cardId = parsed.value().cardId();
        if (cardId == null) {
            LOGGER.warnf("SLACK_HITL_DECISION_REFUSED | integration=%s | subject=%s | user=%s | reason=button is not "
                    + "bound to an approval card",
                    sanitize(integration.getName()), sanitize(subject), sanitize(parsed.slackUserId()));
            return;
        }
        List<SlackApprovalRecord> records;
        try {
            records = approvalRecords.findBySubject(integration.getName(), subject);
        } catch (RuntimeException e) {
            // Fail closed, and leave the card intact so the approver can retry.
            LOGGER.warnf("Could not read Slack HITL approval records for %s — decision refused: %s",
                    sanitize(subject), e.getMessage());
            return;
        }
        if (records.isEmpty()) {
            LOGGER.warnf("SLACK_HITL_DECISION_REFUSED | integration=%s | subject=%s | user=%s | reason=no approval card "
                    + "was posted for this subject by this integration",
                    sanitize(integration.getName()), sanitize(subject), sanitize(parsed.slackUserId()));
            return;
        }
        SlackApprovalRecord card = records.stream().filter(r -> r.matchesCard(cardId)).findFirst().orElse(null);
        if (card == null) {
            LOGGER.warnf("SLACK_HITL_DECISION_REFUSED | integration=%s | subject=%s | user=%s | reason=button does not "
                    + "belong to an approval card this integration posted for this subject",
                    sanitize(integration.getName()), sanitize(subject), sanitize(parsed.slackUserId()));
            return;
        }
        // Residual TOCTOU (review Finding D, accepted): reading the pause and resuming
        // are two steps, so a resume+re-pause landing between them is not detected.
        // Closing it fully needs an expected-pausedAt threaded into
        // resumeConversation/resumeDiscussion and a CAS there — disproportionate here.
        PauseState pause = currentPause(parsed.value());
        if (pause == PauseState.UNKNOWN) {
            return; // could not read the subject — leave the card so it can be retried
        }
        if (!pause.paused()) {
            finalizeAlreadyResolved(auth, parsed.approvalChannelId(), parsed.messageTs());
            return;
        }
        if (!card.matchesPause(pause.pausedAt())) {
            // A card from an EARLIER pause of this subject: that pause was resolved,
            // and this card must not approve the one that followed it — even when a
            // newer card for the current pause exists.
            LOGGER.warnf("SLACK_HITL_DECISION_REFUSED | integration=%s | subject=%s | user=%s | reason=card was posted "
                    + "for an earlier pause",
                    sanitize(integration.getName()), sanitize(subject), sanitize(parsed.slackUserId()));
            finalizeAlreadyResolved(auth, parsed.approvalChannelId(), parsed.messageTs());
            return;
        }

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
     * Whether the decision's subject is paused right now, and since when.
     * {@link #UNKNOWN} means it could not be read (a transient store failure).
     */
    private record PauseState(boolean paused, Instant pausedAt) {
        static final PauseState UNKNOWN = new PauseState(false, null);
        static final PauseState NOT_PAUSED = new PauseState(false, Instant.EPOCH);
    }

    /**
     * Read the subject's current pause identity: a conversation's
     * {@code hitlPausedAt} while it is {@code AWAITING_HUMAN}, or a group's
     * {@code pausedAt} while it is {@code AWAITING_APPROVAL}. A subject that is
     * gone reads as not paused (idempotent "already resolved").
     */
    private PauseState currentPause(SlackHitlSupport.ActionValue value) {
        if (value.isGroup()) {
            String groupConversationId = value.groupConversationId();
            try {
                GroupConversation gc = groupConversationService.readGroupConversation(groupConversationId);
                if (gc == null || gc.getState() != GroupConversationState.AWAITING_APPROVAL) {
                    return PauseState.NOT_PAUSED;
                }
                return new PauseState(true, gc.getPausedAt());
            } catch (IResourceStore.ResourceNotFoundException | GroupConversationGoneException e) {
                return PauseState.NOT_PAUSED;
            } catch (Exception e) {
                LOGGER.warnf("Could not read group discussion %s for a Slack HITL decision: %s",
                        sanitize(groupConversationId), e.getMessage());
                return PauseState.UNKNOWN;
            }
        }
        String conversationId = value.subject();
        try {
            ConversationMemorySnapshot snapshot = conversationService.getConversationMemorySnapshot(conversationId);
            if (snapshot == null || snapshot.getConversationState() != ConversationState.AWAITING_HUMAN) {
                return PauseState.NOT_PAUSED;
            }
            return new PauseState(true, snapshot.getHitlPausedAt());
        } catch (IResourceStore.ResourceNotFoundException e) {
            return PauseState.NOT_PAUSED;
        } catch (Exception e) {
            LOGGER.warnf("Could not read conversation %s for a Slack HITL decision: %s",
                    sanitize(conversationId), e.getMessage());
            return PauseState.UNKNOWN;
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
