/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conversation once governed by a group's dynamic-agent policy stays governed
 * by it on a turn that carries no group context — a turn its owner sent into
 * the member conversation directly, rather than the group orchestrator.
 */
@DisplayName("DynamicAgentToolsProvider — group policy carries over to later turns")
class DynamicAgentGroupPolicyCarryOverTest {

    private static final String CONVERSATION_ID = "6a1b2c3d4e5f60718293a4c6";
    private static final String AGENT_ID = "6a1b2c3d4e5f60718293a4b5";

    private static DynamicAgentConfig lockedDown() {
        var config = new DynamicAgentConfig();
        config.setEnabled(false);
        config.setAllowCreation(false);
        config.setAllowRecruitment(false);
        config.setAllowDelegation(false);
        return config;
    }

    private static void putPolicy(ConversationMemory memory, DynamicAgentConfig config) {
        memory.getCurrentStep().storeData(new Data<>(DynamicAgentToolsProvider.CONTEXT_DYNAMIC_AGENT_CONFIG,
                new Context(Context.ContextType.object, config)));
    }

    @Test
    @DisplayName("a turn without group context still resolves the group's policy")
    void policyFromEarlierStepGoverns() {
        var memory = new ConversationMemory(CONVERSATION_ID, AGENT_ID, 1, "user-1");
        putPolicy(memory, lockedDown());
        memory.startNextStep();

        var resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

        assertFalse(resolved.isEnabled(), "the permissive standalone default must not replace the group's disabled policy");
        assertFalse(resolved.isAllowCreation());
        assertFalse(resolved.isAllowRecruitment());
        assertFalse(resolved.isAllowDelegation());
        assertTrue(DynamicAgentToolsProvider.hasGroupPolicy(memory));
    }

    @Test
    @DisplayName("the most recent policy wins over an older one")
    void mostRecentPolicyWins() {
        var memory = new ConversationMemory(CONVERSATION_ID, AGENT_ID, 1, "user-1");
        var permissive = new DynamicAgentConfig();
        permissive.setEnabled(true);
        permissive.setAllowCreation(true);
        putPolicy(memory, permissive);
        memory.startNextStep();
        putPolicy(memory, lockedDown());
        memory.startNextStep();

        assertFalse(DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory).isEnabled());
    }

    @Test
    @DisplayName("a conversation that never had a group policy stays standalone")
    void standaloneStaysStandalone() {
        var memory = new ConversationMemory(CONVERSATION_ID, AGENT_ID, 1, "user-1");
        memory.startNextStep();

        assertFalse(DynamicAgentToolsProvider.hasGroupPolicy(memory));
        assertTrue(DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory).isEnabled());
    }
}
