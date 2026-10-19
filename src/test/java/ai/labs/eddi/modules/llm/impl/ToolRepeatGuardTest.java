/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.ToolOutcomeClassifier.Outcome;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.ToolRepeatGuardConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRepeatGuardTest {

    private static final String A = "{\"id\":1,\"name\":\"x\"}";

    private static ToolRepeatGuard guard(ToolRepeatGuardConfig config) {
        var task = new LlmConfiguration.Task();
        task.setToolRepeatGuard(config);
        return ToolRepeatGuard.forTask(task, Map.of("calculate", "calculator"));
    }

    private static ToolRepeatGuard defaults() {
        return guard(null);
    }

    @Test
    void terminalFailureIsRefusedOnNextIdenticalCall() {
        var g = defaults();
        assertNull(g.check("getUser", A));
        g.record("getUser", A, Outcome.FAILED_TERMINAL, "{\"httpCode\":403}");
        var refusal = g.check("getUser", A);
        assertNotNull(refusal);
        assertEquals(ToolRepeatGuard.REASON_TERMINAL, refusal.reasonCode());
        assertTrue(ToolRepeatGuard.refusalEnvelope(refusal).contains("\"status\":\"NOT_EXECUTED\""));
        assertTrue(refusal.text().contains("HTTP 403"), refusal.text());
    }

    @Test
    void terminalFailureThenOtherSuccessAllowsRetry() {
        var g = defaults();
        g.record("getUser", A, Outcome.FAILED_TERMINAL, "{\"httpCode\":404}");
        g.record("createUser", "{}", Outcome.SUCCESS, "ok");
        assertNull(g.check("getUser", A));
    }

    @Test
    void retryableFailureRefusedAtThirdCall() {
        var g = defaults();
        g.record("t", A, Outcome.FAILED_RETRYABLE, "Error: boom");
        assertNull(g.check("t", A), "second attempt allowed");
        g.record("t", A, Outcome.FAILED_RETRYABLE, "Error: boom");
        assertEquals(ToolRepeatGuard.REASON_RETRYABLE, g.check("t", A).reasonCode());
    }

    @Test
    void successRepeatsAllowedByDefault() {
        var g = defaults();
        for (int i = 0; i < 10; i++) {
            assertNull(g.check("poll", A));
            g.record("poll", A, Outcome.SUCCESS, "pending");
        }
    }

    @Test
    void maxIdenticalCallsOptIn() {
        var config = new ToolRepeatGuardConfig();
        config.setMaxIdenticalCalls(2);
        var g = guard(config);
        g.record("poll", A, Outcome.SUCCESS, "x");
        assertNull(g.check("poll", A));
        g.record("poll", A, Outcome.SUCCESS, "x");
        assertEquals(ToolRepeatGuard.REASON_IDENTICAL_CALLS, g.check("poll", A).reasonCode());
    }

    @Test
    void exemptToolByDispatchNameOrSlugIsNeverRefused() {
        var config = new ToolRepeatGuardConfig();
        config.setExemptTools(List.of("getJob", "calculator"));
        var g = guard(config);
        g.record("getJob", A, Outcome.FAILED_TERMINAL, "{\"httpCode\":404}");
        assertNull(g.check("getJob", A));
        g.record("calculate", A, Outcome.FAILED_TERMINAL, "Error: x");
        assertNull(g.check("calculate", A), "slug resolves through the canonical names");
    }

    @Test
    void argumentKeyOrderIsIrrelevant() {
        var g = defaults();
        g.record("t", "{\"a\":1,\"b\":{\"y\":2,\"x\":3}}", Outcome.FAILED_TERMINAL, "{\"httpCode\":400}");
        assertNotNull(g.check("t", "{ \"b\":{\"x\":3,\"y\":2}, \"a\":1 }"));
        assertNull(g.check("t", "{\"a\":2,\"b\":{\"x\":3,\"y\":2}}"));
    }

    @Test
    void nonJsonArgumentsFallBackToRawString() {
        var g = defaults();
        g.record("t", " not json ", Outcome.FAILED_TERMINAL, "Error: x");
        assertNotNull(g.check("t", "not json"));
    }

    @Test
    void notExecutedIsNeverRecorded() {
        var g = defaults();
        g.record("t", A, Outcome.NOT_EXECUTED, "{\"status\":\"NOT_EXECUTED\"}");
        assertNull(g.check("t", A));
    }

    @Test
    void escalatesAfterConfiguredRefusals() {
        var g = defaults();
        g.record("t", A, Outcome.FAILED_TERMINAL, "{\"httpCode\":403}");
        for (int i = 0; i < 3; i++) {
            assertFalse(g.shouldEscalate());
            assertNotNull(g.check("t", A));
        }
        assertTrue(g.shouldEscalate());
    }

    @Test
    void disabledGuardIsNull() {
        var config = new ToolRepeatGuardConfig();
        config.setEnabled(false);
        assertNull(guard(config));
    }

    @Test
    void oldConfigWithoutFieldDeserializesAndIsEnabledByDefault() throws Exception {
        var mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        var task = mapper.readValue("{\"id\":\"t\",\"type\":\"openai\",\"maxToolIterations\":4}", LlmConfiguration.Task.class);
        assertNull(task.getToolRepeatGuard());
        assertNotNull(ToolRepeatGuard.forTask(task, Map.of()), "absent config means enabled with defaults");

        var configured = mapper.readValue("{\"id\":\"t\",\"toolRepeatGuard\":{\"enabled\":true,\"maxIdenticalFailures\":4,"
                + "\"maxIdenticalCalls\":-1,\"maxRefusalsPerTurn\":5,\"exemptTools\":[\"a\"]}}", LlmConfiguration.Task.class);
        assertEquals(4, configured.getToolRepeatGuard().getMaxIdenticalFailures());
        assertEquals(List.of("a"), configured.getToolRepeatGuard().getExemptTools());

        var off = mapper.readValue("{\"id\":\"t\",\"toolRepeatGuard\":{\"enabled\":false}}", LlmConfiguration.Task.class);
        assertNull(ToolRepeatGuard.forTask(off, Map.of()));
    }
}
