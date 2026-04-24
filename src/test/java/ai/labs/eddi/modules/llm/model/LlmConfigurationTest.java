/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers all inner static classes of LlmConfiguration — defaults, round-trip
 * get/set, and helper methods. Pure data-model tests (no CDI, no I/O).
 */
class LlmConfigurationTest {

    // ==================== Record ====================

    @Test
    @DisplayName("LlmConfiguration record stores tasks")
    void record_storesTasks() {
        var task = new Task();
        task.setType("openai");
        var config = new LlmConfiguration(List.of(task));

        assertEquals(1, config.tasks().size());
        assertEquals("openai", config.tasks().getFirst().getType());
    }

    @Test
    @DisplayName("LlmConfiguration record accepts null tasks")
    void record_nullTasks() {
        var config = new LlmConfiguration(null);
        assertNull(config.tasks());
    }

    // ==================== Task ====================

    @Nested
    @DisplayName("Task")
    class TaskTests {

        @Test
        @DisplayName("defaults are backward-compatible")
        void defaults() {
            var task = new Task();

            assertFalse(task.getEnableBuiltInTools());
            assertTrue(task.getEnableHttpCallTools());
            assertTrue(task.getEnableMcpCallTools());
            assertEquals(10, task.getConversationHistoryLimit());
            assertEquals(-1, task.getMaxContextTokens());
            assertEquals(2, task.getAnchorFirstSteps());
            assertFalse(task.getEnableWorkflowRag());
            assertTrue(task.getEnableCostTracking());
            assertTrue(task.getEnableToolCaching());
            assertTrue(task.getEnableRateLimiting());
            assertEquals(100, task.getDefaultRateLimit());
            assertFalse(task.getEnableParallelExecution());
            assertEquals(30000L, task.getParallelExecutionTimeoutMs());
            assertNull(task.getMaxToolIterations());
            assertNull(task.getModelCascade());
            assertNull(task.getToolResponseLimits());
            assertNull(task.getConversationSummary());
            assertNull(task.getActions());
            assertNull(task.getId());
            assertNull(task.getType());
            assertNull(task.getDescription());
            assertNull(task.getPreRequest());
            assertNull(task.getParameters());
            assertNull(task.getResponseObjectName());
            assertNull(task.getResponseMetadataObjectName());
            assertNull(task.getPostResponse());
            assertNull(task.getTools());
            assertNull(task.getA2aAgents());
            assertNull(task.getBuiltInToolsWhitelist());
            assertNull(task.getKnowledgeBases());
            assertNull(task.getRagDefaults());
            assertNull(task.getHttpCallRag());
            assertNull(task.getRetry());
            assertNull(task.getMaxBudgetPerConversation());
            assertNull(task.getToolRateLimits());
        }

        @Test
        @DisplayName("round-trip all setters")
        void roundTrip() {
            var task = new Task();
            task.setActions(List.of("greet", "help"));
            task.setId("task-1");
            task.setType("anthropic");
            task.setDescription("Test task");
            task.setResponseObjectName("result");
            task.setResponseMetadataObjectName("meta");
            task.setTools(List.of("eddi://tool1"));
            task.setEnableBuiltInTools(true);
            task.setEnableHttpCallTools(false);
            task.setEnableMcpCallTools(false);
            task.setBuiltInToolsWhitelist(List.of("calculator"));
            task.setConversationHistoryLimit(5);
            task.setMaxContextTokens(4000);
            task.setAnchorFirstSteps(3);
            task.setEnableWorkflowRag(true);
            task.setHttpCallRag("fetchDocs");
            task.setMaxBudgetPerConversation(2.5);
            task.setEnableCostTracking(false);
            task.setEnableToolCaching(false);
            task.setEnableRateLimiting(false);
            task.setDefaultRateLimit(50);
            task.setToolRateLimits(Map.of("calc", 10));
            task.setEnableParallelExecution(true);
            task.setParallelExecutionTimeoutMs(60000L);
            task.setMaxToolIterations(20);
            task.setParameters(Map.of("systemMessage", "Hello"));

            assertEquals(List.of("greet", "help"), task.getActions());
            assertEquals("task-1", task.getId());
            assertEquals("anthropic", task.getType());
            assertEquals("Test task", task.getDescription());
            assertEquals("result", task.getResponseObjectName());
            assertEquals("meta", task.getResponseMetadataObjectName());
            assertEquals(List.of("eddi://tool1"), task.getTools());
            assertTrue(task.getEnableBuiltInTools());
            assertFalse(task.getEnableHttpCallTools());
            assertFalse(task.getEnableMcpCallTools());
            assertEquals(List.of("calculator"), task.getBuiltInToolsWhitelist());
            assertEquals(5, task.getConversationHistoryLimit());
            assertEquals(4000, task.getMaxContextTokens());
            assertEquals(3, task.getAnchorFirstSteps());
            assertTrue(task.getEnableWorkflowRag());
            assertEquals("fetchDocs", task.getHttpCallRag());
            assertEquals(2.5, task.getMaxBudgetPerConversation());
            assertFalse(task.getEnableCostTracking());
            assertFalse(task.getEnableToolCaching());
            assertFalse(task.getEnableRateLimiting());
            assertEquals(50, task.getDefaultRateLimit());
            assertEquals(Map.of("calc", 10), task.getToolRateLimits());
            assertTrue(task.getEnableParallelExecution());
            assertEquals(60000L, task.getParallelExecutionTimeoutMs());
            assertEquals(20, task.getMaxToolIterations());
            assertEquals("Hello", task.getSystemMessage());
        }

