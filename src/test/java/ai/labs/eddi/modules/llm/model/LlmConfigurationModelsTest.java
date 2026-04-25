package ai.labs.eddi.modules.llm.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LlmConfiguration nested model classes that have 0% coverage.
 */
class LlmConfigurationModelsTest {

    // --- RagDefaults ---

    @Nested
    class RagDefaultsTests {

        @Test
        void defaults() {
            var rd = new LlmConfiguration.RagDefaults();
            assertEquals(5, rd.getMaxResults());
            assertEquals(0.6, rd.getMinScore());
            assertEquals("system_message", rd.getInjectionStrategy());
        }

        @Test
        void setters() {
            var rd = new LlmConfiguration.RagDefaults();
            rd.setMaxResults(10);
            rd.setMinScore(0.8);
            rd.setInjectionStrategy("user_message");
            assertEquals(10, rd.getMaxResults());
            assertEquals(0.8, rd.getMinScore());
            assertEquals("user_message", rd.getInjectionStrategy());
        }
    }

    // --- ModelCascadeConfig ---

    @Nested
    class ModelCascadeConfigTests {

        @Test
        void defaults() {
            var mcc = new LlmConfiguration.ModelCascadeConfig();
            assertFalse(mcc.isEnabled());
            assertEquals("cascade", mcc.getStrategy());
            assertEquals("structured_output", mcc.getEvaluationStrategy());
            assertTrue(mcc.isEnableInAgentMode());
            assertNull(mcc.getSteps());
        }

        @Test
        void setters() {
            var mcc = new LlmConfiguration.ModelCascadeConfig();
            mcc.setEnabled(true);
            mcc.setStrategy("parallel");
            mcc.setEvaluationStrategy("heuristic");
            mcc.setEnableInAgentMode(false);
            mcc.setSteps(List.of(new LlmConfiguration.CascadeStep()));
            assertTrue(mcc.isEnabled());
            assertEquals("parallel", mcc.getStrategy());
            assertEquals(1, mcc.getSteps().size());
        }
    }

    // --- CascadeStep ---

    @Nested
    class CascadeStepTests {

        @Test
        void defaults() {
            var cs = new LlmConfiguration.CascadeStep();
            assertNull(cs.getType());
            assertNull(cs.getParameters());
            assertNull(cs.getConfidenceThreshold());
            assertEquals(30000L, cs.getTimeoutMs());
        }

        @Test
        void setters() {
            var cs = new LlmConfiguration.CascadeStep();
            cs.setType("openai");
            cs.setParameters(Map.of("model", "gpt-4o-mini"));
            cs.setConfidenceThreshold(0.7);
            cs.setTimeoutMs(5000L);
            assertEquals("openai", cs.getType());
            assertEquals(0.7, cs.getConfidenceThreshold());
            assertEquals(5000L, cs.getTimeoutMs());
        }
    }

    // --- ToolResponseLimits ---

    @Nested
    class ToolResponseLimitsTests {

        @Test
        void defaults() {
            var trl = new LlmConfiguration.ToolResponseLimits();
            assertEquals(50000, trl.getDefaultMaxChars());
            assertNull(trl.getPerToolLimits());
        }

        @Test
        void setters() {
            var trl = new LlmConfiguration.ToolResponseLimits();
            trl.setDefaultMaxChars(10000);
            trl.setPerToolLimits(Map.of("webscraper", 5000));
            assertEquals(10000, trl.getDefaultMaxChars());
            assertEquals(5000, trl.getPerToolLimits().get("webscraper"));
        }
    }

    // --- McpServerConfig ---

    @Nested
    class McpServerConfigTests {

        @Test
        void defaults() {
            var msc = new LlmConfiguration.McpServerConfig();
            assertNull(msc.getUrl());
            assertNull(msc.getName());
            assertEquals("http", msc.getTransport());
            assertNull(msc.getApiKey());
            assertEquals(30000L, msc.getTimeoutMs());
        }

        @Test
        void setters() {
            var msc = new LlmConfiguration.McpServerConfig();
            msc.setUrl("http://localhost:7070/mcp");
            msc.setName("local-mcp");
            msc.setTransport("sse");
            msc.setApiKey("${eddivault:mcp-key}");
            msc.setTimeoutMs(60000L);
            assertEquals("http://localhost:7070/mcp", msc.getUrl());
            assertEquals("sse", msc.getTransport());
        }
    }

    // --- A2AAgentConfig ---

    @Nested
    class A2AAgentConfigTests {

        @Test
        void defaults() {
            var a2a = new LlmConfiguration.A2AAgentConfig();
            assertNull(a2a.getUrl());
            assertNull(a2a.getName());
            assertNull(a2a.getSkillsFilter());
            assertNull(a2a.getApiKey());
            assertEquals(30000L, a2a.getTimeoutMs());
        }

