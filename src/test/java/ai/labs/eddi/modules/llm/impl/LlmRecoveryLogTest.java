/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.Attempt;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.Policy;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.ReaskGate;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.RemainingBudget;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.Trigger;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LlmRecoveryLog (R11)")
class LlmRecoveryLogTest {

    private static final String VALID = "{\"answer\":\"hi\"}";
    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void setUp() {
        LlmRecoveryLog.observer = lines::add;
    }

    @AfterEach
    void tearDown() {
        LlmRecoveryLog.observer = null;
    }

    @Test
    @DisplayName("the line carries conversation, agent, class, action, outcome, attempt and duration")
    void formatCarriesEveryField() {
        assertEquals("LLM recovery conversationId=c1 agentId=a1 class=TIMEOUT action=escalate outcome=error attempt=2 durationMs=345",
                LlmRecoveryLog.format("c1", "a1", "TIMEOUT", "escalate", "error", 2, 345L));
    }

    @Test
    @DisplayName("whitespace, control characters and missing ids cannot split or blank the line")
    void valuesAreCleaned() {
        String line = LlmRecoveryLog.format("c 1\nINFO fake", null, "", "retry", "ok", 1, 0L);

        assertEquals(1, line.lines().count());
        assertTrue(line.contains("conversationId=c_1_INFO_fake "), line);
        assertTrue(line.contains(" agentId=unknown class=unknown "), line);
    }

    @Test
    @DisplayName("log() writes the line once to the observer")
    void logEmitsOnce() {
        LlmRecoveryLog.log("c1", "a1", "invalid_json", LlmRecoveryLog.REPAIR, "recovered", 1, 0L);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("action=repair"));
    }

    private static Policy policy() {
        var v = new ResponseValidation();
        v.setEnabled(true);
        v.setOnInvalidJson("retry");
        return Policy.from(v, null, true, null, null, null);
    }

    private FormatRetryRunner.Outcome run(List<String> replies, AtomicInteger started) throws LifecycleException {
        var runner = new FormatRetryRunner(new ModelOutputParser(new JsonSerialization(new ObjectMapper())), new SimpleMeterRegistry());
        AtomicInteger asked = new AtomicInteger();
        var listener = LlmRecoveryLog.reaskListener("c1", "a1", (trigger, attempt) -> started.incrementAndGet());
        List<ChatMessage> original = List.of(UserMessage.from("q"));
        return runner.run(policy(), original, (messages, maxTokens) -> {
            int i = asked.getAndIncrement();
            String reply = replies.get(Math.min(i, replies.size() - 1));
            if (reply == null) {
                throw new LifecycleException("provider down");
            }
            return new Attempt(reply, Map.of());
        }, null, RemainingBudget.UNBOUNDED, ReaskGate.ALWAYS, listener);
    }

    @Test
    @DisplayName("a re-ask that fixes the reply: one 'recovered' line, and the delegate still sees the re-ask start")
    void reaskRecovered() throws Exception {
        var started = new AtomicInteger();

        run(List.of("prose", VALID), started);

        assertEquals(1, started.get());
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains(" class=invalid_json action=retry outcome=recovered attempt=1 durationMs="), lines.get(0));
    }

    @Test
    @DisplayName("a re-ask that is still wrong: one 'still_invalid' line")
    void reaskStillInvalid() throws Exception {
        run(List.of("prose", "more prose"), new AtomicInteger());

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains(" outcome=still_invalid attempt=1 "), lines.get(0));
    }

    @Test
    @DisplayName("a re-ask whose model call fails: one 'failed' line, the earlier reply is kept")
    void reaskFailed() throws Exception {
        var outcome = run(Arrays.asList("prose", null), new AtomicInteger());

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains(" outcome=failed attempt=1 "), lines.get(0));
        assertEquals(Trigger.INVALID_JSON, outcome.unresolved());
    }

    @Test
    @DisplayName("a reply that needs no re-ask leaves no line")
    void cleanReplyIsSilent() throws Exception {
        run(List.of(VALID), new AtomicInteger());

        assertTrue(lines.isEmpty(), lines.toString());
    }

    @Test
    @DisplayName("one line per re-ask, and no more re-asks than the policy allows")
    void noExtraAttemptsBeyondThePolicy() throws Exception {
        var asked = new AtomicInteger();
        var runner = new FormatRetryRunner(new ModelOutputParser(new JsonSerialization(new ObjectMapper())), new SimpleMeterRegistry());
        runner.run(policy(), List.of(UserMessage.from("q")), (messages, maxTokens) -> {
            asked.incrementAndGet();
            return new Attempt("prose", Map.of());
        }, null, RemainingBudget.UNBOUNDED, ReaskGate.ALWAYS, LlmRecoveryLog.reaskListener("c1", "a1", null));

        assertEquals(1 + new ResponseValidation().getMaxRetries(), asked.get());
        assertEquals(new ResponseValidation().getMaxRetries(), lines.size(), lines.toString());
    }
}
