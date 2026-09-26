/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.groups.IGroupConversationStore.GroupConversationGoneException;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
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
 * the subject and the pause id:
 * {@code <integrationName>|<conversationId>|<pauseId>} for a single
 * conversation resume, or {@code <integrationName>|group:<gcId>|<pauseId>} for
 * a group discussion resume. Legacy bare values (no integration name) are
 * treated as unbindable and rejected; a value without a pause id is refused as
 * out of date. The subject must belong to the owning integration (see
 * {@link #conversationBelongsTo} and {@link #groupBelongsTo}).
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
    private final IUserConversationStore userConversationStore;
    private final ExecutorService executorService;

    @Inject
    public SlackInteractivityHandler(ChannelTargetRouter channelTargetRouter,
            IConversationService conversationService,
            IGroupConversationService groupConversationService,
            SlackWebApiClient slackApi,
            IUserConversationStore userConversationStore,
            ObjectMapper objectMapper) {
        this.channelTargetRouter = channelTargetRouter;
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.slackApi = slackApi;
        this.userConversationStore = userConversationStore;
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

        // AUTHZ (fail-closed): the acting user must be an approver. An approver entry
        // may be team-scoped (T…:U…), and when the integration pins its workspace, a
        // clicking user who belongs to another team (Slack Connect) is refused even
        // if their bare id happens to be on the list — ids are unique per team only.
        String pinnedTeam = platformConfig != null ? platformConfig.get(ChannelTargetRouter.CFG_TEAM_ID) : null;
        boolean foreignTeamUser = pinnedTeam != null && !pinnedTeam.isBlank() && parsed.slackUserTeamId() != null
                && !pinnedTeam.trim().equals(parsed.slackUserTeamId());
        if (foreignTeamUser
                || !SlackHitlSupport.isAuthorizedApprover(parsed.slackUserId(), parsed.slackUserTeamId(), approverIds)) {
            LOGGER.warnf("Unauthorized Slack HITL decision attempt by user %s on channel %s",
                    sanitize(parsed.slackUserId()), sanitize(parsed.approvalChannelId()));
            postAuthzDenied(botToken, parsed.approvalChannelId(), parsed.slackUserId());
            return;
        }

        String auth = botToken != null && !botToken.isBlank() ? "Bearer " + botToken : null;

        // The workspace/app the click came from must be the integration's own, when
        // the integration pins them (teamId / appId) — same rule as the events
        // webhook.
        if (!ChannelTargetRouter.identifiersMatch(integration, parsed.inboundIds())) {
            LOGGER.warnf("Slack HITL decision from a workspace/app integration '%s' does not pin — ignoring",
                    sanitize(integration.getName()));
            return;
        }

        // H4b: a card is bound to the pause it was posted for. One without a pause
        // id predates that binding and could only mean "whatever is paused now".
        if (parsed.value().pauseId() == null) {
            LOGGER.infof("Slack HITL decision from a card without a pause id (channel %s) — refusing",
                    sanitize(parsed.approvalChannelId()));
            finalizeSuperseded(auth, parsed.approvalChannelId(), parsed.messageTs());
            return;
        }

        if (parsed.value().isGroup()) {
            resolveGroup(integration, parsed, auth);
        } else {
            resolveConversation(integration, parsed, auth);
        }
    }

    /**
     * H4c: whether the conversation a decision targets belongs to the integration
     * whose approvers are deciding it.
     * <p>
     * The button value is attacker-controlled once someone holds an integration's
     * signing secret — and anyone who can create an integration holds its secret.
     * Checking the approver against the integration named in the value, and then
     * resuming whichever conversation id the value also named, let an integration
     * made for the purpose decide every paused conversation in the deployment.
     * <p>
     * A conversation belongs to an integration when (a) its agent is one of the
     * integration's targets, and (b) it was started by that integration — the
     * {@code channelIntegrationId} start context the Slack adapter records, holding
     * the integration's store resource id. The id, not the name: a name can be
     * renamed away and re-used by a newly created integration, which would then
     * inherit the old one's paused conversations. A conversation started before the
     * binding existed carries no id and is not decidable from Slack (the card for
     * it is posted without buttons); it stays decidable from the Manager and the
     * API.
     */
    static boolean conversationBelongsTo(ChannelIntegrationConfiguration integration, ConversationMemorySnapshot snapshot) {
        if (snapshot == null || integration == null || integration.getResourceId() == null) {
            return false;
        }
        boolean agentIsTarget = integration.getTargets() != null && integration.getTargets().stream()
                .anyMatch(t -> t.getType() == ChannelTarget.TargetType.AGENT
                        && t.getTargetId() != null && t.getTargetId().equals(snapshot.getAgentId()));
        return agentIsTarget
                && integration.getResourceId().equals(startContext(snapshot).get(SlackEventHandler.CONTEXT_CHANNEL_INTEGRATION_ID));
    }

    /**
     * The context the conversation was started with — the first output's
     * {@code context} map, which is where {@code Conversation} records it.
     */
    private static Map<?, ?> startContext(ConversationMemorySnapshot snapshot) {
        var outputs = snapshot.getConversationOutputs();
        if (outputs == null || outputs.isEmpty() || outputs.get(0) == null) {
            return Map.of();
        }
        return outputs.get(0).get("context") instanceof Map<?, ?> context ? context : Map.of();
    }

    /**
     * H4c for a group discussion: its group must be one of the integration's
     * targets, AND the discussion must have been started through this integration —
     * recorded by the Slack adapter as an origin mapping keyed by the integration's
     * resource id. "The group is a target" alone would let any integration listing
     * the group decide every discussion of it, including ones started over REST, by
     * other users, or through another integration.
     */
    boolean groupBelongsTo(ChannelIntegrationConfiguration integration, GroupConversation gc) throws Exception {
        if (gc == null || integration == null || integration.getResourceId() == null || integration.getTargets() == null) {
            return false;
        }
        boolean groupIsTarget = integration.getTargets().stream()
                .anyMatch(t -> t.getType() == ChannelTarget.TargetType.GROUP
                        && t.getTargetId() != null && t.getTargetId().equals(gc.getGroupId()));
        if (!groupIsTarget || gc.getId() == null) {
            return false;
        }
        var origin = userConversationStore.readUserConversation(SlackEventHandler.GROUP_ORIGIN_INTENT_PREFIX + gc.getId(),
                SlackEventHandler.GROUP_ORIGIN_USER_PREFIX + integration.getResourceId());
        return origin != null && gc.getId().equals(origin.getConversationId());
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
        String slackUserTeamId = blankToNull(payload.path("user").path("team_id").asText(null));
        String approvalChannelId = payload.path("channel").path("id").asText("");
        String messageTs = payload.path("message").path("ts").asText(null);
        var inboundIds = new HashMap<String, String>();
        inboundIds.put(ChannelTargetRouter.CFG_TEAM_ID, blankToNull(payload.path("team").path("id").asText(null)));
        inboundIds.put(ChannelTargetRouter.CFG_APP_ID, blankToNull(payload.path("api_app_id").asText(null)));

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
        return new ParsedAction(verdict, value, slackUserId, slackUserTeamId, approvalChannelId, messageTs, inboundIds);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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

    private void resolveConversation(ChannelIntegrationConfiguration integration, ParsedAction parsed, String auth) {
        String conversationId = parsed.value().subject();
        HitlVerdict verdict = parsed.verdict();
        String slackUserId = parsed.slackUserId();
        String approvalChannelId = parsed.approvalChannelId();
        String messageTs = parsed.messageTs();
        HitlDecision decision = buildDecision(verdict, slackUserId, parsed.value().pauseId());
        try {
            ConversationMemorySnapshot snapshot = conversationService.getConversationMemorySnapshot(conversationId);
            if (!conversationBelongsTo(integration, snapshot)) {
                LOGGER.warnf("Slack HITL decision via integration '%s' targets conversation %s, which that "
                        + "integration did not start — refusing", sanitize(integration.getName()), sanitize(conversationId));
                postAuthzDenied(integration.getPlatformConfig().get("botToken"), approvalChannelId, slackUserId);
                return;
            }
            // Checked again, authoritatively, inside resumeConversation; here only so a
            // stale card gets a clear "superseded" instead of "already resolved". Only
            // while paused: a conversation that is no longer awaiting a human has no
            // current pause, and the resume's state conflict marks the card resolved.
            if (snapshot.getConversationState() == ConversationState.AWAITING_HUMAN
                    && !decision.appliesToPause(snapshot.getHitlPausedAt())) {
                finalizeSuperseded(auth, approvalChannelId, messageTs);
                return;
            }
            conversationService.resumeConversation(conversationId, decision, null);
            finalizeMessage(auth, approvalChannelId, messageTs, verdict, slackUserId);
        } catch (IConversationService.PauseMismatchException e) {
            finalizeSuperseded(auth, approvalChannelId, messageTs);
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

    private void resolveGroup(ChannelIntegrationConfiguration integration, ParsedAction parsed, String auth) {
        String groupConversationId = parsed.value().groupConversationId();
        HitlVerdict verdict = parsed.verdict();
        String slackUserId = parsed.slackUserId();
        String approvalChannelId = parsed.approvalChannelId();
        String messageTs = parsed.messageTs();
        var decision = buildDecision(verdict, slackUserId, parsed.value().pauseId());
        var request = new GroupApprovalRequest();
        request.setDecision(decision);
        try {
            GroupConversation gc = groupConversationService.readGroupConversation(groupConversationId);
            if (!groupBelongsTo(integration, gc)) {
                LOGGER.warnf("Slack HITL decision via integration '%s' targets group discussion %s, whose group is "
                        + "not one of its targets — refusing", sanitize(integration.getName()), sanitize(groupConversationId));
                postAuthzDenied(integration.getPlatformConfig().get("botToken"), approvalChannelId, slackUserId);
                return;
            }
            // Same rule for a group: only a discussion still awaiting approval has a
            // current pause to compare with.
            if (gc.getState() == GroupConversation.GroupConversationState.AWAITING_APPROVAL
                    && !decision.appliesToPause(gc.getPausedAt())) {
                finalizeSuperseded(auth, approvalChannelId, messageTs);
                return;
            }
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
            String slackUserId, String slackUserTeamId, String approvalChannelId, String messageTs,
            Map<String, String> inboundIds) {
    }

    /**
     * Build a decision. {@code decidedBy} is ALWAYS derived from the verified Slack
     * user id ({@code slack:<userId>}) — never trusted from the payload. The pause
     * id from the card binds the decision to the pause the card was posted for.
     */
    private HitlDecision buildDecision(HitlVerdict verdict, String slackUserId, String pauseId) {
        var decision = new HitlDecision();
        decision.setVerdict(verdict);
        decision.setDecidedBy("slack:" + slackUserId);
        decision.setNote("Decided via Slack by " + slackUserId);
        decision.setPauseId(pauseId);
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

    /**
     * The card is for a pause that is no longer the current one (or predates pause
     * ids). Its buttons are removed so nobody clicks it again; the current pause is
     * untouched and has — or will get — a card of its own.
     */
    private void finalizeSuperseded(String auth, String channelId, String messageTs) {
        if (auth == null || messageTs == null) {
            return;
        }
        String text = "☑️ This approval card is out of date — the request it was posted for is no longer the one "
                + "pending. Decide the current request from its own card, the Manager or the API.";
        try {
            slackApi.updateMessage(auth, channelId, messageTs, text, SlackHitlSupport.buildResolvedBlocks(text));
        } catch (SlackDeliveryException e) {
            LOGGER.warnf("Failed to update superseded HITL message %s: %s", sanitize(messageTs), e.getMessage());
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
