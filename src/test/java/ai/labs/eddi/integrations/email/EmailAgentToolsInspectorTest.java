/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The save-time warning for email targets whose agent can use tools without any
 * tool approval.
 */
class EmailAgentToolsInspectorTest {

    private static final String WF_ID = "0123456789abcdef01234567";
    private static final URI WORKFLOW = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF_ID + "?version=1");
    private static final URI LLM = URI.create("eddi://ai.labs.llm/llmstore/llmconfigs/llm-1?version=1");

    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private IResourceClientLibrary resources;
    private EmailAgentToolsInspector inspector;
    private AgentConfiguration agent;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        resources = mock(IResourceClientLibrary.class);
        inspector = new EmailAgentToolsInspector(agentStore, workflowStore, resources);
        agent = new AgentConfiguration();
        agent.setWorkflows(List.of(WORKFLOW));
        when(agentStore.getCurrentResourceId("agent-1")).thenReturn(resourceId("agent-1", 3));
        when(agentStore.read("agent-1", 3)).thenAnswer(inv -> agent);
    }

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    private static WorkflowConfiguration.WorkflowStep step(Object uri) {
        var step = new WorkflowConfiguration.WorkflowStep();
        Map<String, Object> config = new HashMap<>();
        if (uri != null) {
            config.put("uri", uri);
        }
        step.setConfig(config);
        return step;
    }

    private void workflow(WorkflowConfiguration.WorkflowStep... steps) throws Exception {
        var workflow = new WorkflowConfiguration();
        workflow.setWorkflowSteps(new ArrayList<>(List.of(steps)));
        when(workflowStore.read(WF_ID, 1)).thenReturn(workflow);
    }

    private void llm(LlmConfiguration.Task... tasks) throws Exception {
        when(resources.getResource(eq(LLM), eq(LlmConfiguration.class))).thenReturn(new LlmConfiguration(List.of(tasks)));
    }

    private static LlmConfiguration.Task agentTask() {
        var task = new LlmConfiguration.Task();
        task.setTools(List.of("calculator"));
        return task;
    }

    private static ToolApprovalsConfig approvals(String... patterns) {
        var config = new ToolApprovalsConfig();
        config.setRequireApproval(List.of(patterns));
        return config;
    }

    private static ChannelTarget target(String name, String agentId, ChannelTarget.TargetType type) {
        var target = new ChannelTarget();
        target.setName(name);
        target.setTargetId(agentId);
        target.setType(type);
        return target;
    }

    @Test
    void anAgentModeTaskWithoutApprovalsIsReported() throws Exception {
        workflow(step(LLM.toString()));
        llm(agentTask());
        assertEquals(List.of("support"), inspector.targetsWithUngatedTools(List.of(target("support", "agent-1", ChannelTarget.TargetType.AGENT))));
    }

    @Test
    void anAgentLevelApprovalGatesEveryTask() throws Exception {
        workflow(step(LLM.toString()));
        llm(agentTask());
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setToolApprovals(approvals("*"));
        agent.setHitlConfig(hitl);
        assertFalse(inspector.hasUngatedTools("agent-1"));
    }

    @Test
    void aTaskLevelApprovalGatesTheTask() throws Exception {
        workflow(step(LLM.toString()));
        var task = agentTask();
        task.setToolApprovals(approvals("send_*"));
        llm(task);
        assertFalse(inspector.hasUngatedTools("agent-1"));
    }

    @Test
    void anEmptyRequireApprovalListGatesNothing() throws Exception {
        workflow(step(LLM.toString()));
        var task = agentTask();
        task.setToolApprovals(new ToolApprovalsConfig());
        llm(task);
        assertTrue(inspector.hasUngatedTools("agent-1"));
    }

    @Test
    void aChatOnlyTaskHasNoTools() throws Exception {
        workflow(step(LLM.toString()));
        llm(new LlmConfiguration.Task());
        assertFalse(inspector.hasUngatedTools("agent-1"));
    }

    @Test
    void httpCallsInTheWorkflowAreToolsUnlessSwitchedOff() throws Exception {
        workflow(step(LLM.toString()), step("eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/h-1?version=1"));
        var chat = new LlmConfiguration.Task();
        llm(chat);
        assertTrue(inspector.hasUngatedTools("agent-1"));

        chat.setEnableHttpCallTools(false);
        assertFalse(inspector.hasUngatedTools("agent-1"));
    }

    @Test
    void legacyApiCallsCountAsHttpCalls() throws Exception {
        workflow(step("eddi://ai.labs.apicalls/apicallstore/apicalls/a-1?version=1"), step(LLM.toString()));
        llm(new LlmConfiguration.Task());
        assertTrue(inspector.hasUngatedTools("agent-1"));
    }

    @Test
    void mcpCallsInTheWorkflowAreToolsUnlessSwitchedOff() throws Exception {
        workflow(step("eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/m-1?version=1"), step(LLM.toString()));
        var chat = new LlmConfiguration.Task();
        llm(chat);
        assertTrue(inspector.hasUngatedTools("agent-1"));

        chat.setEnableMcpCallTools(false);
        assertFalse(inspector.hasUngatedTools("agent-1"));
    }

    @Test
    void stepsWithoutAUsableUriAreSkipped() throws Exception {
        var noConfig = new WorkflowConfiguration.WorkflowStep();
        workflow(noConfig, step(null), step(42), step("not a uri"), step("relative/path"),
                step("eddi://ai.labs.parser/parserstore/parsers/p-1?version=1"));
        assertFalse(inspector.hasUngatedTools("agent-1"));
    }

    @Test
    void unreadableOrEmptyDocumentsAreSkipped() throws Exception {
        workflow(step(LLM.toString()));
        when(resources.getResource(any(), eq(LlmConfiguration.class))).thenReturn(null);
        assertFalse(inspector.hasUngatedTools("agent-1"));

        when(resources.getResource(any(), eq(LlmConfiguration.class))).thenReturn(new LlmConfiguration(null));
        assertFalse(inspector.hasUngatedTools("agent-1"));

        when(workflowStore.read(WF_ID, 1)).thenReturn(new WorkflowConfiguration());
        assertFalse(inspector.hasUngatedTools("agent-1"));

        when(workflowStore.read(WF_ID, 1)).thenReturn(null);
        assertFalse(inspector.hasUngatedTools("agent-1"));
    }

    @Test
    void workflowUrisWithoutAValidIdAreSkipped() throws Exception {
        workflow(step(LLM.toString()));
        llm(agentTask());
        agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/not-an-id?version=1")));
        assertFalse(inspector.hasUngatedTools("agent-1"));
    }

    @Test
    void anAgentWithoutWorkflowsHasNoTools() throws Exception {
        agent.setWorkflows(null);
        assertFalse(inspector.hasUngatedTools("agent-1"));
        when(agentStore.read("agent-1", 3)).thenReturn(null);
        assertFalse(inspector.hasUngatedTools("agent-1"));
    }

    @Test
    void groupTargetsBlankTargetsAndFailuresAreNotReported() throws Exception {
        workflow(step(LLM.toString()));
        llm(agentTask());
        when(agentStore.getCurrentResourceId("broken")).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
        var targets = new ArrayList<ChannelTarget>();
        targets.add(null);
        targets.add(target("team", "agent-1", ChannelTarget.TargetType.GROUP));
        targets.add(target("nobody", null, ChannelTarget.TargetType.AGENT));
        targets.add(target("broken", "broken", ChannelTarget.TargetType.AGENT));
        targets.add(target("ok", "agent-1", ChannelTarget.TargetType.AGENT));
        assertEquals(List.of("ok"), inspector.targetsWithUngatedTools(targets));
        assertTrue(inspector.targetsWithUngatedTools(null).isEmpty());
    }
}