        @Test
        void setters() {
            var a2a = new LlmConfiguration.A2AAgentConfig();
            a2a.setUrl("https://remote-agent.example.com/a2a");
            a2a.setName("translator");
            a2a.setSkillsFilter(List.of("translate-en-de"));
            a2a.setApiKey("${eddivault:a2a-key}");
            a2a.setTimeoutMs(10000L);
            assertEquals("translator", a2a.getName());
            assertEquals(1, a2a.getSkillsFilter().size());
        }
    }

    // --- RetryConfiguration ---

    @Nested
    class RetryConfigurationTests {

        @Test
        void defaults() {
            var rc = new LlmConfiguration.RetryConfiguration();
            assertEquals(3, rc.getMaxAttempts());
            assertEquals(1000L, rc.getBackoffDelayMs());
            assertEquals(2.0, rc.getBackoffMultiplier());
            assertEquals(10000L, rc.getMaxBackoffDelayMs());
        }

        @Test
        void setters() {
            var rc = new LlmConfiguration.RetryConfiguration();
            rc.setMaxAttempts(5);
            rc.setBackoffDelayMs(500L);
            rc.setBackoffMultiplier(1.5);
            rc.setMaxBackoffDelayMs(30000L);
            assertEquals(5, rc.getMaxAttempts());
            assertEquals(1.5, rc.getBackoffMultiplier());
        }
    }

    // --- KnowledgeBaseReference ---

    @Nested
    class KnowledgeBaseReferenceTests {

        @Test
        void defaults() {
            var kbr = new LlmConfiguration.KnowledgeBaseReference();
            assertNull(kbr.getName());
            assertNull(kbr.getMaxResults());
            assertNull(kbr.getMinScore());
            assertNull(kbr.getInjectionStrategy());
            assertNull(kbr.getContextTemplate());
        }

        @Test
        void setters() {
            var kbr = new LlmConfiguration.KnowledgeBaseReference();
            kbr.setName("product-docs");
            kbr.setMaxResults(10);
            kbr.setMinScore(0.7);
            kbr.setInjectionStrategy("system_message");
            kbr.setContextTemplate("Context: {{context}}");
            assertEquals("product-docs", kbr.getName());
            assertEquals(10, kbr.getMaxResults());
        }
    }

    // --- ConversationSummaryConfig ---

    @Nested
    class ConversationSummaryConfigTests {

        @Test
        void defaults() {
            var csc = new LlmConfiguration.ConversationSummaryConfig();
            assertFalse(csc.isEnabled());
            assertEquals("anthropic", csc.getLlmProvider());
            assertEquals("claude-sonnet-4-6", csc.getLlmModel());
            assertEquals(800, csc.getMaxSummaryTokens());
            assertTrue(csc.isExcludePropertiesFromSummary());
            assertEquals(5, csc.getRecentWindowSteps());
            assertEquals(20, csc.getMaxRecallTurns());
            assertNull(csc.getSummarizationPrompt());
        }

        @Test
        void setters() {
            var csc = new LlmConfiguration.ConversationSummaryConfig();
            csc.setEnabled(true);
            csc.setLlmProvider("openai");
            csc.setLlmModel("gpt-4o-mini");
            csc.setMaxSummaryTokens(500);
            csc.setExcludePropertiesFromSummary(false);
            csc.setRecentWindowSteps(10);
            csc.setMaxRecallTurns(30);
            csc.setSummarizationPrompt("Summarize:");
            assertTrue(csc.isEnabled());
            assertEquals("openai", csc.getLlmProvider());
            assertEquals(500, csc.getMaxSummaryTokens());
        }

        @Test
        void validate_resetsBadValues() {
            var csc = new LlmConfiguration.ConversationSummaryConfig();
            csc.setRecentWindowSteps(0);
            csc.setMaxRecallTurns(-1);
            csc.setMaxSummaryTokens(50);
            csc.setLlmProvider("");
            csc.setLlmModel(null);

            csc.validate();

            assertEquals(5, csc.getRecentWindowSteps());
            assertEquals(20, csc.getMaxRecallTurns());
            assertEquals(800, csc.getMaxSummaryTokens());
            assertEquals("anthropic", csc.getLlmProvider());
            assertEquals("claude-sonnet-4-6", csc.getLlmModel());
        }

        @Test
        void validate_keepsGoodValues() {
            var csc = new LlmConfiguration.ConversationSummaryConfig();
            csc.setRecentWindowSteps(10);
            csc.setMaxRecallTurns(25);
            csc.setMaxSummaryTokens(1000);
            csc.setLlmProvider("openai");
            csc.setLlmModel("gpt-4o");

            csc.validate();

            assertEquals(10, csc.getRecentWindowSteps());
            assertEquals(25, csc.getMaxRecallTurns());
            assertEquals(1000, csc.getMaxSummaryTokens());
            assertEquals("openai", csc.getLlmProvider());
            assertEquals("gpt-4o", csc.getLlmModel());
        }
    }
}
