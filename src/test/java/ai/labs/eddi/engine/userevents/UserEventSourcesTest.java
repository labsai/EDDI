/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.events.HitlPauseCommittedEvent;
import ai.labs.eddi.engine.hitl.audit.DecisionOutcome;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionRecorded;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where events come from: the HITL observers and the group hooks raise the
 * right kind for the right audience, and never put content in the event.
 */
@DisplayName("user event sources")
class UserEventSourcesTest {

    private UserEventPublisher publisher;
    private ArgumentCaptor<UserEvent> events;
    private ArgumentCaptor<UserEventAudience> audiences;

    @BeforeEach
    void setUp() {
        publisher = mock(UserEventPublisher.class);
        events = ArgumentCaptor.forClass(UserEvent.class);
        audiences = ArgumentCaptor.forClass(UserEventAudience.class);
    }

    private static String hash(String principal) {
        return KvKeys.sha256(principal);
    }

    @Nested
    @DisplayName("HITL")
    class Hitl {

        @Test
        @DisplayName("a committed pause tells the owner (while they may USE the agent) and every approver")
        void pauseCommitted() {
            when(publisher.conversationOwner("conv-1")).thenReturn(new UserEventPublisher.ConversationOwner("alice", "agent-1"));

            new UserEventObservers(publisher).onPauseCommitted(new HitlPauseCommittedEvent("conv-1", "p-1"));

            verify(publisher).publish(events.capture(), audiences.capture());
            assertEquals(UserEvent.KIND_APPROVAL_PENDING, events.getValue().kind());
            assertEquals("conv-1", events.getValue().conversationId());
            assertEquals(Set.of(hash("alice")), audiences.getValue().principalHashes());
            assertTrue(audiences.getValue().approvers());
            assertEquals("agent-1", audiences.getValue().useResourceId());
        }

        @Test
        @DisplayName("a pause whose owner cannot be read raises nothing")
        void pauseWithoutOwner() {
            new UserEventObservers(publisher).onPauseCommitted(new HitlPauseCommittedEvent("conv-1", "p-1"));

            verify(publisher, never()).publish(any(), any());
        }

        @Test
        @DisplayName("a group decision names the group discussion, its owner and the approvers")
        void groupDecision() {
            var unit = unit("group", "gc-1", null, "g-1", "bob", DecisionOutcome.values()[0]);

            new UserEventObservers(publisher).onDecisionRecorded(new HitlDecisionRecorded("a-1", List.of(unit)));

            verify(publisher).publish(events.capture(), audiences.capture());
            assertEquals(UserEvent.KIND_APPROVAL_DECIDED, events.getValue().kind());
            assertEquals(UserEvent.SURFACE_GROUP, events.getValue().surface());
            assertEquals("gc-1", events.getValue().groupConversationId());
            assertEquals("g-1", events.getValue().groupId());
            assertNull(events.getValue().conversationId());
            assertEquals(Set.of(hash("bob")), audiences.getValue().principalHashes());
            assertTrue(audiences.getValue().approvers());
        }

        @Test
        @DisplayName("a partly approved tool batch is MIXED")
        void mixedOutcome() {
            var outcomes = DecisionOutcome.values();
            var first = unit("regular", "conv-1", "agent-1", null, "alice", outcomes[0]);
            var second = unit("regular", "conv-1", "agent-1", null, "alice", outcomes[1]);

            assertEquals("MIXED", UserEventObservers.outcome(new HitlDecisionRecorded("a-1", List.of(first, second))));
            assertEquals(outcomes[0].name(), UserEventObservers.outcome(new HitlDecisionRecorded("a-1", List.of(first))));
        }

        private static HitlDecisionUnit unit(String surface, String conversationId, String agentId, String groupId, String userId,
                                             DecisionOutcome outcome) {
            return new HitlDecisionUnit("a-1", 2, surface, "RULE", conversationId, agentId, null, groupId, userId, null, "p-1",
                    Instant.now(), Instant.now(), 1L, "approver", false, null, null, null, HitlDecisionUnit.UnitKind.ANSWER, "answer",
                    outcome, null, null, null, null, null, null);
        }
    }

    @Nested
    @DisplayName("groups")
    class Groups {

