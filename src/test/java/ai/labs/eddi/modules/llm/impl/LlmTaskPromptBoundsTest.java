/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ConversationSummaryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding F7 — nothing bounded the system prompt: the httpCall-RAG response and
 * the vector-RAG block were appended whole, and {@code maxContextTokens}
 * explicitly excludes the system prompt.
 * <p>
 * Finding F13 — the conversation summarizer could never authenticate because it
 * was pinned to a hardcoded vendor default with no path to the parent task's
 * credentials.
 */
@DisplayName("LlmTask — prompt bounds (F7) and summary inheritance (F13)")
class LlmTaskPromptBoundsTest {

    @Nested
    @DisplayName("F7 — RAG context cap")
    class RagContextCap {

        @Test
        @DisplayName("a RAG block larger than the cap is truncated with an explanatory marker")
        void truncatesOversizedContext() {
            String context = "K".repeat(50_000);

            String capped = LlmTask.capRagContext(context, 1000, "httpCall RAG 'search'");

            assertTrue(capped.length() < context.length(), "oversized context must be cut down");
            assertTrue(capped.startsWith("K".repeat(1000)));
            assertTrue(capped.contains("truncated"), capped.substring(capped.length() - 80));
        }

        @Test
        @DisplayName("a block within the cap is returned unchanged")
        void leavesSmallContextAlone() {
            String context = "short context";
            assertSame(context, LlmTask.capRagContext(context, 1000, "vector RAG"));
        }

        @Test
        @DisplayName("cap <= 0 restores the previous unbounded behaviour")
        void unboundedWhenDisabled() {
            String context = "K".repeat(50_000);
            assertSame(context, LlmTask.capRagContext(context, -1, "vector RAG"));
            assertSame(context, LlmTask.capRagContext(context, 0, "vector RAG"));
        }

        @Test
        @DisplayName("null context is passed through")
        void nullSafe() {
            assertNull(LlmTask.capRagContext(null, 100, "vector RAG"));
        }

        @Test
        @DisplayName("the default cap is active on a fresh task, so RAG cannot grow the prompt without limit")
        void defaultCapIsActive() {
            var task = new LlmConfiguration.Task();
            int cap = RagContextProvider.resolveMaxChars(task);
            assertTrue(cap > 0, "maxRagContextChars must default to a real bound, was " + cap);

            String context = "K".repeat(cap * 3);
            assertTrue(LlmTask.capRagContext(context, cap, "vector RAG").length() < context.length());
        }
    }

    @Nested
    @DisplayName("F7 — system prompt ceiling")
    class SystemPromptCeiling {

        @Test
        @DisplayName("an over-long assembled prompt is capped when maxSystemPromptChars is set")
        void capsWhenConfigured() {
            String prompt = "P".repeat(10_000);

            String capped = LlmTask.capSystemPrompt(prompt, 500, "task-1");

            assertTrue(capped.length() < prompt.length());
            assertTrue(capped.startsWith("P".repeat(500)));
            assertTrue(capped.contains("truncated"));
        }

        @Test
        @DisplayName("opt-in: the default (-1) leaves the designer-authored prompt untouched")
        void untouchedByDefault() {
            var task = new LlmConfiguration.Task();
            String prompt = "P".repeat(10_000);
            assertSame(prompt, LlmTask.capSystemPrompt(prompt, task.getMaxSystemPromptChars(), "task-1"));
        }

        @Test
        @DisplayName("null prompt is passed through")
        void nullSafe() {
            assertNull(LlmTask.capSystemPrompt(null, 100, "task-1"));
        }
    }

    @Nested
    @DisplayName("F13 — summary config inherits the parent task")
    class SummaryInheritance {

        @Test
        @DisplayName("an unset provider/model inherits the parent LLM task's")
        void inheritsBoth() {
            var configured = new ConversationSummaryConfig();
            configured.setEnabled(true);
            configured.setRecentWindowSteps(7);

            var effective = LlmTask.resolveEffectiveSummaryConfig(configured, "openai", "gpt-4o-mini");

            assertEquals("openai", effective.getLlmProvider());
            assertEquals("gpt-4o-mini", effective.getLlmModel());
            assertTrue(effective.isEnabled());
            assertEquals(7, effective.getRecentWindowSteps(), "unrelated settings must be carried over");
        }

        @Test
        @DisplayName("an explicit provider/model wins over the parent task")
        void explicitWins() {
            var configured = new ConversationSummaryConfig();
            configured.setLlmProvider("mistral");
            configured.setLlmModel("mistral-small");

            var effective = LlmTask.resolveEffectiveSummaryConfig(configured, "openai", "gpt-4o");

            assertSame(configured, effective, "nothing to resolve — the original is reused");
            assertEquals("mistral", effective.getLlmProvider());
        }

        @Test
        @DisplayName("only the missing half is inherited")
        void inheritsModelOnly() {
            var configured = new ConversationSummaryConfig();
            configured.setLlmProvider("openai");

            var effective = LlmTask.resolveEffectiveSummaryConfig(configured, "anthropic", "gpt-4o-mini");

            assertEquals("openai", effective.getLlmProvider());
            assertEquals("gpt-4o-mini", effective.getLlmModel());
        }

        @Test
        @DisplayName("the shared task config object is never mutated")
        void doesNotMutateSharedConfig() {
            var configured = new ConversationSummaryConfig();
            configured.setEnabled(true);

            LlmTask.resolveEffectiveSummaryConfig(configured, "openai", "gpt-4o-mini");

            assertNull(configured.getLlmProvider(), "the cached, shared config must stay untouched");
            assertNull(configured.getLlmModel());
        }

        @Test
        @DisplayName("null config stays null")
        void nullSafe() {
            assertNull(LlmTask.resolveEffectiveSummaryConfig(null, "openai", "gpt-4o"));
        }
    }
}
