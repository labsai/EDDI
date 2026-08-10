/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.modules.llm.tools.CreateSubAgentTool;
import ai.labs.eddi.modules.llm.tools.TeardownAgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Two guardrail properties of the dynamic-agent tool assembly.
 * <p>
 * <b>V7 — an omitted whitelist.</b> {@code docs/langchain.md} states twice that
 * omitting {@code builtInToolsWhitelist} enables all built-in tools, and
 * {@code BuiltinToolsProvider} implements exactly that. This provider alone
 * returned early, so an agent with {@code enableBuiltInTools=true}, no
 * whitelist and {@code dynamicAgents.enabled=true} got none of them. The
 * omitted case is now honoured — but only under a governing group policy, since
 * {@code dynamicAgents} is a group-config field and a standalone conversation
 * has no surface on which an operator could have declined.
 * <p>
 * <b>The created-agent cap.</b> {@code maxCreatedAgentsPerDiscussion} is
 * documented as a per-discussion cap but was enforced per member conversation:
 * the seed only ever saw the ids in the member's OWN memory, so a 5-member
 * group with the default cap of 5 could deploy 25 agents to production. A
 * teardown must also give a slot back, which it could not while the id
 * reappeared from an earlier step on the next turn's seed.
 *
 * @author ginccc
 */
@DisplayName("DynamicAgentToolsProvider — whitelist semantics and the created-agent cap")
class DynamicAgentWhitelistAndCapTest {

