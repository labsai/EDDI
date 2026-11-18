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
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DiscussionOutcome;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TurnOutcome;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.internal.groups.DiscussionOutcomes;
import ai.labs.eddi.engine.internal.groups.MemberTurnExecutor;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Fault injection per discussion style preset (the review's A+ bar for the
 * group engine): a provider failure for one member, for every member, and for
 * the moderator. A failed turn is never an answer, and a discussion without an
 * answer never looks like one (live GR1).
 */
class GroupFaultInjectionTest {

    private static final String GROUP_ID = "group-fault";
    private static final String MODERATOR = "mod";
    private static final String HEALTHY_ANSWER = "Response";

    private IAgentGroupStore groupStore;
    private IGroupConversationStore conversationStore;
    private IConversationService conversationService;
    private IAgentFactory agentFactory;
    private GroupConversationService service;

    @BeforeEach
    void setUp() throws Exception {
        groupStore = mock(IAgentGroupStore.class);
        conversationStore = mock(IGroupConversationStore.class);
        conversationService = mock(IConversationService.class);
        agentFactory = mock(IAgentFactory.class);
        var templatingEngine = mock(ITemplatingEngine.class);
        doAnswer(inv -> inv.getArgument(0)).when(templatingEngine).processTemplate(anyString(), any(), any());
        service = new GroupConversationService(groupStore, conversationStore, conversationService, agentFactory,
                templatingEngine, mock(IJsonSerialization.class), new SimpleMeterRegistry(), mock(AgentSigningService.class),
                mock(IAgentStore.class), mock(IScheduleStore.class), null, new CallerIdentityContext(null, null), "default", 3);
        doReturn(mock(IAgent.class)).when(agentFactory).getLatestReadyAgent(any(), anyString());
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
        doReturn("gc-fault").when(conversationStore).create(any());
    }

    /** Members {@code a} and {@code b}; roles cover DEBATE and DEVIL_ADVOCATE. */
    private void givenGroup(DiscussionStyle style) throws Exception {
        var config = new AgentGroupConfiguration();
        config.setName("Fault " + style);
        config.setStyle(style);
        config.setMaxRounds(2);
        config.setMembers(List.of(new GroupMember("a", "Alpha", 1, style == DiscussionStyle.DEBATE ? "PRO" : null),
                new GroupMember("b", "Beta", 2, style == DiscussionStyle.DEBATE ? "CON" : "DEVIL_ADVOCATE")));
        config.setModeratorAgentId(MODERATOR);
        config.setProtocol(new ProtocolConfig(5, MemberFailurePolicy.SKIP, 0, MemberUnavailablePolicy.SKIP, 50, null, CostPolicy.SYNTHESIZE_NOW));
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
    }

