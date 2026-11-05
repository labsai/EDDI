/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolContextBudget;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ModelCallBound - the most one LLM task can cost")
class ModelCallBoundTest {

    private static LlmConfiguration.Task pricedTask(boolean agentMode, Integer iterations) {
        LlmConfiguration.Task task = new LlmConfiguration.Task();
        task.setType("openai");
        task.setInputPricePer1M(2.0);
        task.setOutputPricePer1M(10.0);
        task.setEnableBuiltInTools(agentMode ? Boolean.TRUE : null);
        task.setMaxToolIterations(iterations);
        task.setMaxToolContextTokens(1000);
        return task;
    }

    private static List<ChatMessage> prompt(int chars) {
        return List.of(UserMessage.from("x".repeat(chars)));
    }

    @Test
    @DisplayName("a plain call is prompt x input price + max output x output price")
    void plainCall() {
        // 3000 chars -> about 1000 prompt tokens (a few over: the message's own wrapper
        // counts)
        long promptTokens = ModelCallBound.promptTokens(prompt(3000));
        double expected = promptTokens * 2.0 / 1_000_000 + 500 * 10.0 / 1_000_000;

        double bound = ModelCallBound.phaseMaxUsd(pricedTask(false, null), false, prompt(3000), Map.of("maxTokens", "500"), "openai", "gpt-x", 4096);

        assertEquals(expected, bound, 1e-12);
    }

    @Test
    @DisplayName("a model that sets no output cap is sized with the configured default")
    void defaultOutputCap() {
        double withDefault = ModelCallBound.phaseMaxUsd(pricedTask(false, null), false, prompt(30), Map.of(), "openai", "gpt-x", 1000);
        double withLarger = ModelCallBound.phaseMaxUsd(pricedTask(false, null), false, prompt(30), Map.of(), "openai", "gpt-x", 2000);

        assertTrue(withLarger > withDefault);
        assertEquals(1000 * 10.0 / 1_000_000, withDefault - ModelCallBound.promptTokens(prompt(30)) * 2.0 / 1_000_000, 1e-12);
    }

    @Test
    @DisplayName("an agent-mode task is 1 + maxToolIterations rounds, later rounds carrying the tool context cap")
    void toolLoopRounds() {
        double plain = ModelCallBound.phaseMaxUsd(pricedTask(false, null), false, prompt(300), Map.of("maxTokens", "100"), "openai", "m", 4096);
        double loop = ModelCallBound.phaseMaxUsd(pricedTask(true, 3), false, prompt(300), Map.of("maxTokens", "100"), "openai", "m", 4096);

        // 4 rounds: each pays prompt + output; rounds 2-4 also pay the 1000-token tool
        // context cap
        double expected = 4 * plain + 3 * 1000 * 2.0 / 1_000_000;
        assertEquals(expected, loop, 1e-12);
    }

    @Test
    @DisplayName("a disabled tool-context guard (maxToolContextTokens <= 0) is sized with the default cap, not as zero")
    void disabledGuardUsesTheDefaultCap() {
        var disabled = pricedTask(true, 3);
        disabled.setMaxToolContextTokens(0);
        var defaulted = pricedTask(true, 3);
        defaulted.setMaxToolContextTokens(null);

        assertEquals(ToolContextBudget.DEFAULT_MAX_TOOL_CONTEXT_TOKENS, ModelCallBound.toolContextTokens(disabled));
        assertEquals(
                ModelCallBound.phaseMaxUsd(defaulted, false, prompt(300), Map.of("maxTokens", "100"), "openai", "m", 4096),
                ModelCallBound.phaseMaxUsd(disabled, false, prompt(300), Map.of("maxTokens", "100"), "openai", "m", 4096), 1e-12);

        var batch = new PendingToolCallBatch();
        batch.setChatTranscriptJson("y".repeat(300));
        batch.setIterationIndex(1);
        disabled.setMaxToolContextTokens(-1);
        assertEquals(ModelCallBound.resumeMaxUsd(defaulted, batch, Map.of("maxTokens", "100"), "openai", "m", 4096),
                ModelCallBound.resumeMaxUsd(disabled, batch, Map.of("maxTokens", "100"), "openai", "m", 4096), 1e-12);
    }

    @Test
    @DisplayName("an unpriced model bounds to zero, like metering: the ordinary gate is its only check")
    void unpricedIsZero() {
        LlmConfiguration.Task task = new LlmConfiguration.Task();
        task.setType("openai");

        assertEquals(0.0, ModelCallBound.phaseMaxUsd(task, false, prompt(3000), Map.of("maxTokens", "500"), "openai", "no-such-model", 4096), 0.0);
    }

    @Test
    @DisplayName("the estimate errs high: three characters per token")
    void errsHigh() {
        assertTrue(ModelCallBound.promptTokens(prompt(3000)) >= 1000);
    }

    @Test
    @DisplayName("a resumed loop holds the rounds it has left, each with the frozen transcript and the tool context cap")
    void resumedLoop() {
        var batch = new PendingToolCallBatch();
        batch.setChatTranscriptJson("y".repeat(2999));
        batch.setIterationIndex(2);
        var task = pricedTask(true, 4); // rounds left: 4 - 2 + 1 = 3

        double bound = ModelCallBound.resumeMaxUsd(task, batch, Map.of("maxTokens", "100"), "openai", "m", 4096);

        // the first resumed request carries the transcript and the approved calls'
        // output (tool cap);
        // each later round adds up to the cap again
        long firstIn = 2999 / ModelCallBound.CHARS_PER_TOKEN + 1 + 1000;
        double perRoundOut = 100 * 10.0 / 1_000_000;
        double expected = (firstIn * 2.0 / 1_000_000 + perRoundOut) + 2 * ((firstIn + 1000) * 2.0 / 1_000_000 + perRoundOut);
        assertEquals(expected, bound, 1e-12);
    }
}
