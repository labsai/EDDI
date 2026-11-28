/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import ai.labs.eddi.engine.events.HitlPauseCommittedEvent;
import ai.labs.eddi.engine.hitl.audit.DecisionOutcome;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionRecord;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionRecorded;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Objects;

/**
 * Turns the engine's HITL events into user events. Both events are fired
 * asynchronously after the fact is durable, so nothing here runs on a turn's
 * critical path, and a failure here is logged and forgotten.
 * <ul>
 * <li>{@link HitlPauseCommittedEvent} — a 1:1 conversation paused for approval:
 * {@code approval.pending} for its owner (while they may still {@code USE} its
 * agent, as resuming requires) and for every admin and approver.</li>
 * <li>{@link HitlDecisionRecorded} — a pending approval of either surface was
 * decided, cancelled or timed out: {@code approval.decided} for the owner, and
 * for admins and approvers so their inboxes drop it. It is raised with the
 * {@code hitl.approval} audit entry, so with the audit ledger switched off
 * ({@code eddi.audit.enabled=false}) no decided event is sent.</li>
 * </ul>
 * Group pauses and group lifecycle events are raised by
 * {@code GroupConversationService} itself (see {@link GroupUserEvents}).
 */
@ApplicationScoped
public class UserEventObservers {

    private static final Logger LOGGER = Logger.getLogger(UserEventObservers.class);

    private final UserEventPublisher publisher;

    @Inject
    public UserEventObservers(UserEventPublisher publisher) {
        this.publisher = publisher;
    }

    void onPauseCommitted(@ObservesAsync HitlPauseCommittedEvent pause) {
        try {
            var owner = publisher.conversationOwner(pause.conversationId());
            if (owner == null) {
                return;
            }
            UserEvent event = UserEvent.of(UserEvent.KIND_APPROVAL_PENDING).withSurface(UserEvent.SURFACE_REGULAR)
                    .withConversation(pause.conversationId(), owner.agentId());
            UserEventAudience audience = UserEventAudience.principal(owner.userId()).andApprovers();
            if (owner.agentId() != null) {
                audience = audience.requiringUse(owner.agentId());
            }
            publisher.publish(event, audience);
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not raise approval.pending: %s", e.getMessage());
        }
    }

    void onDecisionRecorded(@ObservesAsync HitlDecisionRecorded decision) {
        try {
            if (decision.units().isEmpty()) {
                return;
            }
            HitlDecisionUnit first = decision.units().getFirst();
            UserEvent event = UserEvent.of(UserEvent.KIND_APPROVAL_DECIDED).withState(outcome(decision));
            if (HitlDecisionRecord.SURFACE_GROUP.equals(first.surface())) {
                event = event.withSurface(UserEvent.SURFACE_GROUP).withGroup(first.groupId(), first.conversationId());
            } else {
                event = event.withSurface(UserEvent.SURFACE_REGULAR).withConversation(first.conversationId(), first.agentId());
            }
            publisher.publish(event, UserEventAudience.principal(first.userId()).andApprovers());
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not raise approval.decided: %s", e.getMessage());
        }
    }

    /**
     * The decision's outcome: the one every unit shares, or {@code MIXED} when a
     * tool batch was partly approved.
     */
    static String outcome(HitlDecisionRecorded decision) {
        var outcomes = decision.units().stream().map(HitlDecisionUnit::decisionOutcome).filter(Objects::nonNull).map(Enum::name).distinct()
                .toList();
        if (outcomes.isEmpty()) {
            return null;
        }
        return outcomes.size() == 1 ? outcomes.getFirst() : DecisionOutcome.MIXED;
    }
}
