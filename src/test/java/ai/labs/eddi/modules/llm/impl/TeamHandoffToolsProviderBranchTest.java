/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.HandoffTarget;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.TeamHandoffConfig;
import ai.labs.eddi.configs.groups.model.SharedArtifact;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffRequest;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffResult;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffResult.Outcome;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffService;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.modules.llm.impl.TeamHandoffToolsProviderTest.GC_ID;
import static ai.labs.eddi.modules.llm.impl.TeamHandoffToolsProviderTest.GROUP_ID;
import static ai.labs.eddi.modules.llm.impl.TeamHandoffToolsProviderTest.MEMBER_CONV;
import static ai.labs.eddi.modules.llm.impl.TeamHandoffToolsProviderTest.MODERATOR;
import static ai.labs.eddi.modules.llm.impl.TeamHandoffToolsProviderTest.agentPolicy;
import static ai.labs.eddi.modules.llm.impl.TeamHandoffToolsProviderTest.groupPolicy;
import static ai.labs.eddi.modules.llm.impl.TeamHandoffToolsProviderTest.resourceId;
import static ai.labs.eddi.modules.llm.impl.TeamHandoffToolsProviderTest.target;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bookkeeping and refusal branches of {@link TeamHandoffToolsProvider} that
 * {@link TeamHandoffToolsProviderTest} does not reach: the standalone filed
 * ledger in step data, artifact references, unusable targets and the group
 * participant rules for unusual rosters.
 */
class TeamHandoffToolsProviderBranchTest {

    private static final String ARGS = "{\"team\":\"desk\",\"subject\":\"s\",\"description\":\"d\"}";

    private LiveDiscussionRegistry registry;
    private IAgentGroupStore groupStore;
    private TeamHandoffService service;
    private AgentGroupConfiguration group;
    private GroupConversation gc;

    @BeforeEach
    void setUp() throws Exception {
        registry = new LiveDiscussionRegistry();
        groupStore = mock(IAgentGroupStore.class);
        service = mock(TeamHandoffService.class);
        gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setUserId("user-1");
        gc.getMemberConversationIds().put(MODERATOR, MEMBER_CONV);
        registry.register(gc);
        group = new AgentGroupConfiguration();
        group.setModeratorAgentId(MODERATOR);
        group.setMembers(List.of(new GroupMember(MODERATOR, "Mod", 0, null, null), new GroupMember("agent-dev", "Dev", 1, " Developer ", null)));
        lenient().when(groupStore.getCurrentResourceId(any())).thenAnswer(inv -> resourceId(inv.getArgument(0)));
        lenient().when(groupStore.read(GROUP_ID, 1)).thenAnswer(inv -> group);
    }

    private TeamHandoffToolsProvider provider() {
        return new TeamHandoffToolsProvider(registry, groupStore, service);
    }

    private static ToolAssemblyContext standalone(IConversationMemory memory, TeamHandoffConfig policy) {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setTeamHandoff(policy);
        return new ToolAssemblyContext(memory, task, null, null, "user-1", "agent-1", null);
    }

    private ToolAssemblyContext inGroup(String agentId, String conversationId) {
        var memory = mock(IConversationMemory.class);
        lenient().when(memory.getConversationId()).thenReturn(conversationId);
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        return new ToolAssemblyContext(memory, task, null, null, "user-1", agentId, GC_ID);
    }

    private static String run(ToolContribution contribution, String args) throws Exception {
        var request = ToolExecutionRequest.builder().name("handoff_to_team").arguments(args).build();
        return contribution.executors().get("handoff_to_team").execute(request, "m");
    }

    // -------------------------------------------------------------- standalone

    @Test
    void aStandaloneHandoffThatIsFiledIsRecordedInTheCurrentStep() throws Exception {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>(TeamHandoffToolsProvider.FILED_KEY, new ArrayList<>(List.of("h-0"))));
        when(service.file(any())).thenReturn(new HandoffResult(Outcome.FILED, "h-1", null, "filed"));

        var contribution = provider().contribute(standalone(memory, agentPolicy("desk")));
        assertEquals("filed", run(contribution, ARGS));

