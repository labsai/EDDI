/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.utils.LogCaptureSupport;
import ai.labs.eddi.utils.LogCaptureSupport.CapturedRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deploy-time warning for an agent whose retry and timeout settings cannot
 * fit inside its {@code turnDeadlineMs}.
 */
class TurnBudgetValidatorTest {

    private static final URI LLM = URI.create("eddi://ai.labs.llm/llmstore/llmconfigs/l-1?version=1");
    private static final URI HTTP = URI.create("eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/h-1?version=1");

    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private IResourceClientLibrary resources;
    private TurnBudgetValidator validator;
    private AgentConfiguration agent;

    @BeforeEach
    void setUp() throws Exception {
        WorkflowTraversal.clearCache();
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        resources = mock(IResourceClientLibrary.class);
        validator = new TurnBudgetValidator(agentStore, workflowStore, resources);

        agent = new AgentConfiguration();
        agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));
        when(agentStore.read("budget-agent", 1)).thenReturn(agent);

        var workflow = new WorkflowConfiguration();
        workflow.setWorkflowSteps(new ArrayList<>(List.of(step("eddi://ai.labs.llm", LLM), step("eddi://ai.labs.httpcalls", HTTP))));
        when(workflowStore.read("wf-1", 1)).thenReturn(workflow);

        // One LLM task: a single attempt of 1s.
        var task = new LlmConfiguration.Task();
        task.setParameters(Map.of("timeout", "1000"));
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        task.setRetry(retry);
        when(resources.getResource(eq(LLM), eq(LlmConfiguration.class))).thenReturn(new LlmConfiguration(List.of(task)));

        // One httpcall: 5s, no retries.
        var call = new ApiCall();
        call.setTimeoutInMillis(5000);
        var httpCalls = new ApiCallsConfiguration();
        httpCalls.setHttpCalls(List.of(call));
        when(resources.getResource(eq(HTTP), eq(ApiCallsConfiguration.class))).thenReturn(httpCalls);
    }

    @AfterEach
    void tearDown() {
        WorkflowTraversal.clearCache();
    }

    private static WorkflowConfiguration.WorkflowStep step(String type, URI uri) {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create(type));
        step.setConfig(Map.of("uri", uri.toString()));
        return step;
    }

    private List<CapturedRecord> run() {
        return LogCaptureSupport.captureRecordsOf(TurnBudgetValidator.class, () -> validator.warnIfOverBudget(agent, "budget-agent", 1));
    }

    private static List<CapturedRecord> warnings(List<CapturedRecord> records) {
        return records.stream().filter(CapturedRecord::isWarning).toList();
    }

    @Test
    void withoutADeadlineNothingIsRead() throws Exception {
        assertTrue(warnings(run()).isEmpty());
        agent.setTurnDeadlineMs(0L);
        assertTrue(warnings(run()).isEmpty());
        verify(agentStore, never()).read(anyString(), anyInt());
    }

    @Test
    void aDeadlineTheWorstCaseExceedsIsWarnedAbout() {
        agent.setTurnDeadlineMs(5_000L);
        agent.setTurnDeadlineReserveMs(0L);
        var warnings = warnings(run());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().message().contains("static worst case 6000ms"), warnings.getFirst().message());
        assertTrue(warnings.getFirst().message().contains("LLM tasks 1000ms"), warnings.getFirst().message());
        assertTrue(warnings.getFirst().message().startsWith("Agent budget-agent v1"), warnings.getFirst().message());
    }

    @Test
    void aDeadlineThatFitsIsNotWarnedAbout() {
        agent.setTurnDeadlineMs(6_000L);
        agent.setTurnDeadlineReserveMs(0L);
        assertTrue(warnings(run()).isEmpty());
    }

    @Test
    void aNegativeReserveFallsBackToTheDefaultReserve() {
        // 6s fits a zero reserve exactly; the default reserve pushes it over.
        agent.setTurnDeadlineMs(6_000L);
        agent.setTurnDeadlineReserveMs(-1L);
        assertEquals(1, warnings(run()).size());
        agent.setTurnDeadlineReserveMs(null);
        assertEquals(1, warnings(run()).size());
    }

    @Test
    void llmConfigsWithoutTasksAddNothing() throws Exception {
        when(resources.getResource(eq(LLM), eq(LlmConfiguration.class))).thenReturn(new LlmConfiguration(null));
        agent.setTurnDeadlineMs(5_000L);
        agent.setTurnDeadlineReserveMs(0L);
        assertTrue(warnings(run()).isEmpty(), "only the 5s httpcall is left, which fits");
    }

    @Test
    void aFailureIsSwallowedAndNeverWarns() throws Exception {
        // A broken httpcalls document (a null call) fails the estimate itself.
        var broken = new ApiCallsConfiguration();
        broken.setHttpCalls(Arrays.asList((ApiCall) null));
        when(resources.getResource(eq(HTTP), eq(ApiCallsConfiguration.class))).thenReturn(broken);
        agent.setTurnDeadlineMs(1L);
        var records = run();
        assertTrue(warnings(records).isEmpty());
        assertTrue(records.stream().anyMatch(r -> r.message().contains("Turn budget check skipped")), records.toString());
    }
}
