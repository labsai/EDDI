/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tenant cost gate in {@code executeSingleToolCallResult} must refuse a
 * tool call for BOTH kinds of quota refusal, and must not describe a
 * quota-store outage as a tenant having spent its allowance.
 * <p>
 * {@code QuotaCheckResult} now carries {@code accountingUnavailable} beside
 * {@code allowed}. Both refuse the call — that part was always right — but the
 * operator-facing sentence in front of the refusal said "cost budget exceeded"
 * in both cases, so an operator grepping the logs during a database outage was
 * told a tenant was over budget. These tests pin that the refusal happens on
 * both paths, that the reason the model is handed is the store's own reason
 * string rather than a rewritten one, and that the tool never executes.
 * <p>
 * <strong>The sentence itself is asserted, not just the refusal.</strong> The
 * refusal is identical on both paths and predates this change — everything
 * downstream of the gate (the returned {@code "Error: <reason>"}, the trace
 * entry, the un-run executor) reads the store's own {@code reason()} and was
 * already correct. The <em>only</em> thing the branch decides is which of two
 * sentences the operator sees, so a test that never looks at the log cannot
 * fail when the branch is removed. Hence the handler: the assertions below
 * match on the fixed part of each sentence, never on the interpolated reason,
 * because the outage reason contains the words "accounting unavailable" itself
 * and would satisfy a naive substring check under the old single-sentence code.
 */
class ToolLoopRunnerTenantCostBudgetTest {

    private static final String OUTAGE_REASON = "Quota accounting unavailable — denying request for safety";
    private static final String OVER_BUDGET_REASON = "Monthly cost budget reached ($10.00)";

    /** The fixed part of each sentence — no reason string can produce either. */
    private static final String OUTAGE_SENTENCE = "Tenant cost accounting unavailable during tool call, refusing";
    private static final String OVER_BUDGET_SENTENCE = "Tenant cost budget exceeded during tool call";

    private ToolExecutionService toolExecutionService;
    private TenantQuotaService tenantQuotaService;
    private ToolExecutor executor;
    private ToolLoopRunner runner;
    private LlmConfiguration.Task task;

    private final List<String> warnings = new ArrayList<>();
    private Logger runnerLogger;
    private Handler logHandler;
    private Level previousLevel;
    private boolean previousUseParentHandlers;

