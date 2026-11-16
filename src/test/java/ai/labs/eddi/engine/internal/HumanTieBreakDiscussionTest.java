/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OptionsSource;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TiePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteMethod;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionRecord;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.HumanRequestDetail;
import ai.labs.eddi.configs.groups.model.HumanTieBreakConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.internal.groups.GroupConversationSchemaMigrations;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code tiePolicy: HUMAN_DECIDES} through the real discussion loop: a tied
 * vote pauses the discussion after persisting its tally, and a resume that
 * starts past the last phase still completes.
 */
@DisplayName("HUMAN_DECIDES through the discussion loop")
class HumanTieBreakDiscussionTest {

    private static final String GROUP_ID = "board";
    private static final String DECIDER = "founder@acme.com";

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
        var jsonSerialization = mock(IJsonSerialization.class);
        service = new GroupConversationService(groupStore, conversationStore, conversationService, agentFactory, templatingEngine,
                jsonSerialization, new SimpleMeterRegistry(), null, mock(IAgentStore.class), null, null,
                new CallerIdentityContext(null, null), "default", 3);
        when(conversationStore.create(any())).thenReturn("gc-1");
        lenient().when(jsonSerialization.serialize(any())).thenAnswer(inv -> inv.getArgument(0).toString());
        lenient().when(templatingEngine.processTemplate(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0, String.class));
    }

    private static AgentGroupConfiguration board(boolean voteIsLast) {
        var c = new AgentGroupConfiguration();
        c.setName("Growth Board");
        c.setStyle(DiscussionStyle.CUSTOM);
        c.setModeratorAgentId("mod");
        c.setMembers(List.of(new GroupMember("a1", "Alice", 1, null), new GroupMember("a2", "Bob", 2, null)));
        var vote = new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, List.of("SEO", "Paid pilot"), 0.5, Map.of(), false,
                TiePolicy.HUMAN_DECIDES, new HumanTieBreakConfig(DECIDER, "PT24H", null));
        var phases = new ArrayList<DiscussionPhase>();
        phases.add(new DiscussionPhase("Board vote", PhaseType.VOTE, "ALL", TurnOrder.PARALLEL, ContextScope.NONE, false, null, 1, false,
                null, false, vote));
        if (!voteIsLast) {
            phases.add(new DiscussionPhase("Plan", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1,
                    false));
        }
        c.setPhases(phases);
        c.setProtocol(new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 0, ProtocolConfig.MemberUnavailablePolicy.SKIP));
        return c;
    }

    private void storeReturns(AgentGroupConfiguration config) throws Exception {
        var rid = mock(IResourceStore.IResourceId.class);
        when(rid.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(rid);
        when(groupStore.read(GROUP_ID, 1)).thenReturn(config);
    }

    private void agentSays(String agentId, String reply) throws Exception {
        when(agentFactory.getLatestReadyAgent(any(Environment.class), eq(agentId))).thenReturn(mock(IAgent.class));
        when(conversationService.startConversation(any(Environment.class), eq(agentId), anyString(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-" + agentId, null));
        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(8);
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            output.put("output", List.of(reply));
            snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(any(Environment.class), eq(agentId), anyString(), any(), any(), any(), any(InputData.class),
                anyBoolean(), any(ConversationResponseHandler.class));
    }

    @Test
    @DisplayName("a tied vote pauses for the decider with the tally on record, and the next phase does not run")
    void tiePauses() throws Exception {
        storeReturns(board(false));
        agentSays("a1", "{\"vote\": \"SEO\"}");
        agentSays("a2", "{\"vote\": \"Paid pilot\"}");

        GroupConversation gc = service.discuss(GROUP_ID, "Which bet?", "owner", 0);

        assertEquals(GroupConversationState.AWAITING_HUMAN_INPUT, gc.getState());
        assertEquals(DECIDER, gc.getPendingHumanInput().memberId());
        assertTrue(gc.getPendingHumanInput().detail().isTieBreak());
        assertEquals(List.of("SEO", "Paid pilot"), gc.getPendingHumanInput().detail().options());
        assertEquals(DecisionType.NONE, gc.getDecision().type());
        assertEquals(0, gc.getResumePoint().phaseIdx());
        assertTrue(gc.getTranscript().stream().noneMatch(e -> "Plan".equals(e.phaseName())), "the phase after the vote waits");
        verify(conversationService, never()).startConversation(any(Environment.class), eq("mod"), anyString(), any());
    }

    @Test
    @DisplayName("resuming past a last-phase vote completes the discussion")
    void lastPhaseVoteCompletes() throws Exception {
        var config = board(true);
        var gc = new GroupConversation();
        gc.setId("gc-last");
        gc.setGroupId(GROUP_ID);
        gc.setUserId("owner");
        gc.setOriginalQuestion("Which bet?");
        gc.setState(GroupConversationState.IN_PROGRESS);
        gc.setDecision(new DecisionRecord(DecisionType.VOTE, "\"SEO\" chosen by Founder (tie break).", "SEO", Map.of(), List.of(),
                "vote+human-tiebreak", "Board vote", null));

        service.executeDiscussion(gc, config, service.resolvePhases(config), "Which bet?", null, config.getPhases().size());

        assertEquals(GroupConversationState.COMPLETED, gc.getState());
        assertEquals("SEO", gc.getDecision().winner());
    }

    @Test
    @DisplayName("the pause survives the production serializer, and an older pod refuses the v5 document")
    void roundTripAndSchemaGuard() throws Exception {
        var json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        var gc = new GroupConversation();
        gc.setId("gc-rt");
        gc.setSchemaVersion(GroupConversation.CURRENT_SCHEMA_VERSION);
        gc.setResumePoint(new GroupConversation.ResumePoint(2, 0, -1, GroupConversation.RESUME_KIND_TIE_BREAK));
        gc.setPendingHumanInput(new GroupConversation.PendingHumanInput(DECIDER, "Founder", 2, 0, -1, TranscriptEntryType.HUMAN_INPUT.name(),
                "Choose", "SKIP_TURN", Instant.now(), HumanRequestDetail.tieBreak(List.of("A", "B"), 5, "MODERATOR_DECIDES")));
        var config = board(false);

        var back = json.deserialize(json.serialize(gc), GroupConversation.class);
        var configBack = json.deserialize(json.serialize(config), AgentGroupConfiguration.class);

        assertEquals(gc.getPendingHumanInput().detail(), back.getPendingHumanInput().detail());
        assertEquals(GroupConversation.RESUME_KIND_TIE_BREAK, back.getResumePoint().pauseKind());
        assertEquals(new HumanTieBreakConfig(DECIDER, "PT24H", null), configBack.getPhases().getFirst().voteConfig().humanTieBreak());
        assertEquals(5, GroupConversation.CURRENT_SCHEMA_VERSION);

        var legacy = json.deserialize(json.serialize(new GroupConversation.PendingHumanInput("h", "H", 0, 0, 0, "OPINION", "p",
                "SKIP_TURN", Instant.now())), GroupConversation.PendingHumanInput.class);
        assertNull(legacy.detail(), "a pre-v5 pending turn has no detail");

        var newer = new GroupConversation();
        newer.setSchemaVersion(GroupConversation.CURRENT_SCHEMA_VERSION + 1);
        assertThrows(Exception.class, () -> GroupConversationSchemaMigrations.prepareForResume(newer));
    }
}