    /**
     * Every member conversation starts; the agents named in {@code failing} answer
     * like a provider outage (conversation ERROR, no output), the rest answer.
     */
    private void givenProviderFailureFor(Set<String> failing) throws Exception {
        doAnswer(inv -> new IConversationService.ConversationResult("conv-" + inv.getArgument(1), null))
                .when(conversationService).startConversation(any(), anyString(), any(), any());
        doAnswer(inv -> {
            String agentId = inv.getArgument(1);
            IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
            var snapshot = new SimpleConversationMemorySnapshot();
            if (failing.contains(agentId)) {
                snapshot.setConversationState(ConversationState.ERROR);
            } else {
                snapshot.setConversationState(ConversationState.READY);
                var output = new ConversationOutput();
                output.put("output", List.of(HEALTHY_ANSWER));
                snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
            }
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    private static void assertNoFailedTurnCarriesContent(GroupConversation gc) {
        for (TranscriptEntry e : gc.getTranscript()) {
            if (e.outcome() != TurnOutcome.ANSWERED) {
                assertNull(e.content(), "a turn that did not answer has no content: " + e);
            }
            if (e.content() != null) {
                assertTrue(!e.content().contains("Agent failed to produce output"), "the old placeholder is gone: " + e);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = DiscussionStyle.class, mode = EnumSource.Mode.EXCLUDE, names = "CUSTOM")
    void everyAgentDown_failsTheDiscussionWithoutAnAnswer(DiscussionStyle style) throws Exception {
        givenGroup(style);
        givenProviderFailureFor(Set.of("a", "b", MODERATOR));

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", "user", 0);

        assertEquals(GroupConversationState.FAILED, gc.getState(), style.name());
        assertEquals(DiscussionOutcome.FAILED, gc.getOutcome(), style.name());
        assertEquals(DiscussionOutcomes.NO_MEMBER_ANSWERED, gc.getOutcomeReason(), style.name());
        assertNull(gc.getSynthesizedAnswer(), style.name());
        assertTrue(gc.getTranscript().stream().anyMatch(e -> e.outcome() == TurnOutcome.FAILED), style.name());
        assertNoFailedTurnCarriesContent(gc);
    }

    @ParameterizedTest
    @EnumSource(value = DiscussionStyle.class, mode = EnumSource.Mode.EXCLUDE, names = "CUSTOM")
    void moderatorDown_isNeverAnAnswer(DiscussionStyle style) throws Exception {
        givenGroup(style);
        givenProviderFailureFor(Set.of(MODERATOR));

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", "user", 0);

        assertNull(gc.getSynthesizedAnswer(), style.name());
        assertNotEquals(DiscussionOutcome.ANSWERED, gc.getOutcome(), style.name());
        if (style == DiscussionStyle.TASK_FORCE) {
            // The moderator also plans and verifies: whatever the members did, there is
            // no answer, and FAILED comes with the FAILED state only.
            assertEquals(gc.getOutcome() == DiscussionOutcome.FAILED, gc.getState() == GroupConversationState.FAILED, style.name());
        } else {
            assertEquals(GroupConversationState.COMPLETED, gc.getState(), style.name());
            assertEquals(DiscussionOutcome.NO_ANSWER, gc.getOutcome(), style.name());
            assertEquals(DiscussionOutcomes.SYNTHESIS_FAILED, gc.getOutcomeReason(), style.name());
        }
        assertNoFailedTurnCarriesContent(gc);
    }

    /** M2: dissent recording leaves a NONE decision record — that is no answer. */
    @Test
    void recordDissents_withAFailedSynthesis_isStillNoAnswer() throws Exception {
        givenGroup(DiscussionStyle.ROUND_TABLE);
        var config = groupStore.read(GROUP_ID, 1);
        config.setRecordDissents(true);
        givenProviderFailureFor(Set.of(MODERATOR));

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", "user", 0);

        assertEquals(GroupConversationState.COMPLETED, gc.getState());
        assertEquals(DiscussionOutcome.NO_ANSWER, gc.getOutcome());
        assertEquals(DiscussionOutcomes.SYNTHESIS_FAILED, gc.getOutcomeReason());
    }

    /**
     * No failed turn's text or reason reaches another member: not in a peer's
     * prompt, not in the synthesis prompt, not in the transcript handed along as
     * context.
     */
    @Test
    void failedTurns_neverReachThePeersOrTheSynthesisPrompt() throws Exception {
        givenGroup(DiscussionStyle.ROUND_TABLE);
        givenProviderFailureFor(Set.of("b"));

        service.discuss(GROUP_ID, "Q?", "user", 0);

        var inputs = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService, atLeastOnce()).say(any(), any(), any(), any(), any(), any(), inputs.capture(), anyBoolean(), any());
        for (InputData input : inputs.getAllValues()) {
            assertFalse(input.getInput().contains(MemberTurnExecutor.REASON_ERROR_STATE), input.getInput());
            assertFalse(input.getInput().contains("Agent failed"), input.getInput());
            var transcript = input.getContext().get("groupTranscript");
            if (transcript != null && transcript.getValue() instanceof List<?> entries) {
                for (Object o : entries) {
                    var e = (TranscriptEntry) o;
                    assertNotEquals("b", e.speakerAgentId(), "the failed member's record is not handed to peers: " + e);
                }
            }
        }
    }

    private GroupConversation completedDiscussion(int answeredRounds, TranscriptEntry... entries) throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc-old");
        gc.setGroupId(GROUP_ID);
        gc.setUserId("user");
        gc.setState(GroupConversationState.COMPLETED);
        gc.setRound(1);
        if (answeredRounds >= 0) {
            gc.setAnsweredRounds(answeredRounds);
        }
        gc.getTranscript().add(new TranscriptEntry("user", "User", "Q1", -1, "Question", GroupConversation.TranscriptEntryType.QUESTION,
                Instant.now(), null, null));
        for (var e : entries) {
            gc.getTranscript().add(e);
        }
        doReturn(gc).when(conversationStore).read("gc-old");
        // The store's CAS moves the stored (here: the same) document to IN_PROGRESS.
        doAnswer(inv -> {
            gc.setState(GroupConversationState.IN_PROGRESS);
            return true;
        }).when(conversationStore).compareAndSetState("gc-old", GroupConversationState.COMPLETED, GroupConversationState.IN_PROGRESS);
        return gc;
    }

    /**
     * M1: continuing a discussion stored with the old placeholder never quotes it.
     */
    @Test
    void continuingALegacyDiscussion_neverQuotesThePlaceholder() throws Exception {
        givenGroup(DiscussionStyle.ROUND_TABLE);
        givenProviderFailureFor(Set.of());
        completedDiscussion(-1, new TranscriptEntry("b", "Beta", "[Agent failed to produce output — conversation entered ERROR state]", 0,
                "Initial Opinions", GroupConversation.TranscriptEntryType.OPINION, Instant.now(), null, null));

        service.continueDiscussion("gc-old", "Q2", null);

        var inputs = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService, atLeastOnce()).say(any(), any(), any(), any(), any(), any(), inputs.capture(), anyBoolean(), any());
        for (InputData input : inputs.getAllValues()) {
            assertFalse(input.getInput().contains("Agent failed to produce output"), input.getInput());
            var transcript = input.getContext().get("groupTranscript");
            if (transcript != null && transcript.getValue() instanceof List<?> entries) {
                assertTrue(entries.stream().noneMatch(o -> ((TranscriptEntry) o).content() != null
                        && ((TranscriptEntry) o).content().contains("Agent failed to produce output")));
            }
        }
    }

    /**
     * M3: a continuation round that fails during an outage reports FAILED as its
     * outcome but leaves the discussion COMPLETED — continuable again.
     */
    @Test
    void aFailedContinuationRound_leavesTheDiscussionContinuable() throws Exception {
        givenGroup(DiscussionStyle.ROUND_TABLE);
        givenProviderFailureFor(Set.of("a", "b", MODERATOR));
        completedDiscussion(1, new TranscriptEntry("a", "Alpha", "round 1 view", 0, "Initial Opinions",
                GroupConversation.TranscriptEntryType.OPINION, Instant.now(), null, null));

        GroupConversation gc = service.continueDiscussion("gc-old", "Q2", null);

        assertEquals(GroupConversationState.COMPLETED, gc.getState());
        assertEquals(DiscussionOutcome.FAILED, gc.getOutcome(), "the outcome describes the latest round");
        assertEquals(DiscussionOutcomes.NO_MEMBER_ANSWERED, gc.getOutcomeReason());
        assertNull(gc.getSynthesizedAnswer());
    }

    @ParameterizedTest
    @EnumSource(value = DiscussionStyle.class, mode = EnumSource.Mode.EXCLUDE, names = "CUSTOM")
    void oneMemberDown_theOthersStillAnswer(DiscussionStyle style) throws Exception {
        givenGroup(style);
        givenProviderFailureFor(Set.of("b"));

        GroupConversation gc = service.discuss(GROUP_ID, "Q?", "user", 0);

        assertEquals(GroupConversationState.COMPLETED, gc.getState(), style.name());
        assertEquals(DiscussionOutcome.ANSWERED, gc.getOutcome(), style.name());
        assertNoFailedTurnCarriesContent(gc);
        assertTrue(gc.getTranscript().stream().filter(e -> "b".equals(e.speakerAgentId())).allMatch(e -> e.content() == null),
                style.name() + ": the failed member is never quoted as a position");
    }
}
