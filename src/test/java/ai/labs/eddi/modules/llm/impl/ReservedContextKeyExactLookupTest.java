/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every reader of an engine-reserved context key must use an exact-key lookup.
 * <p>
 * {@code IConversationStep#getLatestData} and
 * {@code IConversationStepStack#getAllLatestData} match by <em>prefix</em>. A
 * client-sent context key such as {@code groupIdSuffix} is not reserved by
 * name, so {@code ReservedContextKeys.stripFromExternal} used to let it
 * through, and {@code Conversation} stores it as {@code context:groupIdSuffix}
 * — which a prefix lookup of {@code context:groupId} then returned as this
 * conversation's group (CodeRabbit on PR #831, CWE-863). The same held for
 * every other reserved key.
 * <p>
 * These tests run against a real {@link ConversationMemory}, not mocks, so they
 * exercise the real lookup semantics: stubbing {@code getLatestData} with the
 * exact key would pass whether or not the reader matched by prefix.
 */
class ReservedContextKeyExactLookupTest {

    private static ConversationMemory memory() {
        return new ConversationMemory("conv-1", "agent-1", 1, "user-1");
    }

    private static void putContext(ConversationMemory memory, String key, Object value) {
        memory.getCurrentStep().storeData(new Data<Object>("context:" + key, new Context(Context.ContextType.object, value)));
    }

    @Test
    @DisplayName("a client-sent groupIdSuffix on the current step grants no group scope")
    void groupIdSuffix_isNotAGroupId() {
        var memory = memory();
        putContext(memory, "groupIdSuffix", "another-teams-group");

        assertEquals(List.of(), ContextualToolsProvider.resolveGroupIds(memory));
        assertEquals(List.of(), ContextualToolsProvider.resolveGroupIds(memory, new LiveDiscussionRegistry()));
    }

    @Test
    @DisplayName("a groupIdSuffix stored after the real groupId does not shadow it")
    void groupIdSuffix_doesNotShadowTheRealGroupId() {
        var memory = memory();
        putContext(memory, "groupId", "my-group");
        putContext(memory, "groupIdSuffix", "another-teams-group");

        assertEquals(List.of("my-group"), ContextualToolsProvider.resolveGroupIds(memory));
    }

    @Test
    @DisplayName("an earlier step's groupIdSuffix is not paired with a real discussion")
    void groupIdSuffix_onAnEarlierStep_isIgnored() {
        var memory = memory();
        // Earlier step: the verified pair the orchestrator wrote, then a forged
        // suffix key stored after it — a prefix lookup would return the forgery and
        // the registry check would compare it against gc-1's group.
        putContext(memory, "groupId", "my-group");
        putContext(memory, "groupConversationId", "gc-1");
        putContext(memory, "groupIdSuffix", "another-teams-group");
        memory.startNextStep();

        var registry = new LiveDiscussionRegistry();
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("my-group");
        gc.getMemberConversationIds().put("agent-1", "conv-1");
        registry.register(gc);

        assertEquals(List.of("my-group"), ContextualToolsProvider.resolveGroupIds(memory, registry));
    }

    @Test
    @DisplayName("a client-sent dynamicAgentConfigX does not make a standalone agent a group member with a policy of its choosing")
    void dynamicAgentConfigX_isNotAGroupPolicy() {
        var memory = memory();
        putContext(memory, "dynamicAgentConfigX", Map.of("enabled", true, "allowCreation", true, "maxCreatedAgentsPerDiscussion", 999));

        assertFalse(DynamicAgentToolsProvider.hasGroupPolicy(memory));
        assertEquals(DynamicAgentToolsProvider.createDefaultDynamicConfig().getMaxCreatedAgentsPerDiscussion(),
                DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory).getMaxCreatedAgentsPerDiscussion());
    }

    @Test
    @DisplayName("a client-sent dynamicCreatedAgentIdsX does not add agents to the created (tear-down-able) list")
    void dynamicCreatedAgentIdsX_isNotTheCreatedList() {
        var memory = memory();
        putContext(memory, "dynamicCreatedAgentIdsX", List.of("someone-elses-agent"));

        assertTrue(DynamicAgentToolsProvider.seedCreatedAgentIds(memory).isEmpty());
    }

    @Test
    @DisplayName("a client-sent delegationDepthX=0 does not reset a delegated conversation's depth")
    void delegationDepthX_doesNotResetTheDepth() {
        var memory = memory();
        putContext(memory, "delegationDepth", 3);
        memory.startNextStep();
        putContext(memory, "delegationDepthX", 0);

        assertEquals(3, DynamicAgentToolsProvider.resolveDelegationDepth(memory));
    }

    @Test
    @DisplayName("a client-sent groupConversationIdX is not this turn's discussion")
    void groupConversationIdX_isNotADiscussion() {
        var memory = memory();
        putContext(memory, "groupConversationIdX", "gc-someone-else");

        assertNull(AgentOrchestrator.groupConversationIdOf(memory));
    }
}
