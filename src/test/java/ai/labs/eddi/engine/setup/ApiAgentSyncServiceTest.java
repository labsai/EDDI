/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.mcp.McpApiToolBuilder;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.ToolQuotaConfig;
import ai.labs.eddi.secrets.ISecretProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ApiAgentSyncService — re-sync of a setup-api agent")
class ApiAgentSyncServiceTest {

    private static final String AGENT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String WORKFLOW_ID = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String ITEMS_ID = "cccccccccccccccccccccccc";
    private static final String USERS_ID = "dddddddddddddddddddddddd";
    private static final String LLM_ID = "eeeeeeeeeeeeeeeeeeeeeeee";
    private static final String NEW_GROUP_ID = "ffffffffffffffffffffffff";

    private static final String WORKFLOW_LOC = "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1";
    private static final String ITEMS_LOC = "eddi://ai.labs.apicalls/apicallstore/apicalls/" + ITEMS_ID + "?version=1";
    private static final String USERS_LOC = "eddi://ai.labs.apicalls/apicallstore/apicalls/" + USERS_ID + "?version=1";
    private static final String LLM_LOC = "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=1";
    private static final String AUTH = "Bearer ${context:userToken}";

    private static final String SPEC_V1 = """
            {"openapi":"3.0.0","info":{"title":"T","version":"1"},"servers":[{"url":"https://api.example.com"}],
             "paths":{
               "/items":{"get":{"tags":["Items"],"operationId":"listItems","summary":"List items","responses":{"200":{"description":"ok"}}},
                         "post":{"tags":["Items"],"operationId":"createItem","summary":"Create","responses":{"201":{"description":"ok"}}}},
               "/users":{"get":{"tags":["Users"],"operationId":"listUsers","summary":"List users","responses":{"200":{"description":"ok"}}}}}}
            """;

    /** Users removed, listItems changed, getItem added. */
    private static final String SPEC_V2 = """
            {"openapi":"3.0.0","info":{"title":"T","version":"2"},"servers":[{"url":"https://api.example.com"}],
             "paths":{
               "/items":{"get":{"tags":["Items"],"operationId":"listItems","summary":"List all items, paged","responses":{"200":{"description":"ok"}}},
                         "post":{"tags":["Items"],"operationId":"createItem","summary":"Create","responses":{"201":{"description":"ok"}}}},
               "/items/{id}":{"get":{"tags":["Items"],"operationId":"getItem","summary":"One item",
                         "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string"}}],
                         "responses":{"200":{"description":"ok"}}}}}}
            """;

