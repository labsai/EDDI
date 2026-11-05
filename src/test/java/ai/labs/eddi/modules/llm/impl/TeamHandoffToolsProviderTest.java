/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptPolicy;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.GroupHandoffConfig;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.HandoffTarget;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.TeamHandoffConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffRequest;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffResult;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffResult.Outcome;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffService;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamHandoffToolsProviderTest {

    static final String GC_ID = "gc-1";
    static final String GROUP_ID = "group-1";
    static final String MEMBER_CONV = "member-conv-1";
    static final String MODERATOR = "agent-mod";

    LiveDiscussionRegistry registry;
    IAgentGroupStore groupStore;
    TeamHandoffService service;
    AgentGroupConfiguration group;

    @BeforeEach
    void setUp() throws Exception {
        registry = new LiveDiscussionRegistry();
        groupStore = mock(IAgentGroupStore.class);
        service = mock(TeamHandoffService.class);
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setUserId("user-1");
        gc.getMemberConversationIds().put(MODERATOR, MEMBER_CONV);
        registry.register(gc);
        group = new AgentGroupConfiguration();
        group.setModeratorAgentId(MODERATOR);
        group.setMembers(List.of(new GroupMember(MODERATOR, "Mod", 0, "Judge", null), new GroupMember("agent-dev", "Dev", 1, "Developer", null)));
        // Every target group exists unless a test says otherwise.
        lenient().when(groupStore.getCurrentResourceId(any())).thenAnswer(inv -> resourceId(inv.getArgument(0)));
        lenient().when(groupStore.read(GROUP_ID, 1)).thenAnswer(inv -> group);
    }

    static IResourceStore.IResourceId resourceId(String id) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }

    static GroupHandoffConfig groupPolicy(String participants, HandoffTarget... targets) {
        return new GroupHandoffConfig(List.of(targets), participants, 0, 0, 0, null, null);
    }

    static HandoffTarget target(String alias) {
        return new HandoffTarget(alias, "g-" + alias, "desc of " + alias, null);
    }

    TeamHandoffToolsProvider provider() {
        return new TeamHandoffToolsProvider(registry, groupStore, service);
    }

    ToolAssemblyContext standalone(TeamHandoffConfig policy, Boolean builtIns, List<String> whitelist) {
        var memory = mock(IConversationMemory.class);
        lenient().when(memory.getAgentId()).thenReturn("agent-1");
        lenient().when(memory.getConversationId()).thenReturn("conv-1");
        lenient().when(memory.getUserId()).thenReturn("user-1");
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(builtIns);
        task.setTeamHandoff(policy);
        return new ToolAssemblyContext(memory, task, whitelist, null, "user-1", "agent-1", null);
    }

    ToolAssemblyContext inGroup(String agentId, String conversationId, TeamHandoffConfig agentPolicy) {
        var memory = mock(IConversationMemory.class);
        lenient().when(memory.getAgentId()).thenReturn(agentId);
        lenient().when(memory.getConversationId()).thenReturn(conversationId);
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setTeamHandoff(agentPolicy);
        return new ToolAssemblyContext(memory, task, null, null, "user-1", agentId, GC_ID);
    }

    static TeamHandoffConfig agentPolicy(String... aliases) {
        return new TeamHandoffConfig(Arrays.stream(aliases).map(TeamHandoffToolsProviderTest::target).toList(), 0, 0, 0);
    }

    static List<String> aliasesOf(ToolContribution c) {
        var schema = (JsonObjectSchema) c.specs().get(0).parameters();
        return ((JsonEnumSchema) schema.properties().get("team")).enumValues();
    }

    // -------------------------------------------------------------- standalone

    @Test
    void noPolicyMeansNoTool() {
        assertTrue(provider().contribute(standalone(null, true, null)).specs().isEmpty());
        assertTrue(provider().contribute(standalone(new TeamHandoffConfig(null, 0, 0, 0), true, null)).specs().isEmpty());
    }

    @Test
    void aStandaloneAgentWithAPolicyGetsOneToolTaggedTeam() {
        var c = provider().contribute(standalone(agentPolicy("desk", "legal"), true, null));
        assertEquals(1, c.specs().size());
        assertEquals("handoff_to_team", c.specs().get(0).name());
        assertEquals("team", c.toolSources().get("handoff_to_team"));
        assertEquals("handoff_to_team", c.toolCanonicalNames().get("handoff_to_team"));
        assertNotNull(c.executors().get("handoff_to_team"));
        assertEquals(List.of("desk", "legal"), aliasesOf(c));
    }

    @Test
    void theBuiltInToolsSwitchGatesTheTool() {
        assertTrue(provider().contribute(standalone(agentPolicy("desk"), false, null)).specs().isEmpty());
        assertTrue(provider().contribute(standalone(agentPolicy("desk"), null, null)).specs().isEmpty());
        assertFalse(provider().contribute(standalone(agentPolicy("desk"), true, null)).specs().isEmpty());
    }

    @Test
    void aNarrowedWhitelistMustNameTheTool() {
        assertTrue(provider().contribute(standalone(agentPolicy("desk"), true, List.of("calculator"))).specs().isEmpty());
        assertFalse(provider().contribute(standalone(agentPolicy("desk"), true, List.of("calculator", "team_handoff"))).specs().isEmpty());
    }

    @Test
    void aTargetThatDoesNotExistIsNotOffered() throws Exception {
        when(groupStore.getCurrentResourceId("g-gone")).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
        var c = provider().contribute(standalone(agentPolicy("desk", "gone"), true, null));
        assertEquals(List.of("desk"), aliasesOf(c));
        assertTrue(provider().contribute(standalone(agentPolicy("gone"), true, null)).specs().isEmpty(), "no usable target means no tool");
    }

    @Test
    void withoutAServiceThereIsNoTool() {
        var noService = new TeamHandoffToolsProvider(registry, groupStore, null);
        assertTrue(noService.contribute(standalone(agentPolicy("desk"), true, null)).specs().isEmpty());
    }

    // ------------------------------------------------------------------- group

    @Test
    void theGroupPolicyGovernsInsideADiscussion() {
        group.setHandoffConfig(groupPolicy(null, target("group-side")));
        var c = provider().contribute(inGroup(MODERATOR, MEMBER_CONV, agentPolicy("agent-side")));
        assertEquals(List.of("group-side"), aliasesOf(c), "the agent's own targets must not leak into a group turn");
    }

    @Test
    void theAgentsOwnPolicyIsIgnoredInAGroupThatHasNone() {
        group.setHandoffConfig(null);
        assertTrue(provider().contribute(inGroup(MODERATOR, MEMBER_CONV, agentPolicy("agent-side"))).specs().isEmpty());
        group.setHandoffConfig(groupPolicy(null));
        assertTrue(provider().contribute(inGroup(MODERATOR, MEMBER_CONV, agentPolicy("agent-side"))).specs().isEmpty());
    }

    @Test
    void anUnverifiedGroupConversationNeverFallsBackToTheAgentPolicy() {
        group.setHandoffConfig(groupPolicy(null, target("group-side")));
        // A forged groupConversationId: this conversation is not a member of GC_ID.
        assertTrue(provider().contribute(inGroup(MODERATOR, "someone-elses-conversation", agentPolicy("agent-side"))).specs().isEmpty());
    }

    @Test
    void theModeratorIsTheDefaultParticipant() {
        group.setHandoffConfig(groupPolicy(null, target("desk")));
        assertFalse(provider().contribute(inGroup(MODERATOR, MEMBER_CONV, null)).specs().isEmpty());
        // Developer is a member of the live discussion too, but not the moderator.
        registry.get(GC_ID).orElseThrow().getMemberConversationIds().put("agent-dev", "dev-conv");
        assertTrue(provider().contribute(inGroup("agent-dev", "dev-conv", null)).specs().isEmpty());
    }

    @Test
    void allAndRoleParticipants() {
        registry.get(GC_ID).orElseThrow().getMemberConversationIds().put("agent-dev", "dev-conv");
        group.setHandoffConfig(groupPolicy("ALL", target("desk")));
        assertFalse(provider().contribute(inGroup("agent-dev", "dev-conv", null)).specs().isEmpty());
        group.setHandoffConfig(groupPolicy("ROLE:developer", target("desk")));
        assertFalse(provider().contribute(inGroup("agent-dev", "dev-conv", null)).specs().isEmpty(), "role match is case-insensitive");
        assertTrue(provider().contribute(inGroup(MODERATOR, MEMBER_CONV, null)).specs().isEmpty());
    }

    @Test
    void aGroupWithOnlyAcceptNeverSends() {
        group.setHandoffConfig(new GroupHandoffConfig(null, "ALL", 0, 0, 0, null,
                new AcceptPolicy(null, 0, 0)));
        assertTrue(provider().contribute(inGroup(MODERATOR, MEMBER_CONV, null)).specs().isEmpty());
    }

    @Test
    void theAgentBuiltInSwitchStillBindsInsideAGroup() {
        group.setHandoffConfig(groupPolicy("ALL", target("desk")));
        var memory = mock(IConversationMemory.class);
        lenient().when(memory.getConversationId()).thenReturn(MEMBER_CONV);
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(false);
        var ctx = new ToolAssemblyContext(memory, task, null, null, "user-1", MODERATOR, GC_ID);
        assertTrue(provider().contribute(ctx).specs().isEmpty(), "a group cannot widen an agent whose own config has no built-in tools");
    }

    @Test
    void anUnreadableGroupConfigWithholdsTheTool() throws Exception {
        when(groupStore.read(GROUP_ID, 1)).thenThrow(new IResourceStore.ResourceStoreException("db down"));
        assertTrue(provider().contribute(inGroup(MODERATOR, MEMBER_CONV, null)).specs().isEmpty());
    }

    @Test
    void theGroupRunQuotaIsTheDiscussionsPersistedLedger() {
        group.setHandoffConfig(new GroupHandoffConfig(List.of(target("desk")), "ALL", 0, 2, 0, null, null));
        var c = provider().contribute(inGroup(MODERATOR, MEMBER_CONV, null));
        when(service.file(any())).thenAnswer(inv -> {
            var request = (HandoffRequest) inv.getArgument(0);
            return request.runQuota().tryReserve()
                    ? new HandoffResult(Outcome.QUEUED_FOR_REVIEW, "h", null, "ok")
                    : HandoffResult.of(Outcome.RUN_CAPPED, "capped");
        });
        var exec = c.executors().get("handoff_to_team");
        String args = "{\"team\":\"desk\",\"subject\":\"s\",\"description\":\"d\"}";
        var req = ToolExecutionRequest.builder().name("handoff_to_team").arguments(args).build();
        // maxPerDiscussion = 2 for the whole discussion, across freshly built tool
        // instances.
        assertEquals("ok", exec.execute(req, "m"));
        var second = provider().contribute(inGroup(MODERATOR, MEMBER_CONV, null)).executors().get("handoff_to_team");
        assertEquals("ok", second.execute(req, "m"));
        var third = provider().contribute(inGroup(MODERATOR, MEMBER_CONV, null)).executors().get("handoff_to_team");
        assertEquals("capped", third.execute(req, "m"));
        var state = registry.get(GC_ID).orElseThrow().getHandoffState();
        assertEquals(2, state.getUsed());
        assertEquals(3, state.getFiled().size(), "every attempt is recorded, refused ones too");
        assertEquals(3, state.drainEvents().size());
    }

    // ------------------------------------------------------------- run quota

    @Test
    void theConversationQuotaCountsEarlierStepsAndThisTurn() {
        var quota = new TeamHandoffToolsProvider.ConversationRunQuota(2, 3);
        assertTrue(quota.tryReserve());
        assertFalse(quota.tryReserve());
        quota.release();
        assertTrue(quota.tryReserve());
    }
}
