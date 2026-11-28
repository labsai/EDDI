/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DiscussionOutcome;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupMembersUnresolvableException;
import ai.labs.eddi.engine.internal.groups.DiscussionOutcomes;
import ai.labs.eddi.engine.internal.groups.TurnBudget;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The turn cap keeps the synthesis turn (live GR2, K3), cancel is honoured
 * before every speaker and every repeat (live GR3, K2), and a group no member
 * of which can take a turn is refused up front (live GR4).
 */
class GroupTurnCapAndCancelTest {

    private static final String GROUP_ID = "group-cap";
    private static final String GC_ID = "gc-cap";

    private IAgentGroupStore groupStore;
    private IConversationService conversationService;
    private IAgentFactory agentFactory;
    private GroupConversationService service;
    private final AtomicInteger says = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        groupStore = mock(IAgentGroupStore.class);
        var conversationStore = mock(IGroupConversationStore.class);
        conversationService = mock(IConversationService.class);
        agentFactory = mock(IAgentFactory.class);
        service = new GroupConversationService(groupStore, conversationStore, conversationService, agentFactory,
                mock(ITemplatingEngine.class), mock(IJsonSerialization.class), new SimpleMeterRegistry(), mock(AgentSigningService.class),
                mock(IAgentStore.class), mock(IScheduleStore.class), null, new CallerIdentityContext(null, null), "default", 3);
        doReturn(new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP_ID;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        }).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(GC_ID).when(conversationStore).create(any());
        doAnswer(inv -> new IConversationService.ConversationResult("conv-" + inv.getArgument(1), null))
                .when(conversationService).startConversation(any(), anyString(), any(), any());
    }

    private void givenRoundTable(int members, int rounds) throws Exception {
        var config = new AgentGroupConfiguration();
        config.setName("Cap");
        config.setStyle(DiscussionStyle.ROUND_TABLE);
        config.setMaxRounds(rounds);
        config.setMembers(IntStream.range(0, members).mapToObj(i -> new GroupMember("m" + i, "Member " + i, i, null)).toList());
        config.setModeratorAgentId("mod");
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn(mock(IAgent.class)).when(agentFactory).getLatestReadyAgent(any(), anyString());
    }

    /** Every say answers; {@code onSay} runs first with the 1-based call count. */
    private void givenAnsweringMembers(IntConsumer onSay) throws Exception {
        doAnswer(inv -> {
            int n = says.incrementAndGet();
            onSay.accept(n);
            IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            var output = new ConversationOutput();
            output.put("output", List.of("Answer " + n));
            snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    private void cancel(ControlSignal mode) {
        try {
            service.cancelDiscussion(GC_ID, mode);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * GR2: ten members, five rounds, the default 50 turns. It used to spend all 50
     * on opinions and complete without a synthesis or a note.
     */
    @Test
    void defaultTurnCap_stillEndsWithTheSynthesis_andSaysItWasCut() throws Exception {
        givenRoundTable(10, 5);
        givenAnsweringMembers(n -> {
        });

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", "user", 0);

        assertEquals(50, says.get(), "49 opinions and the synthesis");
        assertNotNull(gc.getSynthesizedAnswer());
        assertTrue(gc.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.SYNTHESIS));
        assertTrue(gc.getTranscript().stream().anyMatch(e -> e.errorReason() != null && e.errorReason().startsWith(TurnBudget.CUT_NOTE_PREFIX)),
                "the cut is recorded in the transcript");
        assertEquals(GroupConversationState.COMPLETED, gc.getState());
        assertEquals(DiscussionOutcome.ANSWERED, gc.getOutcome());
        assertEquals(DiscussionOutcomes.TURN_LIMIT_REACHED, gc.getOutcomeReason());
    }

    @Test
    void cancelBetweenSpeakers_stopsBeforeTheNextOne() throws Exception {
        givenRoundTable(3, 2);
        givenAnsweringMembers(n -> {
            if (n == 1) {
                cancel(ControlSignal.CANCEL_GRACEFUL);
            }
        });

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", "user", 0);

        assertEquals(1, says.get(), "the running turn finishes, nobody else is asked");
        assertEquals(GroupConversationState.CANCELLED, gc.getState());
    }

    @Test
    void cancelInALaterRepeat_stopsBeforeTheNextRepeat() throws Exception {
        // One member, maxRounds 6: Initial Opinions + Discussion (5 repeats) +
        // Synthesis.
        givenRoundTable(1, 6);
        givenAnsweringMembers(n -> {
            if (n == 2) {
                cancel(ControlSignal.CANCEL_GRACEFUL);
            }
        });

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", "user", 0);

        assertEquals(2, says.get(), "no further repeat runs after the cancel (it used to run every remaining one)");
        assertEquals(GroupConversationState.CANCELLED, gc.getState());
    }

    /** GR4: an unknown agent id used to complete as an ordinary success. */
    @Test
    void groupWhoseMembersCannotTakeATurn_endsWithoutAnAnswerAndSaysWhy() throws Exception {
        givenRoundTable(2, 2);
        doReturn(null).when(agentFactory).getLatestReadyAgent(any(), anyString());

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", "user", 0);

        verify(conversationService, never()).startConversation(any(), anyString(), any(), any());
        assertEquals(GroupConversationState.COMPLETED, gc.getState());
        assertEquals(DiscussionOutcome.NO_ANSWER, gc.getOutcome());
        assertEquals(DiscussionOutcomes.NO_MEMBER_ANSWERED, gc.getOutcomeReason());
    }

    @Test
    void groupWithoutMembers_isRefused() throws Exception {
        givenRoundTable(0, 2);

        assertThrows(GroupMembersUnresolvableException.class, () -> service.discuss(GROUP_ID, "Q?", "user", 0));
        verify(conversationService, never()).say(any(), eq("mod"), any(), any(), any(), any(), any(), anyBoolean(), any());
    }
}