        private GroupConversation gc(GroupConversationState state) {
            var gc = new GroupConversation();
            gc.setId("gc-1");
            gc.setGroupId("g-1");
            gc.setUserId("owner");
            gc.setState(state);
            gc.setOriginalQuestion("What is our secret launch date?");
            gc.setSynthesizedAnswer("The launch is on the 3rd.");
            gc.setHitlPauseReason("Waiting for input from Gregor — phase: Opinion");
            return gc;
        }

        private AgentGroupConfiguration config() {
            var config = new AgentGroupConfiguration();
            config.setMembers(List.of(new GroupMember("agent-1", "Analyst", 1, null, MemberType.AGENT),
                    new GroupMember("gregor@example.com", "Gregor", 2, null, MemberType.HUMAN)));
            return config;
        }

        @Test
        @DisplayName("start tells the owner and the HUMAN members, never an agent member")
        void started() {
            GroupUserEvents.started(publisher, gc(GroupConversationState.IN_PROGRESS), config());

            verify(publisher).publish(events.capture(), audiences.capture());
            assertEquals(UserEvent.KIND_TEAM_STARTED, events.getValue().kind());
            assertEquals(Set.of(hash("owner"), hash("gregor@example.com")), audiences.getValue().principalHashes());
            assertFalse(audiences.getValue().approvers());
        }

        @Test
        @DisplayName("a terminal state is team.ended with that state")
        void ended() {
            GroupUserEvents.afterLeg(publisher, gc(GroupConversationState.REJECTED), config());

            verify(publisher).publish(events.capture(), audiences.capture());
            assertEquals(UserEvent.KIND_TEAM_ENDED, events.getValue().kind());
            assertEquals("REJECTED", events.getValue().state());
        }

        @Test
        @DisplayName("an approval gate tells the owner and the approvers, not the human members")
        void approvalGate() {
            var gc = gc(GroupConversationState.AWAITING_APPROVAL);
            gc.setHitlPauseType(GroupConversation.HitlPauseType.PHASE);

            GroupUserEvents.afterLeg(publisher, gc, config());

            verify(publisher).publish(events.capture(), audiences.capture());
            assertEquals(UserEvent.KIND_APPROVAL_PENDING, events.getValue().kind());
            assertEquals("PHASE", events.getValue().state());
            assertEquals(Set.of(hash("owner")), audiences.getValue().principalHashes());
            assertTrue(audiences.getValue().approvers());
        }

        @Test
        @DisplayName("a human turn tells the participants and the principal it waits for, marking theirs")
        void humanTurn() {
            var gc = gc(GroupConversationState.AWAITING_HUMAN_INPUT);
            gc.setPendingHumanInput(new GroupConversation.PendingHumanInput("escalated@example.com", "Escalated", 0, 0, -1, "FOLLOW_UP",
                    "Rendered prompt with private context", "SKIP_TURN", Instant.now()));

            GroupUserEvents.afterLeg(publisher, gc, config());

            verify(publisher).publish(events.capture(), audiences.capture());
            assertEquals(UserEvent.KIND_TEAM_WAITING_FOR_HUMAN, events.getValue().kind());
            assertEquals(Set.of(hash("owner"), hash("gregor@example.com"), hash("escalated@example.com")),
                    audiences.getValue().principalHashes());
            assertEquals(hash("escalated@example.com"), audiences.getValue().yourTurnHash());
        }

        @Test
        @DisplayName("a still-running state raises nothing")
        void running() {
            GroupUserEvents.afterLeg(publisher, gc(GroupConversationState.IN_PROGRESS), config());

            verify(publisher, never()).publish(any(), any());
        }

        @Test
        @DisplayName("no group event carries the question, the answer, a reason or a prompt")
        void noContent() throws Exception {
            var json = new ObjectMapper();
            for (GroupConversationState state : GroupConversationState.values()) {
                var gc = gc(state);
                gc.setPendingHumanInput(new GroupConversation.PendingHumanInput("gregor@example.com", "Gregor", 0, 0, 1, "OPINION",
                        "Rendered prompt with private context", "SKIP_TURN", Instant.now()));
                GroupUserEvents.started(publisher, gc, config());
                GroupUserEvents.afterLeg(publisher, gc, config());
            }
            verify(publisher, atLeastOnce()).publish(events.capture(), any());
            for (UserEvent event : events.getAllValues()) {
                String wire = json.writeValueAsString(event);
                for (String content : List.of("secret launch", "launch is on", "Waiting for input", "Rendered prompt", "Gregor")) {
                    assertFalse(wire.contains(content), event.kind() + " leaked '" + content + "': " + wire);
                }
            }
        }
    }
}