    /** Adds a new tag group, removes nothing. */
    private static final String SPEC_V3 = """
            {"openapi":"3.0.0","info":{"title":"T","version":"3"},"servers":[{"url":"https://api.example.com"}],
             "paths":{
               "/items":{"get":{"tags":["Items"],"operationId":"listItems","summary":"List items","responses":{"200":{"description":"ok"}}},
                         "post":{"tags":["Items"],"operationId":"createItem","summary":"Create","responses":{"201":{"description":"ok"}}}},
               "/users":{"get":{"tags":["Users"],"operationId":"listUsers","summary":"List users","responses":{"200":{"description":"ok"}}}},
               "/orders":{"delete":{"tags":["Orders"],"operationId":"deleteOrders","summary":"Purge","responses":{"204":{"description":"ok"}}}}}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private IRestAgentStore agentStore;
    private IRestWorkflowStore workflowStore;
    private IRestApiCallsStore apiCallsStore;
    private IRestLlmStore llmStore;
    private AgentConfiguration agent;
    private LlmConfiguration llm;
    private ApiAgentSyncService service;
    private ISecretProvider secretProvider;

    @BeforeEach
    void setUp() throws Exception {
        var factory = mock(IRestInterfaceFactory.class);
        agentStore = mock(IRestAgentStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        apiCallsStore = mock(IRestApiCallsStore.class);
        llmStore = mock(IRestLlmStore.class);
        when(factory.get(IRestAgentStore.class)).thenReturn(agentStore);
        when(factory.get(IRestWorkflowStore.class)).thenReturn(workflowStore);
        when(factory.get(IRestApiCallsStore.class)).thenReturn(apiCallsStore);
        when(factory.get(IRestLlmStore.class)).thenReturn(llmStore);
        secretProvider = mock(ISecretProvider.class);
        var setup = new AgentSetupService(factory, mock(IRestAgentAdministration.class), secretProvider, "http://localhost:11434");
        service = new ApiAgentSyncService(factory, setup);

        // The state a setup-api call with SPEC_V1 leaves behind.
        var v1 = McpApiToolBuilder.parseAndBuild(SPEC_V1, null, null, AUTH, null);
        AgentSetupService.markGenerated(v1, AUTH, null);
        when(apiCallsStore.readApiCalls(ITEMS_ID, 1)).thenReturn(copy(v1.configsByGroup().get("Items")));
        when(apiCallsStore.readApiCalls(USERS_ID, 1)).thenReturn(copy(v1.configsByGroup().get("Users")));

        var setupService = new AgentSetupService(factory, mock(IRestAgentAdministration.class), mock(ISecretProvider.class), "x");
        llm = setupService.createLlmConfig("openai", "gpt-4o", "${vault:k}", AgentSetupService.enrichSystemPrompt("You are ops.", v1.apiSummary()),
                false, null, null, null, false, false, new ArrayList<>(List.of(ITEMS_LOC, USERS_LOC)));
        when(llmStore.readLlm(LLM_ID, 1)).thenReturn(llm);

        var workflow = setupService.createWorkflowConfig("eddi://ai.labs.parser/parserstore/parsers/111111111111111111111111?version=1",
                "eddi://ai.labs.rules/rulestore/rulesets/222222222222222222222222?version=1", List.of(ITEMS_LOC, USERS_LOC), null, LLM_LOC, null);
        when(workflowStore.readWorkflow(WORKFLOW_ID, 1)).thenReturn(workflow);

        agent = new AgentConfiguration();
        agent.setWorkflows(List.of(URI.create(WORKFLOW_LOC)));
        var hitl = new AgentConfiguration.HitlConfig();
        var approvals = new ToolApprovalsConfig();
        approvals.setRequireApproval(List.of("http.post:*"));
        hitl.setToolApprovals(approvals);
        agent.setHitlConfig(hitl);
        when(agentStore.getCurrentVersion(AGENT_ID)).thenReturn(1);
        when(agentStore.readAgent(AGENT_ID, 1)).thenReturn(agent);

        when(apiCallsStore.updateApiCalls(anyString(), anyInt(), any()))
                .thenAnswer(inv -> created("eddi://ai.labs.apicalls/apicallstore/apicalls/" + inv.getArgument(0) + "?version=2"));
        when(apiCallsStore.createApiCalls(any())).thenReturn(created("eddi://ai.labs.apicalls/apicallstore/apicalls/" + NEW_GROUP_ID + "?version=1"));
        when(llmStore.updateLlm(anyString(), anyInt(), any())).thenReturn(created("eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=2"));
        when(workflowStore.updateWorkflow(anyString(), anyInt(), any()))
                .thenReturn(created("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=2"));
        when(agentStore.updateAgent(anyString(), anyInt(), any(), anyBoolean()))
                .thenReturn(created("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=2"));
    }

    private ApiCallsConfiguration copy(ApiCallsConfiguration config) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(config), ApiCallsConfiguration.class);
    }

    private static Response created(String location) {
        // A default answer rather than when(): this is called inside other stubbings.
        return mock(Response.class, inv -> "getHeaderString".equals(inv.getMethod().getName()) ? location : null);
    }

    private static SyncApiAgentRequest request(String spec, AgentConfiguration.HitlConfig hitl, Boolean dryRun) {
        return new SyncApiAgentRequest(spec, null, null, null, null, hitl, null, false, null, dryRun);
    }

    private void verifyNothingWritten() {
        verify(apiCallsStore, never()).updateApiCalls(anyString(), anyInt(), any());
        verify(apiCallsStore, never()).createApiCalls(any());
        verify(llmStore, never()).updateLlm(anyString(), anyInt(), any());
        verify(workflowStore, never()).updateWorkflow(anyString(), anyInt(), any());
        verify(agentStore, never()).updateAgent(anyString(), anyInt(), any(), anyBoolean());
    }

    @Test
    @DisplayName("reports added, changed and removed tools and saves a compatible agent version")
    void addedChangedRemoved() throws Exception {
        var quotas = new ToolQuotaConfig();
        var rule = new ToolQuotaConfig.Rule();
        rule.setLimit(5);
        rule.setWindow("P1D");
        quotas.setTools(new LinkedHashMap<>(Map.of("createItem", rule)));
        llm.tasks().getFirst().setToolQuotas(quotas);
        llm.tasks().getFirst().getParameters().put("modelName", "integrator-chosen-model");

        SyncResult result = service.sync(AGENT_ID, request(SPEC_V2, null, null));

        assertEquals(List.of("getItem"), result.addedTools());
        assertEquals(List.of("listItems"), result.changedTools());
        assertEquals(List.of("listUsers"), result.removedTools());
        assertEquals(2, result.newVersion());

        // Compatible save: running conversations follow the new version.
        verify(agentStore).updateAgent(eq(AGENT_ID), eq(1), any(), eq(true));
        verify(apiCallsStore).updateApiCalls(eq(ITEMS_ID), eq(1), any());
        verify(apiCallsStore, never()).createApiCalls(any());

        ArgumentCaptor<WorkflowConfiguration> workflow = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        verify(workflowStore).updateWorkflow(eq(WORKFLOW_ID), eq(1), workflow.capture());
        String steps = mapper.writeValueAsString(workflow.getValue());
        assertFalse(steps.contains(USERS_ID), steps);
        assertTrue(steps.contains(ITEMS_ID + "?version=2"), steps);
        assertTrue(steps.contains(LLM_ID + "?version=2"), steps);

        ArgumentCaptor<LlmConfiguration> written = ArgumentCaptor.forClass(LlmConfiguration.class);
        verify(llmStore).updateLlm(eq(LLM_ID), eq(1), written.capture());
        var task = written.getValue().tasks().getFirst();
        assertEquals(List.of("eddi://ai.labs.apicalls/apicallstore/apicalls/" + ITEMS_ID + "?version=2"), task.getTools());
        // Integrator-owned parts survive.
        assertEquals("integrator-chosen-model", task.getParameters().get("modelName"));
        assertEquals(5, task.getToolQuotas().getTools().get("createItem").getLimit());
        String prompt = task.getParameters().get("systemMessage");
        assertTrue(prompt.startsWith("You are ops."), prompt);
        assertTrue(prompt.contains("List all items, paged") && !prompt.contains("List users"), prompt);

        // The gate is the stored one.
        assertEquals(List.of("http.post:*"), agent.getHitlConfig().getToolApprovals().getRequireApproval());
    }

    @Test
    @DisplayName("a removed tool still referenced is refused, naming each reference, with nothing written")
    void danglingReferencesRefused() {
        var quotas = new ToolQuotaConfig();
        var rule = new ToolQuotaConfig.Rule();
        rule.setLimit(1);
        rule.setWindow("P1D");
        quotas.setTools(new LinkedHashMap<>(Map.of("listUsers", rule)));
        llm.tasks().getFirst().setToolQuotas(quotas);
        agent.getHitlConfig().getToolApprovals().setExempt(List.of("http:listUsers", "http.get:*"));

        var refused = assertThrows(ApiAgentSyncService.DanglingReferencesException.class,
                () -> service.sync(AGENT_ID, request(SPEC_V2, null, null)));

        assertEquals(2, refused.references().size(), refused.references().toString());
        assertTrue(refused.references().stream().anyMatch(r -> r.contains("toolQuotas.tools.listUsers")), refused.references().toString());
        assertTrue(refused.references().stream().anyMatch(r -> r.contains("toolApprovals.exempt 'http:listUsers'")),
                refused.references().toString());
        verifyNothingWritten();
    }

    @Test
    @DisplayName("the gate is never weakened: requested rules are added, exemptions are not, nothing is removed")
    void gateNeverWeakened() throws Exception {
        var hitl = new AgentConfiguration.HitlConfig();
        var approvals = new ToolApprovalsConfig();
        approvals.setRequireApproval(List.of("http.delete:*"));
        approvals.setExempt(List.of("*"));
        hitl.setToolApprovals(approvals);

        SyncResult result = service.sync(AGENT_ID, request(SPEC_V3, hitl, null));

        assertEquals(List.of("deleteOrders"), result.addedTools());
        assertEquals(List.of("http.delete:*"), result.gateRulesAdded());
        ArgumentCaptor<AgentConfiguration> saved = ArgumentCaptor.forClass(AgentConfiguration.class);
        verify(agentStore).updateAgent(eq(AGENT_ID), eq(1), saved.capture(), eq(true));
        var gate = saved.getValue().getHitlConfig().getToolApprovals();
        assertEquals(List.of("http.post:*", "http.delete:*"), gate.getRequireApproval());
        assertTrue(gate.getExempt() == null || gate.getExempt().isEmpty(), String.valueOf(gate.getExempt()));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("exempt")), result.warnings().toString());
        // A new tag group becomes a new httpcalls config wired into the workflow and
        // the LLM task.
        verify(apiCallsStore).createApiCalls(any());
        ArgumentCaptor<LlmConfiguration> written = ArgumentCaptor.forClass(LlmConfiguration.class);
        verify(llmStore).updateLlm(eq(LLM_ID), eq(1), written.capture());
        assertTrue(written.getValue().tasks().getFirst().getTools().stream().anyMatch(t -> t.contains(NEW_GROUP_ID)));
    }

    @Test
    @DisplayName("dryRun reports the same diff and writes nothing")
    void dryRunWritesNothing() throws Exception {
        SyncResult result = service.sync(AGENT_ID, request(SPEC_V2, null, true));

        assertTrue(result.dryRun());
        assertEquals(List.of("getItem"), result.addedTools());
        assertEquals(List.of("listUsers"), result.removedTools());
        assertEquals(1, result.newVersion());
        verifyNothingWritten();
    }

    @Test
    @DisplayName("an unchanged spec writes nothing")
    void unchangedWritesNothing() throws Exception {
        SyncResult result = service.sync(AGENT_ID, request(SPEC_V1, null, null));

        assertTrue(result.addedTools().isEmpty() && result.changedTools().isEmpty() && result.removedTools().isEmpty());
        verifyNothingWritten();
    }

    @Test
    @DisplayName("an agent whose httpcalls carry no setup-api marker is refused with how to add it")
    void unmarkedAgentRefused() throws Exception {
        var unmarked = copy(McpApiToolBuilder.parseAndBuild(SPEC_V1, null, null, AUTH, null).configsByGroup().get("Items"));
        when(apiCallsStore.readApiCalls(ITEMS_ID, 1)).thenReturn(unmarked);
        when(apiCallsStore.readApiCalls(USERS_ID, 1)).thenReturn(unmarked);

        var refused = assertThrows(AgentSetupService.AgentSetupException.class, () -> service.sync(AGENT_ID, request(SPEC_V2, null, null)));

        assertTrue(refused.getMessage().contains("openApiOrigin"), refused.getMessage());
        verifyNothingWritten();
    }

    @Test
    @DisplayName("the generated calls keep sending the recorded apiAuth when the request carries none")
    void keepsRecordedApiAuth() throws Exception {
        service.sync(AGENT_ID, request(SPEC_V2, null, null));

        ArgumentCaptor<ApiCallsConfiguration> written = ArgumentCaptor.forClass(ApiCallsConfiguration.class);
        verify(apiCallsStore).updateApiCalls(eq(ITEMS_ID), eq(1), written.capture());
        written.getValue().getHttpCalls().forEach(call -> assertEquals(AUTH, call.getRequest().getHeaders().get("Authorization")));
        assertEquals("Items", written.getValue().getOpenApiOrigin().getGroup());
    }

    @Test
    @DisplayName("an unknown agent is a not-found")
    void unknownAgent() {
        when(agentStore.getCurrentVersion("nope")).thenReturn(null);
        assertThrows(ApiAgentSyncService.AgentSyncNotFoundException.class, () -> service.sync("nope", request(SPEC_V2, null, null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"${eddivault:crm-token}", "Bearer ${vault:crm-token}", "${connection:crm}"})
    @DisplayName("a recorded reference goes through the vault step unchanged and never touches the vault")
    void recordedReferencePassesThroughUnchanged(String reference) throws Exception {
        var v1 = McpApiToolBuilder.parseAndBuild(SPEC_V1, null, null, reference, null);
        AgentSetupService.markGenerated(v1, reference, null);
        when(apiCallsStore.readApiCalls(ITEMS_ID, 1)).thenReturn(copy(v1.configsByGroup().get("Items")));
        when(apiCallsStore.readApiCalls(USERS_ID, 1)).thenReturn(copy(v1.configsByGroup().get("Users")));

        service.sync(AGENT_ID, request(SPEC_V2, null, null));

        ArgumentCaptor<ApiCallsConfiguration> written = ArgumentCaptor.forClass(ApiCallsConfiguration.class);
        verify(apiCallsStore).updateApiCalls(eq(ITEMS_ID), eq(1), written.capture());
        written.getValue().getHttpCalls().forEach(call -> assertEquals(reference, call.getRequest().getHeaders().get("Authorization")));
        verifyNoInteractions(secretProvider);
    }

    @Test
    @DisplayName("a refused sync never reaches the vault, even with a plaintext apiAuth to vault")
    void refusedSyncLeavesTheVaultUntouched() {
        agent.getHitlConfig().getToolApprovals().setExempt(List.of("http:listUsers"));
        var plaintext = new SyncApiAgentRequest(SPEC_V2, null, null, "Bearer plaintext-credential-0123456789", null, null, null, false, null,
                null);

        assertThrows(ApiAgentSyncService.DanglingReferencesException.class, () -> service.sync(AGENT_ID, plaintext));

        verifyNoInteractions(secretProvider);
        verifyNothingWritten();
    }
}