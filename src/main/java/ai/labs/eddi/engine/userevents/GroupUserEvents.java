/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The user events of a group discussion, read off its persisted state.
 * <p>
 * Called from the two places every discussion passes through: the start of a
 * leg of {@code GroupConversationService.executeDiscussion} (a fresh start or a
 * continuation round) and the end of every leg, whatever ended it — and from
 * the cancel of a discussion that was paused and so had no running leg. At the
 * end of a leg the state says what happened: paused for approval, waiting for a
 * human member, or terminal. Reading the state rather than listening to each
 * branch means a new way of ending a discussion cannot forget to tell anyone.
 *
 * <h3>Who is told</h3>
 * <ul>
 * <li>The discussion's owner (who started it) and its HUMAN members (whose
 * {@code agentId} is their principal) — for start, end and a human turn.</li>
 * <li>For a human turn also the principal it waits for, if not a roster member
 * (a facilitator escalation names one); their copy says
 * {@code yourTurn: true}.</li>
 * <li>For an approval gate, the owner and every admin and approver — exactly
 * who may decide it.</li>
 * </ul>
 */
public final class GroupUserEvents {

    private static final Logger LOGGER = Logger.getLogger(GroupUserEvents.class);

    private GroupUserEvents() {
    }

    /** A leg started fresh (round 1) or as a continuation round. */
    public static void started(UserEventPublisher publisher, GroupConversation gc, AgentGroupConfiguration config) {
        if (publisher == null || gc == null) {
            return;
        }
        try {
            publisher.publish(event(UserEvent.KIND_TEAM_STARTED, gc).withState(stateOf(gc)), participants(gc, config));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not raise team.started: %s", e.getMessage());
        }
    }

    /**
     * A leg ended, or a paused discussion was cancelled: announces what its state
     * now is. A state that is still running announces nothing.
     */
    public static void afterLeg(UserEventPublisher publisher, GroupConversation gc, AgentGroupConfiguration config) {
        if (publisher == null || gc == null || gc.getState() == null) {
            return;
        }
        try {
            GroupConversationState state = gc.getState();
            switch (state) {
                case AWAITING_APPROVAL -> publisher.publish(
                        event(UserEvent.KIND_APPROVAL_PENDING, gc).withState(gc.getHitlPauseType() != null ? gc.getHitlPauseType().name() : null),
                        UserEventAudience.principal(gc.getUserId()).andApprovers());
                case AWAITING_HUMAN_INPUT -> {
                    String waitingFor = gc.getPendingHumanInput() != null ? gc.getPendingHumanInput().memberId() : null;
                    Set<String> principals = participantPrincipals(gc, config);
                    if (waitingFor != null && !waitingFor.isBlank()) {
                        principals.add(waitingFor);
                    }
                    publisher.publish(event(UserEvent.KIND_TEAM_WAITING_FOR_HUMAN, gc),
                            UserEventAudience.principals(principals).withYourTurn(waitingFor));
                }
                case COMPLETED, FAILED, REJECTED, CANCELLED, CLOSED -> publisher
                        .publish(event(UserEvent.KIND_TEAM_ENDED, gc).withState(state.name()), participants(gc, config));
                default -> {
                    // still running: nothing to say
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not raise the group's user event: %s", e.getMessage());
        }
    }

    private static UserEvent event(String kind, GroupConversation gc) {
        return UserEvent.of(kind).withSurface(UserEvent.SURFACE_GROUP).withGroup(gc.getGroupId(), gc.getId());
    }

    private static String stateOf(GroupConversation gc) {
        return gc.getState() != null ? gc.getState().name() : null;
    }

    private static UserEventAudience participants(GroupConversation gc, AgentGroupConfiguration config) {
        return UserEventAudience.principals(participantPrincipals(gc, config));
    }

    /** The owner and every HUMAN member of the roster. */
    static Set<String> participantPrincipals(GroupConversation gc, AgentGroupConfiguration config) {
        Set<String> principals = new LinkedHashSet<>();
        if (gc.getUserId() != null && !gc.getUserId().isBlank()) {
            principals.add(gc.getUserId());
        }
        if (config != null && config.getMembers() != null) {
            for (var member : config.getMembers()) {
                if (member != null && member.memberType() == AgentGroupConfiguration.MemberType.HUMAN && member.agentId() != null) {
                    principals.add(member.agentId());
                }
            }
        }
        return principals;
    }
}
