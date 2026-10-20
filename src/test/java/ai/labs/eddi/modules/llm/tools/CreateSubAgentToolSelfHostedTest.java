/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.setup.AgentSetupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * WS10: a dynamic sub-agent cannot be put on a self-hosted
 * {@code openai-compatible} endpoint, because the tool has no base URL to pass.
 * The model gets a refusal it can act on, and nothing is created.
 */
@DisplayName("create_sub_agent refuses the self-hosted openai-compatible type clearly")
class CreateSubAgentToolSelfHostedTest {

    @Test
    void refusesWithAnActionableMessage() throws Exception {
        AgentSetupService agentSetupService = mock(AgentSetupService.class);
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowCreation(true);
        var tool = new CreateSubAgentTool(agentSetupService, mock(IConversationService.class), "0123456789abcdef01234567", "user-1", config,
                new ArrayList<>(), new HashSet<>());

        String result = tool.createSubAgent("helper", "Be helpful.", " OpenAI-Compatible ", "granite", null, false);

        assertTrue(result.contains("cannot target a self-hosted OpenAI-compatible endpoint"), result);
        assertTrue(result.contains("baseUrl") && result.contains("another provider"), result);
        verify(agentSetupService, never()).setupAgent(any(), any());
    }
}