    private DynamicAgentToolsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DynamicAgentToolsProvider(mock(AgentSetupService.class), mock(CapabilityRegistryService.class),
                mock(IConversationService.class), mock(IAgentFactory.class), mock(IAgentStore.class),
                mock(IDeploymentStore.class), new LiveDiscussionRegistry(), mock(IAgentGroupStore.class));
    }

    private static ConversationMemory memory() {
        return new ConversationMemory("conv-a", "agent-a", 1, "user-1");
    }

    private static DynamicAgentConfig permissiveGroupPolicy() {
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowCreation(true);
        config.setAllowRecruitment(true);
        config.setAllowDelegation(true);
        return config;
    }

    private static void putGroupPolicy(ConversationMemory memory, DynamicAgentConfig config) {
        memory.getCurrentStep().storeData(new Data<>(DynamicAgentToolsProvider.CONTEXT_DYNAMIC_AGENT_CONFIG,
                new Context(Context.ContextType.object, config)));
    }

    private static <T> T findTool(List<Object> tools, Class<T> type) {
        return tools.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    @Nested
    @DisplayName("V7 — an omitted whitelist")
    class OmittedWhitelist {

        @Test
        @DisplayName("under a group policy, enables the dynamic tools like every other built-in")
        void omittedWhitelistUnderAGroupPolicyEnablesThem() {
            var memory = memory();
            putGroupPolicy(memory, permissiveGroupPolicy());
            List<Object> tools = new ArrayList<>();

            provider.addDynamicAgentTools(tools, null, memory);

            assertNotNull(findTool(tools, CreateSubAgentTool.class),
                    "an omitted whitelist means all built-in tools — docs/langchain.md says so twice");
            assertNotNull(findTool(tools, TeardownAgentTool.class));
        }

        @Test
        @DisplayName("an empty whitelist behaves the same as an omitted one")
        void emptyWhitelistBehavesLikeOmitted() {
            var memory = memory();
            putGroupPolicy(memory, permissiveGroupPolicy());
            List<Object> tools = new ArrayList<>();

            provider.addDynamicAgentTools(tools, List.of(), memory);

            assertNotNull(findTool(tools, CreateSubAgentTool.class));
        }

        @Test
        @DisplayName("without a group policy, still requires the explicit entry")
        void standaloneStillRequiresTheExplicitEntry() {
            List<Object> tools = new ArrayList<>();

            provider.addDynamicAgentTools(tools, null, memory());

            assertNull(findTool(tools, CreateSubAgentTool.class),
                    "dynamicAgents is a group-config field, so a standalone conversation has no surface on which "
                            + "an operator could have declined production-deploying capabilities");
            assertTrue(tools.isEmpty());
        }

        @Test
        @DisplayName("a group policy that disables creation still suppresses the tool")
        void aDisablingGroupPolicyStillWins() {
            var locked = new DynamicAgentConfig();
            locked.setEnabled(true);
            locked.setAllowCreation(false);
            locked.setAllowRecruitment(false);
            locked.setAllowDelegation(false);

            var memory = memory();
            putGroupPolicy(memory, locked);
            List<Object> tools = new ArrayList<>();

            provider.addDynamicAgentTools(tools, null, memory);

            var createTool = findTool(tools, CreateSubAgentTool.class);
            // The tool object may be assembled, but the guardrail refuses the call.
            if (createTool != null) {
                assertTrue(createTool.createSubAgent("x", "y", null, null, null, null).contains("not enabled"),
                        "allowCreation=false must refuse the call whatever the whitelist said");
            }
        }

        @Test
        @DisplayName("an explicit whitelist is still an exact allow-list")
        void explicitWhitelistStaysExact() {
            var memory = memory();
            putGroupPolicy(memory, permissiveGroupPolicy());
            List<Object> tools = new ArrayList<>();

            provider.addDynamicAgentTools(tools, List.of("create_sub_agent"), memory);

            assertNotNull(findTool(tools, CreateSubAgentTool.class));
            assertNull(findTool(tools, TeardownAgentTool.class), "teardown_agent was not whitelisted");
        }
    }

    @Nested
    @DisplayName("the created-agent cap")
    class CreatedAgentCap {

        @Test
        @DisplayName("counts the discussion-wide total injected by the group, not just this conversation")
        void seedIncludesTheDiscussionWideTotal() {
            var memory = memory();
            memory.getCurrentStep().storeData(new Data<>(DynamicAgentToolsProvider.CONTEXT_DYNAMIC_CREATED_AGENT_IDS,
                    new Context(Context.ContextType.object, List.of("made-by-member-b", "made-by-member-c"))));

            List<String> seeded = DynamicAgentToolsProvider.seedCreatedAgentIds(memory);

            assertEquals(2, seeded.size(),
                    "without the discussion-wide total the cap bounded each member conversation independently");
            assertTrue(seeded.containsAll(List.of("made-by-member-b", "made-by-member-c")));
        }

        @Test
        @DisplayName("unions the group total with this conversation's own history")
        void seedUnionsBothSources() {
            var memory = memory();
            memory.getCurrentStep().storeData(new Data<Object>(MemoryKeys.DYNAMIC_CREATED_AGENT_IDS,
                    new ArrayList<>(List.of("made-here"))));
            memory.startNextStep();
            memory.getCurrentStep().storeData(new Data<>(DynamicAgentToolsProvider.CONTEXT_DYNAMIC_CREATED_AGENT_IDS,
                    new Context(Context.ContextType.object, List.of("made-by-member-b"))));

            List<String> seeded = DynamicAgentToolsProvider.seedCreatedAgentIds(memory);

            assertEquals(2, seeded.size(), "both sources count: " + seeded);
        }

        @Test
        @DisplayName("a torn-down agent frees a slot")
        void teardownFreesASlot() {
            var memory = memory();
            memory.getCurrentStep().storeData(new Data<Object>(MemoryKeys.DYNAMIC_CREATED_AGENT_IDS,
                    new ArrayList<>(List.of("agent-1", "agent-2"))));
            memory.getCurrentStep().storeData(new Data<Object>(MemoryKeys.DYNAMIC_TORN_DOWN_AGENT_IDS,
                    new ArrayList<>(List.of("agent-1"))));
            memory.startNextStep();

            List<String> seeded = DynamicAgentToolsProvider.seedCreatedAgentIds(memory);

            assertEquals(List.of("agent-2"), seeded,
                    "an agent that no longer exists must not keep occupying a maxCreatedAgentsPerDiscussion slot");
        }

        @Test
        @DisplayName("a teardown outranks the group's total, which may not have observed it yet")
        void teardownOutranksTheGroupTotal() {
            var memory = memory();
            // An earlier turn tore agent-1 down...
            memory.getCurrentStep().storeData(new Data<Object>(MemoryKeys.DYNAMIC_TORN_DOWN_AGENT_IDS,
                    new ArrayList<>(List.of("agent-1"))));
            memory.startNextStep();
            // ...but the total the group injects for THIS turn still names it, because
            // propagateDynamicAgentTracking has not folded the teardown in yet. The
            // context is read from the current step only — that is where each member
            // turn's context lands.
            memory.getCurrentStep().storeData(new Data<>(DynamicAgentToolsProvider.CONTEXT_DYNAMIC_CREATED_AGENT_IDS,
                    new Context(Context.ContextType.object, List.of("agent-1", "agent-2"))));

            List<String> seeded = DynamicAgentToolsProvider.seedCreatedAgentIds(memory);

            assertFalse(seeded.contains("agent-1"));
            assertTrue(seeded.contains("agent-2"));
        }
    }
}