    @BeforeEach
    void captureTheOperatorFacingLog() {
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() < Level.WARNING.intValue()) {
                    return;
                }
                // Printf-style calls may or may not be pre-formatted depending on the
                // JBoss Logging backend in use, so both halves are kept. The
                // assertions match on the fixed sentence only, which cannot come from
                // the interpolated argument either way.
                warnings.add(record.getMessage() + " " + Arrays.toString(record.getParameters()));
            }

            @Override
            public void flush() {
                // nothing is buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        // src/test/resources/logging.properties silences the whole ai.labs.eddi
        // namespace, so without raising the level the records never reach a handler.
        // Detaching the parent handlers keeps the refusal lines out of the surefire
        // output.
        runnerLogger = Logger.getLogger(ToolLoopRunner.class.getName());
        previousLevel = runnerLogger.getLevel();
        previousUseParentHandlers = runnerLogger.getUseParentHandlers();
        runnerLogger.setLevel(Level.ALL);
        runnerLogger.setUseParentHandlers(false);
        runnerLogger.addHandler(logHandler);
    }

    @AfterEach
    void releaseTheLogger() {
        runnerLogger.removeHandler(logHandler);
        runnerLogger.setLevel(previousLevel);
        runnerLogger.setUseParentHandlers(previousUseParentHandlers);
    }

    @BeforeEach
    void setUp() {
        toolExecutionService = mock(ToolExecutionService.class);
        // Straight-through, so a tool that DOES run is visible as an invocation of
        // the executor rather than being swallowed by the wrapper's own gates.
        lenient()
                .when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class),
                        nullable(String.class), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());

        var truncator = mock(ToolResponseTruncator.class);
        lenient().when(truncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(i -> i.getArgument(1));

        tenantQuotaService = mock(TenantQuotaService.class);
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("default");

        executor = mock(ToolExecutor.class);
        lenient().when(executor.execute(any(), nullable(String.class))).thenReturn("Order #7 shipped.");

        // A real guardrail, not null: the allowed path runs the result through it,
        // and a null collaborator would make "the tool ran" untestable.
        runner = new ToolLoopRunner(toolExecutionService, truncator, tenantQuotaService, null, null, null, null,
                new ToolResultGuardrail(new SimpleMeterRegistry()));

        task = new LlmConfiguration.Task();
        task.setId("llmTask-cost-gate");
    }

    private String execute(List<Map<String, Object>> trace) {
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("c1").name("get_order").arguments("{}").build();
        Map<String, ToolExecutor> executors = new HashMap<>();
        executors.put("get_order", executor);

        return runner.executeSingleToolCallResult(request, mock(IConversationMemory.class), trace, executors,
                Map.of(), Map.of(), Map.of("get_order", "http"), 100, null, "conv-1",
                false, false, false, task, false, List.of(), new ArrayList<>());
    }

    /** Whether any captured WARN carries the given fixed sentence. */
    private boolean warned(String sentence) {
        return warnings.stream().anyMatch(warning -> warning.contains(sentence));
    }

    /**
     * The regression the {@code accountingUnavailable} flag exists for: the store
     * could not answer, so the call is refused — but the refusal must carry the
     * outage's own reason, not a budget sentence.
     * <p>
     * The log assertion is the one that fails when the branch is removed: with a
     * single {@code LOGGER.warnf("Tenant cost budget exceeded during tool call:
     * %s", ...)} the refusal, the returned string and the trace entry are all
     * byte-for-byte identical, and only the sentence in front of them changes.
     */
    @Test
    @DisplayName("a quota-store outage refuses the tool call and reports the outage, not a spent budget")
    void accountingUnavailable_refusesAndNamesTheOutage() {
        when(tenantQuotaService.checkCostBudget("default")).thenReturn(QuotaCheckResult.unavailable(OUTAGE_REASON));
        var trace = new ArrayList<Map<String, Object>>();

        String result = execute(trace);

        assertEquals("Error: " + OUTAGE_REASON, result,
                "the model is handed the store's own reason so it can stop rather than retry a budget it has not spent");
        verify(executor, never()).execute(any(), nullable(String.class));

        var errors = trace.stream().filter(step -> "tool_error".equals(step.get("type"))).toList();
        assertEquals(1, errors.size(), trace.toString());
        assertEquals("get_order", errors.getFirst().get("tool"));
        assertEquals(OUTAGE_REASON, errors.getFirst().get("error"));
        assertTrue(((String) errors.getFirst().get("error")).contains("accounting unavailable"),
                "the trace entry an auditor reads must not say the tenant went over a limit");

        assertTrue(warned(OUTAGE_SENTENCE),
                "the operator has to be able to tell a quota-store outage from a spent allowance; saw: " + warnings);
        assertFalse(warned(OVER_BUDGET_SENTENCE),
                "an operator grepping for 'cost budget exceeded' during a database outage must not find this turn; saw: "
                        + warnings);
        assertEquals(1, warnings.size(), "one refusal, one sentence: " + warnings);
    }

    /**
     * The ordinary half of the same gate: a genuine over-budget denial. Asserted
     * alongside the outage half so a branch that simply renamed the sentence for
     * everybody — which would also make the test above pass — is caught here.
     */
    @Test
    @DisplayName("an over-budget denial refuses the tool call and reports the budget")
    void overBudget_refusesAndNamesTheBudget() {
        when(tenantQuotaService.checkCostBudget("default")).thenReturn(QuotaCheckResult.denied(OVER_BUDGET_REASON));
        var trace = new ArrayList<Map<String, Object>>();

        String result = execute(trace);

        assertEquals("Error: " + OVER_BUDGET_REASON, result);
        verify(executor, never()).execute(any(), nullable(String.class));
        assertEquals(OVER_BUDGET_REASON,
                trace.stream().filter(step -> "tool_error".equals(step.get("type"))).findFirst().orElseThrow().get("error"));

        assertTrue(warned(OVER_BUDGET_SENTENCE),
                "a tenant that really did spend its allowance must still be reported as such; saw: " + warnings);
        assertFalse(warned(OUTAGE_SENTENCE),
                "nothing is unavailable here — the store answered; saw: " + warnings);
        assertEquals(1, warnings.size(), "one refusal, one sentence: " + warnings);
    }

    /** An allowed check must leave the call alone — the gate is not a no-op. */
    @Test
    @DisplayName("an allowed cost check lets the tool run")
    void allowed_executesTheTool() {
        when(tenantQuotaService.checkCostBudget("default")).thenReturn(QuotaCheckResult.OK);
        var trace = new ArrayList<Map<String, Object>>();

        String result = execute(trace);

        assertTrue(result.contains("Order #7 shipped."), result);
        verify(executor).execute(any(), nullable(String.class));
        assertTrue(trace.stream().noneMatch(step -> "tool_error".equals(step.get("type"))), trace.toString());
        assertFalse(warned(OVER_BUDGET_SENTENCE), "a call that was allowed is not a refusal; saw: " + warnings);
        assertFalse(warned(OUTAGE_SENTENCE), "a call that was allowed is not a refusal; saw: " + warnings);
    }
}
