/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.testing;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel.Step;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FaultInjectingChatModel — the scripted resilience-test model")
class FaultInjectingChatModelTest {

    @Test
    @DisplayName("replays the script call by call and records the requests")
    void replaysScript() {
        var model = FaultInjectingChatModel.script(Step.text("one"), Step.empty(), Step.finishReason("cut", FinishReason.LENGTH),
                Step.finishReason("", FinishReason.CONTENT_FILTER));

        assertEquals("one", model.chat("q").trim());

        ChatResponse empty = model.chat(List.of(UserMessage.from("q")));
        assertEquals("", empty.aiMessage().text());
        assertEquals(FinishReason.STOP, empty.finishReason());

        assertEquals(FinishReason.LENGTH, model.chat(List.of(UserMessage.from("q"))).finishReason());
        assertEquals(FinishReason.CONTENT_FILTER,
                model.chat(List.of(UserMessage.from("q"))).finishReason());

        assertEquals(4, model.callCount());
        assertEquals(4, model.requests().size());
    }

    @Test
    @DisplayName("a call beyond the script fails loudly, unless repeatLast() was asked for")
    void exhaustion() {
        var strict = FaultInjectingChatModel.script(Step.text("only"));
        strict.chat("q");
        assertThrows(IllegalStateException.class, () -> strict.chat("q"));

        var repeating = FaultInjectingChatModel.script(Step.text("again")).repeatLast();
        repeating.chat("q");
        assertEquals("again", repeating.chat("q"));
        assertEquals(2, repeating.callCount());
    }

    @ParameterizedTest(name = "HTTP {0} is retryable={1}")
    @CsvSource({"429,true", "500,true", "502,true", "503,true", "504,true", "400,false", "401,false", "403,false", "404,false"})
    @DisplayName("a simulated status is classified by RetryConfiguration the way a real provider error is")
    void statusIsClassifiedLikeAProviderError(int status, boolean retryable) {
        var model = FaultInjectingChatModel.script(Step.status(status));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> model.chat("q"));

        assertEquals(retryable, RetryConfiguration.isRetryableError(thrown));
        HttpException cause = assertInstanceOf(HttpException.class, thrown.getCause(), "the raw status stays reachable on the cause chain");
        assertEquals(status, cause.statusCode());
    }

    @Test
    @DisplayName("an arbitrary exception is thrown as-is")
    void arbitraryException() {
        var timeout = new TimeoutException("simulated read timeout");
        var model = FaultInjectingChatModel.script(Step.fail(timeout));

        assertEquals(timeout, assertThrows(TimeoutException.class, () -> model.chat("q")));
    }

    @Test
    @DisplayName("a delayed step sleeps before it answers")
    void delay() {
        var model = FaultInjectingChatModel.script(Step.delayed(120, Step.text("late")));

        long start = System.nanoTime();
        String answer = model.chat("q");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("late", answer);
        assertTrue(elapsedMs >= 100, "slept " + elapsedMs + "ms");
    }
}