        assertEquals(List.of("h-0", "h-1"), memory.getCurrentStep().getLatestData(TeamHandoffToolsProvider.FILED_KEY).getResult());
        assertEquals(2, TeamHandoffToolsProvider.countFiled(memory));
    }

    @Test
    void aRefusedStandaloneHandoffIsNotCounted() throws Exception {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        when(service.file(any())).thenReturn(HandoffResult.of(Outcome.RUN_CAPPED, "capped"));

        assertEquals("capped", run(provider().contribute(standalone(memory, agentPolicy("desk"))), ARGS));
        assertNull(memory.getCurrentStep().getLatestData(TeamHandoffToolsProvider.FILED_KEY));
        assertEquals(0, TeamHandoffToolsProvider.countFiled(memory));
    }

    @Test
    void theConversationCapCountsEarlierSteps() throws Exception {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>(TeamHandoffToolsProvider.FILED_KEY, List.of("h-0")));
        memory.startNextStep();
        when(service.file(any())).thenAnswer(inv -> ((HandoffRequest) inv.getArgument(0)).runQuota().tryReserve()
                ? new HandoffResult(Outcome.FILED, "h", null, "ok")
                : HandoffResult.of(Outcome.RUN_CAPPED, "capped"));
        // maxPerConversation of one, already used in the previous step.
        var policy = new TeamHandoffConfig(List.of(target("desk")), 0, 1, 0);
        assertEquals("capped", run(provider().contribute(standalone(memory, policy)), ARGS));
    }

    @Test
    void countFiledIgnoresEntriesThatAreNotLists() {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>(TeamHandoffToolsProvider.FILED_KEY, "not-a-list"));
        memory.startNextStep();
        memory.getCurrentStep().storeData(new Data<>(TeamHandoffToolsProvider.FILED_KEY, List.of("a", "b")));
        memory.startNextStep();
        assertEquals(2, TeamHandoffToolsProvider.countFiled(memory));

        var noSteps = mock(IConversationMemory.class);
        assertEquals(0, TeamHandoffToolsProvider.countFiled(noSteps));
    }

    @Test
    void aMemoryWithoutAnAgentOrNoTaskGetsNoTool() {
        var noAgent = mock(IConversationMemory.class);
        assertTrue(provider().contribute(standalone(noAgent, agentPolicy("desk"))).specs().isEmpty());
        assertTrue(provider().contribute(standalone(null, agentPolicy("desk"))).specs().isEmpty());
        var noTask = new ToolAssemblyContext(new ConversationMemory("c", "a", 1, "u"), null, null, null, "u", "a", null);
        assertTrue(provider().contribute(noTask).specs().isEmpty());
    }

    @Test
    void unusableTargetsAreNotOffered() throws Exception {
        when(groupStore.getCurrentResourceId("g-missing")).thenReturn(null);
        var targets = new ArrayList<HandoffTarget>();
        targets.add(new HandoffTarget("blank", " ", null, null));
        targets.add(new HandoffTarget("nogroup", null, null, null));
        targets.add(target("missing"));
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        assertTrue(provider().contribute(standalone(memory, new TeamHandoffConfig(targets, 0, 0, 0))).specs().isEmpty());

        targets.add(target("desk"));
        var withDesk = provider().contribute(standalone(memory, new TeamHandoffConfig(targets, 0, 0, 0)));
        assertEquals(List.of("desk"), TeamHandoffToolsProviderTest.aliasesOf(withDesk));
    }

    @Test
    void withoutAGroupStoreEveryDeclaredTargetIsOffered() {
        var provider = new TeamHandoffToolsProvider(registry, null, service);
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        assertEquals(List.of("desk"), TeamHandoffToolsProviderTest.aliasesOf(provider.contribute(standalone(memory, agentPolicy("desk")))));
        assertTrue(provider.contribute(inGroup(MODERATOR, MEMBER_CONV)).specs().isEmpty(), "a group turn needs the group store");
    }

    // ------------------------------------------------------------------- group

    @Test
    void withoutARegistryAGroupTurnHasNoTool() {
        group.setHandoffConfig(groupPolicy("ALL", target("desk")));
        var provider = new TeamHandoffToolsProvider(null, groupStore, service);
        assertTrue(provider.contribute(inGroup(MODERATOR, MEMBER_CONV)).specs().isEmpty());
    }

    @Test
    void aGroupTurnWithoutMemoryIsNotVerifiedAsAMember() {
        group.setHandoffConfig(groupPolicy("ALL", target("desk")));
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        var ctx = new ToolAssemblyContext(null, task, null, null, "user-1", MODERATOR, GC_ID);
        assertTrue(provider().contribute(ctx).specs().isEmpty());
    }

    @Test
    void aGroupThatNoLongerExistsWithholdsTheTool() throws Exception {
        group.setHandoffConfig(groupPolicy("ALL", target("desk")));
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(null);
        assertTrue(provider().contribute(inGroup(MODERATOR, MEMBER_CONV)).specs().isEmpty());

        gc.setGroupId(null);
        assertTrue(provider().contribute(inGroup(MODERATOR, MEMBER_CONV)).specs().isEmpty());
    }

    @Test
    void aReferenceNamingASharedArtifactIsResolvedToItsId() throws Exception {
        group.setHandoffConfig(groupPolicy("ALL", target("desk")));
        var other = new SharedArtifact();
        other.setId("art-0");
        other.setName("Draft");
        var spec = new SharedArtifact();
        spec.setId("art-1");
        spec.setName("Spec");
        var artifacts = new ArrayList<SharedArtifact>();
        artifacts.add(null);
        artifacts.add(other);
        artifacts.add(spec);
        gc.setArtifacts(artifacts);
        when(service.file(any())).thenReturn(new HandoffResult(Outcome.FILED, "h", null, "ok"));

        var contribution = provider().contribute(inGroup(MODERATOR, MEMBER_CONV));
        run(contribution, "{\"team\":\"desk\",\"subject\":\"s\",\"description\":\"d\",\"reference\":\"spec\"}");
        run(contribution, "{\"team\":\"desk\",\"subject\":\"s\",\"description\":\"d\",\"reference\":\"unknown\"}");

        var captor = ArgumentCaptor.forClass(HandoffRequest.class);
        verify(service, times(2)).file(captor.capture());
        assertEquals("art-1", captor.getAllValues().get(0).artifactId(), "matched by name, ignoring case");
        assertNull(captor.getAllValues().get(1).artifactId());
        assertEquals("spec", captor.getAllValues().get(0).reference());
    }

    @Test
    void aDiscussionWithoutArtifactsResolvesNoReference() throws Exception {
        group.setHandoffConfig(groupPolicy("ALL", target("desk")));
        gc.setArtifacts(null);
        when(service.file(any())).thenReturn(new HandoffResult(Outcome.FILED, "h", null, "ok"));
        run(provider().contribute(inGroup(MODERATOR, MEMBER_CONV)),
                "{\"team\":\"desk\",\"subject\":\"s\",\"description\":\"d\",\"reference\":\"x\"}");
        var captor = ArgumentCaptor.forClass(HandoffRequest.class);
        verify(service).file(captor.capture());
        assertNull(captor.getValue().artifactId());
    }

    @Test
    void participantRulesForUnusualRosters() {
        // No agent id: only ALL admits.
        assertTrue(TeamHandoffToolsProvider.participates(groupPolicy("ALL"), group, gc, null));
        assertFalse(TeamHandoffToolsProvider.participates(groupPolicy("MODERATOR"), group, gc, null));
        // A member without a role never matches a role rule; a padded role does.
        assertFalse(TeamHandoffToolsProvider.participates(groupPolicy("ROLE:judge"), group, gc, MODERATOR));
        assertTrue(TeamHandoffToolsProvider.participates(groupPolicy("ROLE: developer"), group, gc, "agent-dev"));
        // A role rule names a role, not an agent outside the roster.
        assertFalse(TeamHandoffToolsProvider.participates(groupPolicy("ROLE:developer"), group, gc, "stranger"));
    }

    @Test
    void sourceIsTheTeamTag() {
        assertEquals("team", provider().source());
    }
}
