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
import dev.langchain4j.data.message.AiMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code onUnwrappable: "retry"}: with {@code onInvalidJson: "wrap"}, a reply
 * that is neither wrappable (broken JSON, over the cap) nor recoverable is
 * re-asked once, through the same runner, budget and corrective message as
 * every other retry; a wrapped or recovered reply is not.
 */
@DisplayName("FormatRetryRunner — onUnwrappable retry")
class FormatRetryRunnerUnwrappableTest {

    private static final String FIELD = "htmlResponseText";
    private static final String GOOD = "{\"htmlResponseText\":\"fixed\"}";
    private static final String BROKEN = "{\"htmlResponseText\": \"cut o";

    private final ModelOutputParser parser = new ModelOutputParser(new JsonSerialization(new ObjectMapper()));
    private final FormatRetryRunner runner = new FormatRetryRunner(parser, new SimpleMeterRegistry());

    private static ResponseValidation validation(String onUnwrappable, int maxRetries) {
        var v = new ResponseValidation();
        v.setEnabled(true);
        v.setOnInvalidJson("wrap");
        v.setWrapField(FIELD);
        v.setOnUnwrappable(onUnwrappable);
        v.setMaxRetries(maxRetries);
        v.setMinAttemptMs(0);
        return v;
    }

    private static Policy policy(ResponseValidation v) {
        return Policy.from(v, null, true, null, null, null, null, List.of(FIELD), "t", RescueOptions.of(v, null));
    }

    private Outcome run(Policy policy, String firstReply, List<String> replies, AtomicInteger calls, List<ChatMessage> seen, RemainingBudget budget)
            throws Exception {
        Asker asker = (messages, maxTokens) -> {
            int n = calls.getAndIncrement();
            seen.clear();
            seen.addAll(messages);
            return new Attempt(replies.get(Math.min(n, replies.size() - 1)), Map.of());
        };
        List<ChatMessage> messages = new ArrayList<>(List.of(UserMessage.from("q"), AiMessage.from(firstReply)));
        return runner.resolve(policy, new Attempt(firstReply, Map.of()), messages, asker, budget, ReaskGate.ALWAYS, ReaskListener.NONE);
    }

    @Test
    @DisplayName("without onUnwrappable there is no policy at all: broken JSON goes straight to the fallback, as before")
    void defaultIsNoPolicy() {
        assertNull(policy(validation("fallback", 1)));
        assertNull(policy(validation(null, 1)));
    }

    @Test
    @DisplayName("onUnwrappable=retry: broken JSON is re-asked once with the corrective message and the second reply is used")
    void brokenJsonIsReasked() throws Exception {
        var calls = new AtomicInteger();
        var seen = new ArrayList<ChatMessage>();

        Outcome outcome = run(policy(validation("retry", 1)), BROKEN, List.of(GOOD), calls, seen, RemainingBudget.UNBOUNDED);

        assertEquals(1, calls.get());
        assertTrue(outcome.usable());
        assertEquals(GOOD, outcome.attempt().text());
        assertEquals(1, outcome.reasks());
        String last = ((UserMessage) seen.getLast()).singleText();
        assertTrue(last.contains("truncated JSON"), "the corrective message carries EDDI's reason, not model output: " + last);
    }

    @Test
    @DisplayName("the re-ask also fails: one re-ask only, then the reply is unresolved so the fallback applies")
    void secondFailureIsUnresolved() throws Exception {
        var calls = new AtomicInteger();

        Outcome outcome = run(policy(validation("retry", 1)), BROKEN, List.of("{\"htmlResponseText\": \"again cut"), calls, new ArrayList<>(),
                RemainingBudget.UNBOUNDED);

        assertEquals(1, calls.get(), "maxRetries is respected");
        assertFalse(outcome.usable());
        assertEquals(FormatRetryRunner.Trigger.INVALID_JSON, outcome.unresolved());
    }

    @Test
    @DisplayName("maxRetries 0: nothing is re-asked")
    void zeroRetries() throws Exception {
        var calls = new AtomicInteger();

        Outcome outcome = run(policy(validation("retry", 0)), BROKEN, List.of(GOOD), calls, new ArrayList<>(), RemainingBudget.UNBOUNDED);

        assertEquals(0, calls.get());
        assertFalse(outcome.usable());
    }

    @Test
    @DisplayName("the turn deadline leaves no room: no re-ask, the fallback applies")
    void deadlineLeavesNoRoom() throws Exception {
        var calls = new AtomicInteger();
        var v = validation("retry", 1);
        v.setMinAttemptMs(3000);

        Outcome outcome = run(policy(v), BROKEN, List.of(GOOD), calls, new ArrayList<>(), () -> 500L);

        assertEquals(0, calls.get());
        assertFalse(outcome.usable());
    }

    @Test
    @DisplayName("a plain-text reply is wrapped, not re-asked")
    void proseIsWrappedNotReasked() throws Exception {
        var calls = new AtomicInteger();

        Outcome outcome = run(policy(validation("retry", 1)), "just prose, no JSON", List.of(GOOD), calls, new ArrayList<>(),
                RemainingBudget.UNBOUNDED);

        assertEquals(0, calls.get());
        assertTrue(outcome.usable());
    }

    @Test
    @DisplayName("a recoverable broken reply is recovered, not re-asked")
    void recoverableIsNotReasked() throws Exception {
        var calls = new AtomicInteger();
        var v = validation("retry", 1);
        v.setRecoverFields(List.of(FIELD));

        Outcome outcome = run(policy(v), BROKEN, List.of(GOOD), calls, new ArrayList<>(), RemainingBudget.UNBOUNDED);

        assertEquals(0, calls.get());
        assertTrue(outcome.usable());
    }

    @Test
    @DisplayName("a broken reply that recovery cannot read (field absent) is still re-asked")
    void unrecoverableIsReasked() throws Exception {
        var calls = new AtomicInteger();
        var v = validation("retry", 1);
        v.setRecoverFields(List.of("somethingElse"));

        Outcome outcome = run(policy(v), BROKEN, List.of(GOOD), calls, new ArrayList<>(), RemainingBudget.UNBOUNDED);

        assertEquals(1, calls.get());
        assertEquals(GOOD, outcome.attempt().text());
    }

    @Test
    @DisplayName("text over wrapMaxChars is not wrappable and is re-asked")
    void overlongIsReasked() throws Exception {
        var calls = new AtomicInteger();
        var v = validation("retry", 1);
        v.setWrapMaxChars(10);

        Outcome outcome = run(policy(v), "prose that is longer than ten characters", List.of(GOOD), calls, new ArrayList<>(),
                RemainingBudget.UNBOUNDED);

        assertEquals(1, calls.get());
        assertNotNull(outcome.attempt());
        assertEquals(GOOD, outcome.attempt().text());
    }

    @Test
    @DisplayName("onSchemaMismatch=retry and onUnwrappable=retry share the one retry budget")
    void sharesBudgetWithSchemaMismatch() throws Exception {
        var calls = new AtomicInteger();
        var v = validation("retry", 1);
        v.setOnSchemaMismatch("retry");

        Outcome outcome = run(policy(v), BROKEN, List.of("{}", GOOD), calls, new ArrayList<>(), RemainingBudget.UNBOUNDED);

        assertEquals(1, calls.get(), "one re-ask in total, whichever policy asked for it");
        assertFalse(outcome.usable());
    }
}
