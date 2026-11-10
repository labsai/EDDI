/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.Asker;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.Attempt;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.Outcome;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.Policy;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.ReaskGate;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.ReaskListener;
import ai.labs.eddi.modules.llm.impl.FormatRetryRunner.RemainingBudget;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The format-retry runner sees a reply through the same local rescue the task
 * stores it with, so a reply that rescue repairs is not re-asked (a second
 * model call), and one it cannot repair still is.
 */
@DisplayName("FormatRetryRunner — local rescue before a re-ask")
class FormatRetryRunnerRescueTest {

    private static final String FIELD = "htmlResponseText";
    private static final String GOOD = "{\"htmlResponseText\":\"fixed\"}";

    private final ModelOutputParser parser = new ModelOutputParser(new JsonSerialization(new ObjectMapper()));
    private final FormatRetryRunner runner = new FormatRetryRunner(parser, new SimpleMeterRegistry());

    private static ResponseValidation validation(String onInvalidJson, String onSchemaMismatch) {
        var v = new ResponseValidation();
        v.setEnabled(true);
        v.setOnInvalidJson(onInvalidJson);
        v.setOnSchemaMismatch(onSchemaMismatch);
        v.setWrapField(FIELD);
        v.setMinAttemptMs(0);
        return v;
    }

    private Outcome run(Policy policy, String firstReply, AtomicInteger calls) throws Exception {
        Asker asker = (messages, maxTokens) -> {
            calls.incrementAndGet();
            return new Attempt(GOOD, Map.of());
        };
        List<ChatMessage> messages = new ArrayList<>(List.of(UserMessage.from("q")));
        return runner.resolve(policy, new Attempt(firstReply, Map.of()), messages, asker, RemainingBudget.UNBOUNDED, ReaskGate.ALWAYS,
                ReaskListener.NONE);
    }

    private static Policy policy(ResponseValidation v, RescueOptions rescue) {
        return Policy.from(v, null, true, null, null, null, null, List.of(FIELD), "t", rescue);
    }

    @Test
    @DisplayName("a double-escaped reply is repaired locally under onInvalidJson=retry: no re-ask")
    void doubleEscapedIsNotReasked() throws Exception {
        var v = validation("retry", "ignore");
        var calls = new AtomicInteger();

        Outcome outcome = run(policy(v, RescueOptions.of(v, null)), "{\\\"htmlResponseText\\\": \\\"x\\\"}", calls);

        assertEquals(0, calls.get());
        assertTrue(outcome.usable());
        assertEquals(0, outcome.reasks());
    }

    @Test
    @DisplayName("without the rescue options the same reply is re-asked, as before")
    void withoutRescueItIsReasked() throws Exception {
        var v = validation("retry", "ignore");
        var calls = new AtomicInteger();

        Outcome outcome = run(policy(v, RescueOptions.NONE), "{\\\"htmlResponseText\\\": \\\"x\\\"}", calls);

        assertEquals(1, calls.get());
        assertEquals(GOOD, outcome.attempt().text());
    }

    @Test
    @DisplayName("a renamed key satisfies nonBlankFields: onSchemaMismatch=retry does not re-ask")
    void aliasAvoidsSchemaMismatchRetry() throws Exception {
        var v = validation("ignore", "retry");
        var rescue = RescueOptions.of(v, Map.of("htmlResponseStr", FIELD));

        var withAlias = new AtomicInteger();
        assertTrue(run(policy(v, rescue), "{\"htmlResponseStr\":\"x\"}", withAlias).usable());
        assertEquals(0, withAlias.get());

        var withoutAlias = new AtomicInteger();
        run(policy(v, RescueOptions.NONE), "{\"htmlResponseStr\":\"x\"}", withoutAlias);
        assertEquals(1, withoutAlias.get());
    }

    @Test
    @DisplayName("the empty object is still re-asked under nonBlankFields + onSchemaMismatch=retry, even with wrap configured")
    void emptyObjectIsStillReasked() throws Exception {
        var v = validation("wrap", "retry");
        var calls = new AtomicInteger();

        Outcome outcome = run(policy(v, RescueOptions.of(v, null)), "{}", calls);

        assertEquals(1, calls.get());
        assertEquals(GOOD, outcome.attempt().text());
    }

    @Test
    @DisplayName("classifyJsonReply: wrapped prose is usable, broken JSON is not")
    void classifyUsesRescue() {
        var v = validation("wrap", "ignore");
        var rescue = RescueOptions.of(v, null);

        assertNull(runner.classifyJsonReply("just prose", null, List.of(FIELD), "t", rescue));
        assertNotNull(runner.classifyJsonReply("just prose", null, List.of(FIELD), "t"), "unchanged without the options");
        assertNotNull(runner.classifyJsonReply("{\"htmlResponseText\": \"cut", null, List.of(FIELD), "t", rescue));
    }

    @Test
    @DisplayName("the nine-argument Policy.from derives the options from the validation")
    void nineArgumentFromDerivesOptions() {
        var v = validation("retry", "ignore");

        Policy derived = Policy.from(v, null, true, null, null, null, null, List.of(FIELD), "t");

        assertTrue(derived.rescue().doubleEscape());
        assertEquals(derived.rescue(), RescueOptions.of(v, null));
    }
}