        @Test
        @DisplayName("isAgentMode with a2aAgents")
        void isAgentMode_a2a() {
            var task = new Task();
            task.setA2aAgents(List.of(new A2AAgentConfig()));
            assertTrue(task.isAgentMode());
        }

        @Test
        @DisplayName("isAgentMode false with empty a2a list")
        void isAgentMode_emptyA2a() {
            var task = new Task();
            task.setA2aAgents(List.of());
            assertFalse(task.isAgentMode());
        }
    }

    // ==================== ToolResponseLimits ====================

    @Nested
    @DisplayName("ToolResponseLimits")
    class ToolResponseLimitsTests {

        @Test
        void defaults() {
            var limits = new ToolResponseLimits();
            assertEquals(50000, limits.getDefaultMaxChars());
            assertNull(limits.getPerToolLimits());
        }

        @Test
        void roundTrip() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(10000);
            limits.setPerToolLimits(Map.of("webscraper", 5000));

            assertEquals(10000, limits.getDefaultMaxChars());
            assertEquals(5000, limits.getPerToolLimits().get("webscraper"));
        }
    }

    // ==================== McpServerConfig ====================

    @Nested
    @DisplayName("McpServerConfig")
    class McpServerConfigTests {

        @Test
        void defaults() {
            var cfg = new McpServerConfig();
            assertEquals("http", cfg.getTransport());
            assertEquals(30000L, cfg.getTimeoutMs());
            assertNull(cfg.getUrl());
            assertNull(cfg.getName());
            assertNull(cfg.getApiKey());
        }

        @Test
        void roundTrip() {
            var cfg = new McpServerConfig();
            cfg.setUrl("http://mcp:8080");
            cfg.setName("my-mcp");
            cfg.setTransport("sse");
            cfg.setApiKey("${vault:key}");
            cfg.setTimeoutMs(60000L);

            assertEquals("http://mcp:8080", cfg.getUrl());
            assertEquals("my-mcp", cfg.getName());
            assertEquals("sse", cfg.getTransport());
            assertEquals("${vault:key}", cfg.getApiKey());
            assertEquals(60000L, cfg.getTimeoutMs());
        }
    }

    // ==================== A2AAgentConfig ====================

    @Nested
    @DisplayName("A2AAgentConfig")
    class A2AAgentConfigTests {

        @Test
        void defaults() {
            var cfg = new A2AAgentConfig();
            assertEquals(30000L, cfg.getTimeoutMs());
            assertNull(cfg.getUrl());
            assertNull(cfg.getName());
            assertNull(cfg.getSkillsFilter());
            assertNull(cfg.getApiKey());
        }

        @Test
        void roundTrip() {
            var cfg = new A2AAgentConfig();
            cfg.setUrl("https://agent.example.com");
            cfg.setName("remote-agent");
            cfg.setSkillsFilter(List.of("skill-1", "skill-2"));
            cfg.setApiKey("${eddivault:a2a-key}");
            cfg.setTimeoutMs(15000L);

            assertEquals("https://agent.example.com", cfg.getUrl());
            assertEquals("remote-agent", cfg.getName());
            assertEquals(2, cfg.getSkillsFilter().size());
            assertEquals("${eddivault:a2a-key}", cfg.getApiKey());
            assertEquals(15000L, cfg.getTimeoutMs());
        }
    }

    // ==================== RetryConfiguration ====================

    @Nested
    @DisplayName("RetryConfiguration")
    class RetryConfigurationTests {

        @Test
        void defaults() {
            var retry = new RetryConfiguration();
            assertEquals(3, retry.getMaxAttempts());
            assertEquals(1000L, retry.getBackoffDelayMs());
            assertEquals(2.0, retry.getBackoffMultiplier());
            assertEquals(10000L, retry.getMaxBackoffDelayMs());
        }

        @Test
        void roundTrip() {
            var retry = new RetryConfiguration();
            retry.setMaxAttempts(5);
            retry.setBackoffDelayMs(500L);
            retry.setBackoffMultiplier(1.5);
            retry.setMaxBackoffDelayMs(30000L);

            assertEquals(5, retry.getMaxAttempts());
            assertEquals(500L, retry.getBackoffDelayMs());
            assertEquals(1.5, retry.getBackoffMultiplier());
            assertEquals(30000L, retry.getMaxBackoffDelayMs());
        }
    }

    // ==================== KnowledgeBaseReference ====================

    @Nested
    @DisplayName("KnowledgeBaseReference")
    class KnowledgeBaseReferenceTests {

        @Test
        void defaults() {
            var ref = new KnowledgeBaseReference();
            assertNull(ref.getName());
            assertNull(ref.getMaxResults());
            assertNull(ref.getMinScore());
            assertNull(ref.getInjectionStrategy());
            assertNull(ref.getContextTemplate());
        }

        @Test
        void roundTrip() {
            var ref = new KnowledgeBaseReference();
            ref.setName("product-docs");
            ref.setMaxResults(5);
            ref.setMinScore(0.7);
            ref.setInjectionStrategy("system_message");
            ref.setContextTemplate("Context:\n{{context}}");

            assertEquals("product-docs", ref.getName());
            assertEquals(5, ref.getMaxResults());
            assertEquals(0.7, ref.getMinScore());
            assertEquals("system_message", ref.getInjectionStrategy());
            assertEquals("Context:\n{{context}}", ref.getContextTemplate());
        }
    }

    // ==================== RagDefaults ====================

    @Nested
    @DisplayName("RagDefaults")
    class RagDefaultsTests {

        @Test
        void defaults() {
            var rag = new RagDefaults();
            assertEquals(5, rag.getMaxResults());
            assertEquals(0.6, rag.getMinScore());
            assertEquals("system_message", rag.getInjectionStrategy());
        }

        @Test
        void roundTrip() {
            var rag = new RagDefaults();
            rag.setMaxResults(10);
            rag.setMinScore(0.5);
            rag.setInjectionStrategy("user_message");

            assertEquals(10, rag.getMaxResults());
            assertEquals(0.5, rag.getMinScore());
            assertEquals("user_message", rag.getInjectionStrategy());
        }
    }

    // ==================== ConversationSummaryConfig ====================

    @Nested
    @DisplayName("ConversationSummaryConfig")
    class ConversationSummaryConfigTests {

        @Test
        void defaults() {
            var cfg = new ConversationSummaryConfig();
            assertFalse(cfg.isEnabled());
            assertEquals("anthropic", cfg.getLlmProvider());
            assertEquals("claude-sonnet-4-6", cfg.getLlmModel());
            assertEquals(800, cfg.getMaxSummaryTokens());
            assertTrue(cfg.isExcludePropertiesFromSummary());
            assertEquals(5, cfg.getRecentWindowSteps());
            assertEquals(20, cfg.getMaxRecallTurns());
            assertNull(cfg.getSummarizationPrompt());
        }

        @Test
        void roundTrip() {
            var cfg = new ConversationSummaryConfig();
            cfg.setEnabled(true);
            cfg.setLlmProvider("openai");
            cfg.setLlmModel("gpt-4o-mini");
            cfg.setMaxSummaryTokens(1000);
            cfg.setExcludePropertiesFromSummary(false);
            cfg.setRecentWindowSteps(10);
            cfg.setMaxRecallTurns(30);
            cfg.setSummarizationPrompt("Custom prompt");

            assertTrue(cfg.isEnabled());
            assertEquals("openai", cfg.getLlmProvider());
            assertEquals("gpt-4o-mini", cfg.getLlmModel());
            assertEquals(1000, cfg.getMaxSummaryTokens());
            assertFalse(cfg.isExcludePropertiesFromSummary());
            assertEquals(10, cfg.getRecentWindowSteps());
            assertEquals(30, cfg.getMaxRecallTurns());
            assertEquals("Custom prompt", cfg.getSummarizationPrompt());
        }

        @Test
        @DisplayName("validate() resets negative recentWindowSteps to 5")
        void validate_negativeWindowSteps() {
            var cfg = new ConversationSummaryConfig();
            cfg.setRecentWindowSteps(-1);
            cfg.validate();
            assertEquals(5, cfg.getRecentWindowSteps());
        }

        @Test
        @DisplayName("validate() resets zero recentWindowSteps to 5")
        void validate_zeroWindowSteps() {
            var cfg = new ConversationSummaryConfig();
            cfg.setRecentWindowSteps(0);
            cfg.validate();
            assertEquals(5, cfg.getRecentWindowSteps());
        }

        @Test
        @DisplayName("validate() resets negative maxRecallTurns to 20")
        void validate_negativeRecallTurns() {
            var cfg = new ConversationSummaryConfig();
            cfg.setMaxRecallTurns(-5);
            cfg.validate();
            assertEquals(20, cfg.getMaxRecallTurns());
        }

        @Test
        @DisplayName("validate() resets low maxSummaryTokens to 800")
        void validate_lowMaxSummaryTokens() {
            var cfg = new ConversationSummaryConfig();
            cfg.setMaxSummaryTokens(50);
            cfg.validate();
            assertEquals(800, cfg.getMaxSummaryTokens());
        }

        @Test
        @DisplayName("validate() resets null llmProvider to anthropic")
        void validate_nullProvider() {
            var cfg = new ConversationSummaryConfig();
            cfg.setLlmProvider(null);
            cfg.validate();
            assertEquals("anthropic", cfg.getLlmProvider());
        }

        @Test
        @DisplayName("validate() resets blank llmProvider to anthropic")
        void validate_blankProvider() {
            var cfg = new ConversationSummaryConfig();
            cfg.setLlmProvider("  ");
            cfg.validate();
            assertEquals("anthropic", cfg.getLlmProvider());
        }

        @Test
        @DisplayName("validate() resets null llmModel to default")
        void validate_nullModel() {
            var cfg = new ConversationSummaryConfig();
            cfg.setLlmModel(null);
            cfg.validate();
            assertEquals("claude-sonnet-4-6", cfg.getLlmModel());
        }

        @Test
        @DisplayName("validate() preserves valid values")
        void validate_preservesValid() {
            var cfg = new ConversationSummaryConfig();
            cfg.setRecentWindowSteps(10);
            cfg.setMaxRecallTurns(50);
            cfg.setMaxSummaryTokens(500);
            cfg.setLlmProvider("openai");
            cfg.setLlmModel("gpt-4o");
            cfg.validate();

            assertEquals(10, cfg.getRecentWindowSteps());
            assertEquals(50, cfg.getMaxRecallTurns());
            assertEquals(500, cfg.getMaxSummaryTokens());
            assertEquals("openai", cfg.getLlmProvider());
            assertEquals("gpt-4o", cfg.getLlmModel());
        }
    }

    // ==================== ModelCascadeConfig ====================

    @Nested
    @DisplayName("ModelCascadeConfig")
    class ModelCascadeConfigTests {

        @Test
        void defaults() {
            var cfg = new ModelCascadeConfig();
            assertFalse(cfg.isEnabled());
            assertEquals("cascade", cfg.getStrategy());
            assertEquals("structured_output", cfg.getEvaluationStrategy());
            assertTrue(cfg.isEnableInAgentMode());
            assertNull(cfg.getSteps());
        }

        @Test
        void roundTrip() {
            var cfg = new ModelCascadeConfig();
            cfg.setEnabled(true);
            cfg.setStrategy("parallel");
            cfg.setEvaluationStrategy("heuristic");
            cfg.setEnableInAgentMode(false);
            cfg.setSteps(List.of(new CascadeStep()));

            assertTrue(cfg.isEnabled());
            assertEquals("parallel", cfg.getStrategy());
            assertEquals("heuristic", cfg.getEvaluationStrategy());
            assertFalse(cfg.isEnableInAgentMode());
            assertEquals(1, cfg.getSteps().size());
        }
    }

    // ==================== CascadeStep ====================

    @Nested
    @DisplayName("CascadeStep")
    class CascadeStepTests {

        @Test
        void defaults() {
            var step = new CascadeStep();
            assertEquals(30000L, step.getTimeoutMs());
            assertNull(step.getConfidenceThreshold());
            assertNull(step.getType());
            assertNull(step.getParameters());
        }

        @Test
        void roundTrip() {
            var step = new CascadeStep();
            step.setType("anthropic");
            step.setParameters(Map.of("model", "claude-sonnet-4-20250514"));
            step.setConfidenceThreshold(0.8);
            step.setTimeoutMs(60000L);

            assertEquals("anthropic", step.getType());
            assertEquals("claude-sonnet-4-20250514", step.getParameters().get("model"));
            assertEquals(0.8, step.getConfidenceThreshold(), 0.001);
            assertEquals(60000L, step.getTimeoutMs());
        }
    }
}
