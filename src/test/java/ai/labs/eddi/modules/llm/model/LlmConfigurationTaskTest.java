/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LlmConfiguration.Task} logic methods: isAgentMode() and
 * getSystemMessage().
 */
class LlmConfigurationTaskTest {

    @Nested
    @DisplayName("isAgentMode")
    class IsAgentMode {

        @Test
        @DisplayName("empty task should not be agent mode")
        void emptyTask() {
            var task = new LlmConfiguration.Task();
            assertFalse(task.isAgentMode());
        }

        @Test
        @DisplayName("task with tools should be agent mode")
        void withTools() {
            var task = new LlmConfiguration.Task();
            task.setTools(List.of("eddi://tool1"));
            assertTrue(task.isAgentMode());
        }

        @Test
        @DisplayName("task with empty tools list should not be agent mode")
        void emptyToolsList() {
            var task = new LlmConfiguration.Task();
            task.setTools(List.of());
            assertFalse(task.isAgentMode());
        }

        @Test
        @DisplayName("task with enableBuiltInTools=true should be agent mode")
        void builtInToolsEnabled() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(true);
            assertTrue(task.isAgentMode());
        }

        @Test
        @DisplayName("task with enableBuiltInTools=false should not be agent mode")
        void builtInToolsDisabled() {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(false);
            assertFalse(task.isAgentMode());
        }

        @Test
        @DisplayName("task with a2aAgents should be agent mode")
        void withA2aAgents() {
            var task = new LlmConfiguration.Task();
            var agent = new LlmConfiguration.A2AAgentConfig();
            agent.setUrl("http://remote-agent");
            task.setA2aAgents(List.of(agent));
            assertTrue(task.isAgentMode());
        }

        @Test
        @DisplayName("enableHttpCallTools alone should NOT trigger agent mode")
        void httpCallToolsAloneNotAgentMode() {
            var task = new LlmConfiguration.Task();
            task.setEnableHttpCallTools(true);
            assertFalse(task.isAgentMode());
        }
    }

    @Nested
    @DisplayName("getSystemMessage")
    class GetSystemMessage {

        @Test
        @DisplayName("should return null when parameters is null")
        void nullParameters() {
            var task = new LlmConfiguration.Task();
            assertNull(task.getSystemMessage());
        }

        @Test
        @DisplayName("should return system message from parameters")
        void fromParameters() {
            var task = new LlmConfiguration.Task();
            task.setParameters(Map.of("systemMessage", "You are helpful"));
            assertEquals("You are helpful", task.getSystemMessage());
        }

        @Test
        @DisplayName("should return null when systemMessage key not present")
        void missingKey() {
            var task = new LlmConfiguration.Task();
            task.setParameters(Map.of("otherKey", "value"));
            assertNull(task.getSystemMessage());
        }
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("should have sensible defaults")
        void defaultValues() {
            var task = new LlmConfiguration.Task();
            assertEquals(10, task.getConversationHistoryLimit());
            assertEquals(-1, task.getMaxContextTokens());
            assertEquals(2, task.getAnchorFirstSteps());
            assertTrue(task.getEnableToolCaching());
            assertTrue(task.getEnableRateLimiting());
            assertEquals(100, task.getDefaultRateLimit());
            assertFalse(task.getEnableParallelExecution());
            assertEquals(30000L, task.getParallelExecutionTimeoutMs());
        }
    }
}
