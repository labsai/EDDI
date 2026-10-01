/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Resolution of the per-tool execution timeout — the table
 * {@code ToolLoopRunner#executeSingleToolCall} consults before every tool call.
 *
 * <p>
 * The end-to-end wiring (that the resolved value actually reaches
 * {@code ToolExecutionService}) is pinned in
 * {@code AgentOrchestratorCoverageTest} and
 * {@code AgentOrchestratorResumeToolLoopTest}; this covers the table itself,
 * including the cases those cannot reach cheaply.
 * </p>
 */
@DisplayName("Per-tool execution timeout resolution")
class ToolTimeoutResolutionTest {

    @Test
    @DisplayName("no map at all falls back to the task default")
    void nullMapFallsBackToTheDefault() {
        assertEquals(7_000, ToolLoopRunner.resolveToolTimeoutMs(null, "calculate", "calculator", 7_000));
    }

    @Test
    @DisplayName("an unrelated entry falls back to the task default")
    void unrelatedEntryFallsBackToTheDefault() {
        assertEquals(7_000,
                ToolLoopRunner.resolveToolTimeoutMs(Map.of("websearch", 1_000), "calculate", "calculator", 7_000));
    }

    /**
     * The documented form: operators key these maps on the same slugs as
     * {@code builtInToolsWhitelist}, and a built-in's dispatch name is never equal
     * to its slug.
     */
    @Test
    @DisplayName("a slug-keyed entry binds")
    void slugKeyedEntryBinds() {
        assertEquals(1_000,
                ToolLoopRunner.resolveToolTimeoutMs(Map.of("calculator", 1_000), "calculate", "calculator", 7_000));
    }

    @Test
    @DisplayName("a dispatch-name entry wins over the tool-wide slug entry")
    void dispatchNameWinsOverSlug() {
        assertEquals(500, ToolLoopRunner.resolveToolTimeoutMs(
                Map.of("calculator", 1_000, "calculate", 500), "calculate", "calculator", 7_000));
    }

    /**
     * {@code -1} is a value, not an absence: it is how one deliberately
     * long-running tool is exempted without unbounding every other tool on the
     * task. Clamping or ignoring it would silently re-bound the one call the
     * operator explicitly unbounded.
     */
    @Test
    @DisplayName("a negative per-tool entry passes through unchanged")
    void negativeEntryPassesThrough() {
        assertEquals(-1, ToolLoopRunner.resolveToolTimeoutMs(Map.of("calculate", -1), "calculate", "calculator", 7_000));
        assertEquals(0, ToolLoopRunner.resolveToolTimeoutMs(Map.of("calculate", 0), "calculate", "calculator", 7_000));
    }

    @Test
    @DisplayName("a task that sets defaultToolTimeoutMs gets its own value")
    void taskDefaultIsHonoured() {
        var task = new LlmConfiguration.Task();
        task.setDefaultToolTimeoutMs(3_000);
        assertEquals(3_000, ToolLoopRunner.defaultToolTimeoutMs(task));
    }

    /**
     * Every agent stored before this field existed deserializes it as null. If the
     * fallback were missing, the timeout would be inert for exactly those agents —
     * which is all of them, on the release that introduces it.
     */
    @Test
    @DisplayName("a task with no defaultToolTimeoutMs still gets the engine default")
    void nullTaskDefaultFallsBackToTheEngineDefault() {
        var task = new LlmConfiguration.Task();
        task.setDefaultToolTimeoutMs(null);
        assertEquals(ToolLoopRunner.DEFAULT_TOOL_TIMEOUT_MS, ToolLoopRunner.defaultToolTimeoutMs(task));
    }

    /**
     * Two places state the default: the field initializer (what a freshly created
     * config gets) and the engine constant (what a stored config without the key
     * gets). If they drift, the same agent behaves differently depending on when it
     * was written.
     */
    @Test
    @DisplayName("the engine default and the config field initializer agree")
    void engineDefaultMatchesTheFieldInitializer() {
        assertEquals(ToolLoopRunner.DEFAULT_TOOL_TIMEOUT_MS, new LlmConfiguration.Task().getDefaultToolTimeoutMs(),
                "LlmConfiguration.Task's initializer and ToolLoopRunner.DEFAULT_TOOL_TIMEOUT_MS must stay equal");
    }
}
