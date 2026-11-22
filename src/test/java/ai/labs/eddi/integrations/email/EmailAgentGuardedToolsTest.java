/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The best-effort save-time half of the email escalation guard (email plan v3
 * §5.3): an agent a mail can reach must not hold, by name, a tool or an HTTP
 * call that changes channel integrations or decides approvals.
 */
@DisplayName("EmailAgentToolsInspector — guarded tools at save time")
class EmailAgentGuardedToolsTest {

    private static final String WF_ID = "0123456789abcdef01234567";
    private static final URI WORKFLOW = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF_ID + "?version=1");
    private static final URI LLM = URI.create("eddi://ai.labs.llm/llmstore/llmconfigs/llm-1?version=1");
    private static final URI MCP = URI.create("eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/m-1?version=1");
    private static final URI HTTP = URI.create("eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/h-1?version=1");

    private IWorkflowStore workflowStore;
    private IResourceClientLibrary resources;
    private EmailAgentToolsInspector inspector;

    @BeforeEach
    void setUp() throws Exception {
        IAgentStore agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        resources = mock(IResourceClientLibrary.class);
        inspector = new EmailAgentToolsInspector(agentStore, workflowStore, resources);
        var agent = new AgentConfiguration();
        agent.setWorkflows(List.of(WORKFLOW));
        when(agentStore.getCurrentResourceId("agent-1")).thenReturn(new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return "agent-1";
            }

            @Override
            public Integer getVersion() {
                return 3;
            }
        });
        when(agentStore.read("agent-1", 3)).thenReturn(agent);
    }

    private void workflow(URI... uris) throws Exception {
        var workflow = new WorkflowConfiguration();
        var steps = new ArrayList<WorkflowConfiguration.WorkflowStep>();
        for (URI uri : uris) {
            var step = new WorkflowConfiguration.WorkflowStep();
            Map<String, Object> config = new HashMap<>();
            config.put("uri", uri.toString());
            step.setConfig(config);
            steps.add(step);
        }
        workflow.setWorkflowSteps(steps);
        when(workflowStore.read(WF_ID, 1)).thenReturn(workflow);
    }

    private static List<ChannelTarget> targets() {
        var target = new ChannelTarget();
        target.setName("intake");
        target.setTargetId("agent-1");
        target.setType(ChannelTarget.TargetType.AGENT);
        var group = new ChannelTarget();
        group.setName("team");
        group.setTargetId("group-1");
        group.setType(ChannelTarget.TargetType.GROUP);
        return List.of(target, group);
    }

    @Test
    @DisplayName("an mcpcalls whitelist or call naming a guarded tool is found")
    void mcpCalls() throws Exception {
        workflow(MCP);
        var mcp = new McpCallsConfiguration();
        mcp.setToolsWhitelist(List.of("list_agents", "update_email_senders"));
        var call = new McpCall();
        call.setToolName("resume_conversation");
        mcp.setMcpCalls(List.of(call));
        when(resources.getResource(eq(MCP), eq(McpCallsConfiguration.class))).thenReturn(mcp);

        assertEquals(List.of("intake: update_email_senders", "intake: resume_conversation"), inspector.targetsHoldingGuardedTools(targets()));
    }

    @Test
    @DisplayName("an httpcall writing to EDDI's channel store is found, a read is not")
    void httpCalls() throws Exception {
        workflow(HTTP);
        var calls = new ApiCallsConfiguration();
        calls.setTargetServerUrl("http://localhost:7070");
        calls.setHttpCalls(List.of(call("allowSender", "POST", "/channelstore/channels/{id}/email-senders"),
                call("readSenders", "GET", "/channelstore/channels/{id}/email-senders")));
        when(resources.getResource(eq(HTTP), eq(ApiCallsConfiguration.class))).thenReturn(calls);

        var found = inspector.targetsHoldingGuardedTools(targets());

        assertEquals(1, found.size(), String.valueOf(found));
        assertTrue(found.getFirst().startsWith("intake: allowSender"));
    }

    @Test
    @DisplayName("an LLM task naming a guarded tool is found; other tools are not")
    void llmTools() throws Exception {
        workflow(LLM);
        var task = new LlmConfiguration.Task();
        task.setTools(List.of("calculator", "eddi_update_channel_integration"));
        when(resources.getResource(eq(LLM), eq(LlmConfiguration.class))).thenReturn(new LlmConfiguration(List.of(task)));

        assertEquals(List.of("intake: eddi_update_channel_integration"), inspector.targetsHoldingGuardedTools(targets()));
    }

    @Test
    @DisplayName("the save is refused, naming target and tool")
    @SuppressWarnings("unchecked")
    void supportRefuses() {
        var inspectorMock = mock(EmailAgentToolsInspector.class);
        when(inspectorMock.targetsHoldingGuardedTools(any())).thenReturn(List.of("intake: update_email_senders"));
        Instance<ConnectionResolver> resolvers = mock(Instance.class);
        when(resolvers.isResolvable()).thenReturn(true);
        when(resolvers.get()).thenReturn(mock(ConnectionResolver.class));
        EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
        when(directory.scan()).thenReturn(List.of());
        var support = new EmailChannelSupport(mock(SafeSocketConnector.class), resolvers, directory, mock(EmailPollScheduler.class),
                inspectorMock);
        var configuration = new ChannelIntegrationConfiguration();
        configuration.setPlatformConfig(Map.of("mode", "trigger", "transport", "imap", "connection", "exo", "host", "imap.example.com",
                "address", "in@example.com"));
        configuration.setTargets(targets());

        var error = assertThrows(BadRequestException.class, () -> support.validateAgainstDeployment(configuration, null));
        assertTrue(error.getMessage().contains("intake: update_email_senders"), error.getMessage());
    }

    private static ApiCall call(String name, String method, String path) {
        var request = new Request();
        request.setMethod(method);
        request.setPath(path);
        var call = new ApiCall();
        call.setName(name);
        call.setRequest(request);
        return call;
    }
}
