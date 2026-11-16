/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.hitl.model.HitlNotifyConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.events.GroupHitlPauseCommittedEvent;
import ai.labs.eddi.engine.events.HitlPauseCommittedEvent;
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.hitl.HitlPlanHash;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore;
import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore.SlackApprovalRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Posts the Slack approval card for <b>every</b> committed pause whose agent or
 * group names a Slack channel integration ({@code hitlConfig.notify.slack}), or
 * that falls to the deployment default
 * ({@code eddi.slack.hitl.notify.default-integration}) — not only for
 * conversations and discussions a Slack message started. A schedule, a team
 * cadence, a webhook-triggered agent or a REST/MCP conversation that pauses
 * reaches its approvers in Slack the same way.
 * <p>
 * <b>Trigger.</b> {@link HitlPauseCommittedEvent} and
 * {@link GroupHitlPauseCommittedEvent}, fired asynchronously once a pause is
 * durable. Nothing polls for pauses.
 * <p>
 * <b>The same card, the same security.</b> The card is built from the stored
 * pause exactly as the Slack-started path builds it: the redacted tool-call
 * preview, the approval context only when the integration opts in
 * ({@code hitlApprovalContext}), buttons only when it lists
 * {@code hitlApproverUserIds}. Before the card is posted an approval record is
 * written for {@code (integration, subject, pause)} with a fresh card id that
 * goes into the buttons, so {@link SlackInteractivityHandler} accepts a click
 * only from this card, only for the pause it was posted for, only from that
 * integration's approvers and workspace. The integration's own bot token posts
 * into its own approval channel; the agent configuration chooses an
 * integration, never a channel or a token.
 * <p>
 * <b>One card per pause and integration.</b> The record doubles as the
 * idempotency marker, so a pause that the Slack thread path also announces
 * through the same integration gets one card, whichever path writes the record
 * first.
 * <p>
 * <b>Resolution.</b> The card's {@code ts} is kept on the record. When the
 * pause ends anywhere — a button, the Manager, REST or MCP, a timeout, a cancel
 * — the card's buttons are replaced by the outcome: at once for a conversation
 * ({@link HitlResumeCompletedEvent}), and by {@link #sweep()} for everything
 * that event does not cover (group discussions, a pause replaced by a new one,
 * a deleted subject). The record is then removed.
 * <p>
 * Best-effort throughout: a failure here never affects the pause.
 *
 * @since 6.8.0
 */
@ApplicationScoped
public class SlackPauseNotifier {

    private static final Logger LOGGER = Logger.getLogger(SlackPauseNotifier.class);

    static final String CHANNEL_TYPE_SLACK = "slack";
    static final String METER = "eddi.slack.hitl.notify";

    /** Cards one sweep checks. */
    static final int SWEEP_BATCH = 50;
    /** Pages one sweep reads at most. */
    static final int SWEEP_PAGES = 20;
    /** Where the next sweep resumes in the oldest-first list of tracked cards. */
    private volatile int sweepCursor;

    static final String RESOLVED_TEXT = "✔️ This approval request is no longer waiting — it was decided, cancelled or expired.";
    static final String GROUP_REJECTED_TEXT = "⛔ Rejected — the discussion did not continue past this approval.";
    static final String GROUP_CANCELLED_TEXT = "🛑 The discussion was cancelled — this approval request is no longer waiting.";
    static final String GROUP_DECIDED_TEXT = "✔️ This approval request was decided — the discussion has moved on.";

    private final IConversationMemoryStore conversationMemoryStore;
    private final IGroupConversationStore groupConversationStore;
    private final IAgentStore agentStore;
    private final IAgentGroupStore groupStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final ChannelTargetRouter channelTargetRouter;
    private final SlackWebApiClient slackApi;
    private final ISlackApprovalRecordStore approvalRecords;
    private final MeterRegistry meterRegistry;
    private final String defaultIntegration;

    @Inject
    public SlackPauseNotifier(IConversationMemoryStore conversationMemoryStore, IGroupConversationStore groupConversationStore,
            IAgentStore agentStore, IAgentGroupStore groupStore, IDocumentDescriptorStore documentDescriptorStore,
            ChannelTargetRouter channelTargetRouter, SlackWebApiClient slackApi, ISlackApprovalRecordStore approvalRecords,
            MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.slack.hitl.notify.default-integration") Optional<String> defaultIntegration) {
        this.conversationMemoryStore = conversationMemoryStore;
        this.groupConversationStore = groupConversationStore;
        this.agentStore = agentStore;
        this.groupStore = groupStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.channelTargetRouter = channelTargetRouter;
        this.slackApi = slackApi;
        this.approvalRecords = approvalRecords;
        this.meterRegistry = meterRegistry;
        String configured = defaultIntegration == null ? null : defaultIntegration.map(String::trim).orElse(null);
        this.defaultIntegration = configured == null || configured.isEmpty() ? null : configured;
    }

    @PostConstruct
    void registerMeters() {
        for (String outcome : List.of("posted", "duplicate", "failed", "no_integration", "no_channel", "no_token", "resolved")) {
            counter(outcome);
        }
        if (defaultIntegration != null) {
            LOGGER.infof("Slack approval cards for pauses without hitlConfig.notify.slack go to integration '%s' "
                    + "(eddi.slack.hitl.notify.default-integration)", sanitize(defaultIntegration));
        }
    }

    private Counter counter(String outcome) {
        return meterRegistry.counter(METER, "outcome", outcome);
    }

    private void count(String outcome) {
        counter(outcome).increment();
    }

    // --- Triggers ------------------------------------------------------------

    void onPauseCommitted(@ObservesAsync HitlPauseCommittedEvent event) {
        try {
            notifyConversationPause(event.conversationId(), event.pauseId());
        } catch (RuntimeException e) {
            LOGGER.warnf("Slack approval card for conversation %s failed: %s", sanitize(event.conversationId()), e.getMessage());
        }
    }

    void onGroupPauseCommitted(@ObservesAsync GroupHitlPauseCommittedEvent event) {
        try {
            notifyGroupPause(event.groupConversationId(), event.pauseId());
        } catch (RuntimeException e) {
            LOGGER.warnf("Slack approval card for group conversation %s failed: %s", sanitize(event.groupConversationId()),
                    e.getMessage());
        }
    }

    void onResumeCompleted(@ObservesAsync HitlResumeCompletedEvent event) {
        try {
            var snapshot = event.snapshot();
            if (event.conversationId() == null || snapshot == null || snapshot.getAgentId() == null) {
                return; // the sweep resolves it
            }
            String integrationName = integrationFor(readAgent(snapshot.getAgentId(), snapshot.getAgentVersion()));
            if (integrationName == null) {
                return;
            }
            String text = SlackHitlResumeObserver.decisionSummary(event.verdict(), event.decidedBy(), snapshot);
            // The event is observed asynchronously: by now the conversation may be in a
            // NEWER pause (whose card must stay live) or may even have left that one too.
            // Only cards whose own pause has ended are finalized, and the decision's
            // outcome is shown only on the card of the pause it decided.
            List<SlackApprovalRecord> ended = new ArrayList<>();
            for (SlackApprovalRecord record : approvalRecords.findBySubject(integrationName, event.conversationId())) {
                if (record.messageTs() != null && resolutionText(record) != null) {
                    ended.add(record);
                }
            }
            for (SlackApprovalRecord record : ended) {
                boolean itsDecision = event.pauseId() != null ? event.pauseId().equals(record.pauseEpoch()) : ended.size() == 1;
                finalizeCard(record, itsDecision ? text : RESOLVED_TEXT);
            }
        } catch (RuntimeException e) {
            LOGGER.debugf("Slack approval card for conversation %s not updated on resume (the sweep will): %s",
                    sanitize(event.conversationId()), e.getMessage());
        }
    }

    // --- Conversations -------------------------------------------------------

    void notifyConversationPause(String conversationId, String pauseId) {
        if (conversationId == null || pauseId == null) {
            return;
        }
        ConversationMemorySnapshot snapshot = currentConversationPause(conversationId, pauseId);
        if (snapshot == null) {
            return; // decided, cancelled or replaced before we got here
        }
        AgentConfiguration agent = readAgent(snapshot.getAgentId(), snapshot.getAgentVersion());
        String integrationName = integrationFor(agent);
        if (integrationName == null) {
            return; // not configured: off
        }
        ChannelIntegrationConfiguration integration = resolveIntegration(integrationName, conversationId);
        if (integration == null) {
            return;
        }
        Map<String, String> platformConfig = integration.getPlatformConfig();
        String agentLabel = agentLabel(snapshot.getAgentId(), snapshot.getAgentVersion());
        String timeoutInfo = SlackEventHandler.formatTimeoutInfo(
                snapshot.getHitlTimeoutPolicy() != null ? snapshot.getHitlTimeoutPolicy().name() : null, snapshot.getHitlApprovalTimeout());
        String pauseType = snapshot.getHitlPauseType();
        var pendingToolCalls = snapshot.getHitlPendingToolCalls();
        postCard(integration, conversationId, pauseId, buttonsAllowed -> {
            String actionValue = SlackHitlSupport.buildActionValue(integration.getName(), conversationId, buttonsAllowed.cardId(),
                    HitlPlanHash.presentable(snapshot.getHitlPlanHash()));
            var blocks = SlackHitlSupport.buildApprovalBlocks("⏸️ Conversation awaiting approval", "Conversation", conversationId,
                    agentLabel, snapshot.getHitlPauseReason(), timeoutInfo, actionValue, buttonsAllowed.includeButtons(), pauseType,
                    pendingToolCalls, SlackHitlEditModal.forCard(pauseType, pendingToolCalls, snapshot, platformConfig),
                    buttonsAllowed.noButtonsNote());
            boolean contextShown = SlackApprovalContextBlocks.insertBeforeActions(blocks, snapshot.getHitlApprovalContext(),
                    SlackApprovalContextBlocks.enabled(platformConfig));
            return new Card(blocks, "Conversation " + conversationId + " is awaiting human approval.", contextShown);
        });
    }

    /**
     * The conversation's snapshot when it is still in exactly this pause, else
     * {@code null}.
     */
    private ConversationMemorySnapshot currentConversationPause(String conversationId, String pauseId) {
        try {
            if (conversationMemoryStore.getConversationState(conversationId) != ConversationState.AWAITING_HUMAN) {
                return null;
            }
            ConversationMemorySnapshot snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (snapshot == null || !pauseId.equals(HitlDecision.pauseIdOf(snapshot.getHitlPausedAt()))) {
                return null;
            }
            return snapshot;
        } catch (IResourceStore.ResourceNotFoundException e) {
            return null;
        } catch (IResourceStore.ResourceStoreException e) {
            throw new IllegalStateException("Pause " + pauseId + " of conversation " + conversationId + " could not be read", e);
        }
    }

    private AgentConfiguration readAgent(String agentId, Integer agentVersion) {
        if (agentId == null) {
            return null;
        }
        try {
            return agentStore.read(agentId, agentVersion);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            LOGGER.debugf("Agent %s v%s not readable for its Slack approval card: %s", sanitize(agentId), agentVersion, e.getMessage());
            return null;
        }
    }

    /** "name (id)" when the agent has a name, else the id. */
    private String agentLabel(String agentId, Integer agentVersion) {
        try {
            var descriptor = documentDescriptorStore.readDescriptor(agentId, agentVersion);
            String name = descriptor == null ? null : descriptor.getName();
            if (name != null && !name.isBlank()) {
                return name + " (" + agentId + ")";
            }
        } catch (Exception e) {
            LOGGER.debugf("No descriptor for agent %s: %s", sanitize(agentId), e.getMessage());
        }
        return agentId;
    }

    /**
     * The integration an agent's pauses go to: its {@code hitlConfig.notify.slack},
     * else the deployment default, else {@code null} (off).
     */
    String integrationFor(AgentConfiguration agent) {
        HitlNotifyConfig notify = agent != null && agent.getHitlConfig() != null ? agent.getHitlConfig().getNotify() : null;
        return integrationFor(notify);
    }

    String integrationFor(AgentGroupConfiguration group) {
        HitlNotifyConfig notify = group != null && group.getHitlConfig() != null ? group.getHitlConfig().getNotify() : null;
        return integrationFor(notify);
    }

    private String integrationFor(HitlNotifyConfig notify) {
        if (notify != null && notify.getSlack() != null) {
            String named = notify.getSlack().getIntegration();
            if (named != null && !named.isBlank()) {
                return named.trim();
            }
        }
        return defaultIntegration;
    }

    // --- Groups --------------------------------------------------------------

    void notifyGroupPause(String groupConversationId, String pauseId) {
        if (groupConversationId == null || pauseId == null) {
            return;
        }
        GroupConversation gc = currentGroupPause(groupConversationId, pauseId);
        if (gc == null) {
            return;
        }
        AgentGroupConfiguration group = readCurrentGroup(gc.getGroupId());
        String integrationName = integrationFor(group);
        if (integrationName == null) {
            return;
        }
        ChannelIntegrationConfiguration integration = resolveIntegration(integrationName, groupConversationId);
        if (integration == null) {
            return;
        }
        Map<String, String> platformConfig = integration.getPlatformConfig();
        String subject = SlackHitlSupport.GROUP_VALUE_PREFIX + groupConversationId;
        String phase = gc.getPausedPhaseName() != null ? gc.getPausedPhaseName() : ("phase " + gc.getPausedAtPhaseIndex());
        String label = group != null && group.getName() != null && !group.getName().isBlank() ? group.getName() + " — " + phase : phase;
        String timeoutInfo = SlackEventHandler.formatTimeoutInfo(gc.getHitlTimeoutPolicy() != null ? gc.getHitlTimeoutPolicy().name() : null,
                gc.getHitlApprovalTimeout());
        String planHash = HitlPlanHash.forGroup(gc);
        postCard(integration, subject, pauseId, buttonsAllowed -> {
            String actionValue = SlackHitlSupport.buildActionValue(integration.getName(), subject, buttonsAllowed.cardId(), planHash);
            var blocks = SlackHitlSupport.buildApprovalBlocks("⏸️ Discussion awaiting approval", "Discussion", groupConversationId, label,
                    gc.getHitlPauseReason(), timeoutInfo, actionValue, buttonsAllowed.includeButtons(), null, null,
                    SlackHitlEditModal.CardEdits.NONE, buttonsAllowed.noButtonsNote());
            boolean contextShown = SlackApprovalContextBlocks.insertBeforeActions(blocks, gc.getHitlApprovalContext(),
                    SlackApprovalContextBlocks.enabled(platformConfig));
            return new Card(blocks, "Group discussion " + groupConversationId + " is awaiting human approval.", contextShown);
        });
    }

    private GroupConversation currentGroupPause(String groupConversationId, String pauseId) {
        try {
            GroupConversation gc = groupConversationStore.read(groupConversationId);
            if (gc == null || gc.getState() != GroupConversationState.AWAITING_APPROVAL
                    || !pauseId.equals(HitlDecision.pauseIdOf(gc.getPausedAt()))) {
                return null;
            }
            return gc;
        } catch (IResourceStore.ResourceNotFoundException | IGroupConversationStore.GroupConversationGoneException e) {
            return null;
        } catch (IResourceStore.ResourceStoreException e) {
            throw new IllegalStateException("Pause " + pauseId + " of group conversation " + groupConversationId + " could not be read", e);
        }
    }

    /** The group's current version, as the discussion engine reads it. */
    private AgentGroupConfiguration readCurrentGroup(String groupId) {
        if (groupId == null) {
            return null;
        }
        try {
            IResourceStore.IResourceId current = groupStore.getCurrentResourceId(groupId);
            return current == null ? null : groupStore.read(groupId, current.getVersion());
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            LOGGER.debugf("Group %s not readable for its Slack approval card: %s", sanitize(groupId), e.getMessage());
            return null;
        }
    }

    // --- Posting -------------------------------------------------------------

    /** The integration by name, or {@code null} (logged) when it cannot post. */
    private ChannelIntegrationConfiguration resolveIntegration(String integrationName, String subject) {
        Optional<ChannelIntegrationConfiguration> found = channelTargetRouter.getIntegrationByName(CHANNEL_TYPE_SLACK, integrationName);
        if (found.isEmpty() || found.get().getPlatformConfig() == null) {
            count("no_integration");
            LOGGER.warnf("No Slack channel integration named '%s' (or more than one) — approval card for %s NOT posted. "
                    + "Check hitlConfig.notify.slack.integration or eddi.slack.hitl.notify.default-integration",
                    sanitize(integrationName), sanitize(subject));
            return null;
        }
        return found.get();
    }

    /** What a card builder is told: its id, and whether it may carry buttons. */
    record CardSlot(String cardId, boolean includeButtons, String noButtonsNote) {
    }

    /** A built card. */
    record Card(List<Map<String, Object>> blocks, String fallback, boolean contextShown) {
    }

    @FunctionalInterface
    interface CardBuilder {
        Card build(CardSlot slot);
    }

    /**
     * Records, posts and tracks one card: the record first (it binds the buttons
     * and keeps the card unique per pause), then the post, then its {@code ts} for
     * the resolution update. A card that could not be delivered gives its record
     * back, so a later announcement of the same pause may try again.
     */
    void postCard(ChannelIntegrationConfiguration integration, String subject, String pauseEpoch, CardBuilder builder) {
        Map<String, String> platformConfig = integration.getPlatformConfig();
        String integrationName = integration.getName();
        String approvalChannel = platformConfig.get(SlackHitlSupport.CFG_HITL_APPROVAL_CHANNEL);
        if (approvalChannel == null || approvalChannel.isBlank()) {
            count("no_channel");
            LOGGER.warnf("Slack integration '%s' has no %s — approval card for %s NOT posted", sanitize(integrationName),
                    SlackHitlSupport.CFG_HITL_APPROVAL_CHANNEL, sanitize(subject));
            return;
        }
        String botToken = platformConfig.get("botToken");
        if (botToken == null || botToken.isBlank()) {
            // Only the integration's own token posts its card — never another's.
            count("no_token");
            LOGGER.errorf("Slack integration '%s' has no bot token — approval card for %s NOT posted", sanitize(integrationName),
                    sanitize(subject));
            return;
        }
        boolean includeButtons = !SlackHitlSupport.parseApproverUserIds(platformConfig.get(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS))
                .isEmpty();
        if (!SlackHitlSupport.isBindableIntegrationName(integrationName)) {
            LOGGER.warnf("Slack integration '%s' has a name containing '|', which approval buttons cannot carry — posting the approval "
                    + "card without buttons; rename the integration", sanitize(integrationName));
            includeButtons = false;
        }
        String cardId = ISlackApprovalRecordStore.newCardId();
        boolean recorded;
        try {
            if (!approvalRecords.tryRecord(integrationName, subject, pauseEpoch, cardId, approvalChannel)) {
                count("duplicate");
                return; // this integration already posted a card for this pause
            }
            recorded = true;
        } catch (RuntimeException e) {
            // Fail closed: no record, no decision could be accepted — buttons would only
            // produce refusals.
            LOGGER.errorf("Could not record the Slack approval card for %s — posting it without buttons: %s", sanitize(subject),
                    e.getMessage());
            recorded = false;
            includeButtons = false;
        }

        Card card = builder.build(new CardSlot(cardId, includeButtons, null));
        String auth = "Bearer " + botToken;
        String ts;
        try {
            ts = card.contextShown()
                    // Conversation text on the card: no link or media preview of anything a
                    // model wrote.
                    ? slackApi.postBlocksMessage(auth, approvalChannel, null, card.blocks(), card.fallback(), false)
                    : slackApi.postBlocksMessage(auth, approvalChannel, null, card.blocks(), card.fallback());
        } catch (SlackDeliveryException e) {
            LOGGER.warnf("Failed to post the Slack approval card for %s: %s", sanitize(subject), e.getMessage());
            ts = null;
        }
        if (ts == null) {
            count("failed");
            if (recorded) {
                forget(integrationName, subject, pauseEpoch);
            }
            return;
        }
        count("posted");
        if (recorded) {
            try {
                approvalRecords.markPosted(integrationName, subject, pauseEpoch, ts);
            } catch (RuntimeException e) {
                // The card works; it just will not be updated when the pause ends elsewhere.
                LOGGER.warnf("Could not remember the Slack approval card for %s — it will not be updated on resolution: %s",
                        sanitize(subject), e.getMessage());
            }
        }
    }

    private void forget(String integrationName, String subject, String pauseEpoch) {
        try {
            approvalRecords.delete(integrationName, subject, pauseEpoch);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not delete the record of an undelivered Slack approval card for %s: %s", sanitize(subject), e.getMessage());
        }
    }

    // --- Resolution ----------------------------------------------------------

    @Scheduled(every = "${eddi.slack.hitl.notify.sweep-interval:60s}", delayed = "60s", identity = "slack-hitl-card-sweeper",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            LOGGER.warnf("Slack approval card sweep failed: %s", e.getMessage());
        }
    }

    /**
     * Updates every tracked card whose pause has ended. A subject that cannot be
     * read right now is left for the next sweep.
     */
    void sweepOnce() {
        // Oldest-first pages, walked past the records that are kept (still pending), so
        // a full page of old pending cards cannot hide a newer resolved one. A sweep
        // reads at most SWEEP_PAGES pages and the next one carries on from there.
        int offset = sweepCursor;
        for (int page = 0; page < SWEEP_PAGES; page++) {
            List<SlackApprovalRecord> records = approvalRecords.findPosted(SWEEP_BATCH, offset);
            for (SlackApprovalRecord record : records) {
                boolean finalized = false;
                try {
                    String text = resolutionText(record);
                    if (text != null) {
                        finalizeCard(record, text);
                        finalized = true;
                    }
                } catch (RuntimeException e) {
                    LOGGER.debugf("Slack approval card for %s not checked: %s", sanitize(record.subject()), e.getMessage());
                }
                if (!finalized) {
                    offset++; // a finalized record is gone, so the next one slid into its place
                }
            }
            if (records.size() < SWEEP_BATCH) {
                sweepCursor = 0; // reached the end: start over from the oldest
                return;
            }
        }
        sweepCursor = offset;
    }

    /**
     * The text that replaces the card's buttons, or {@code null} while the card's
     * pause is still the subject's current one (or cannot be read).
     */
    String resolutionText(SlackApprovalRecord record) {
        String subject = record.subject();
        if (subject.startsWith(SlackHitlSupport.GROUP_VALUE_PREFIX)) {
            String groupConversationId = subject.substring(SlackHitlSupport.GROUP_VALUE_PREFIX.length());
            GroupConversation gc;
            try {
                gc = groupConversationStore.read(groupConversationId);
            } catch (IResourceStore.ResourceNotFoundException | IGroupConversationStore.GroupConversationGoneException e) {
                return RESOLVED_TEXT;
            } catch (IResourceStore.ResourceStoreException e) {
                return null;
            }
            if (gc == null) {
                return RESOLVED_TEXT;
            }
            if (gc.getState() == GroupConversationState.AWAITING_APPROVAL && record.matchesPause(gc.getPausedAt())) {
                return null;
            }
            return switch (gc.getState()) {
                case REJECTED -> GROUP_REJECTED_TEXT;
                case CANCELLED -> GROUP_CANCELLED_TEXT;
                default -> GROUP_DECIDED_TEXT;
            };
        }
        try {
            ConversationState state = conversationMemoryStore.getConversationState(subject);
            if (state == ConversationState.AWAITING_HUMAN) {
                ConversationMemorySnapshot snapshot = conversationMemoryStore.loadConversationMemorySnapshot(subject);
                if (snapshot != null && record.matchesPause(snapshot.getHitlPausedAt())) {
                    return null;
                }
            }
            return RESOLVED_TEXT;
        } catch (IResourceStore.ResourceNotFoundException e) {
            return RESOLVED_TEXT;
        } catch (IResourceStore.ResourceStoreException e) {
            return null;
        }
    }

    /**
     * Replaces the card's buttons with {@code text} and drops its record. The
     * record goes even when the update fails: the card's pause is over either way,
     * and a click on it is refused for want of a record.
     */
    void finalizeCard(SlackApprovalRecord record, String text) {
        try {
            var integration = channelTargetRouter.getIntegrationByName(CHANNEL_TYPE_SLACK, record.integrationName());
            String botToken = integration.map(ChannelIntegrationConfiguration::getPlatformConfig).map(c -> c.get("botToken")).orElse(null);
            if (botToken != null && !botToken.isBlank() && record.approvalChannelId() != null) {
                slackApi.updateMessage("Bearer " + botToken, record.approvalChannelId(), record.messageTs(), text,
                        SlackHitlSupport.buildResolvedBlocks(text));
                count("resolved");
            }
        } catch (SlackDeliveryException e) {
            LOGGER.warnf("Could not update the resolved Slack approval card for %s: %s", sanitize(record.subject()), e.getMessage());
        } finally {
            forget(record.integrationName(), record.subject(), record.pauseEpoch());
        }
    }
}
