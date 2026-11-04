/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.secrets.ISecretProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AgentSetupService — setup-api dryRun")
class AgentSetupApiDryRunTest {

    private static final String SPEC = """
            {"openapi":"3.0.0","info":{"title":"T","version":"1"},"servers":[{"url":"https://api.example.com"}],
             "paths":{"/items":{"get":{"tags":["Items"],"operationId":"listItems","summary":"List","responses":{"200":{"description":"ok"}}},
                                "post":{"tags":["Items"],"operationId":"createItem","responses":{"201":{"description":"ok"}}}}}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    /** All stores, the vault and the deployer of one service instance. */
    private record Fixture(AgentSetupService service, IRestInterfaceFactory factory, ISecretProvider secrets, IRestAgentAdministration admin,
            IRestApiCallsStore apiCalls, IRestParserStore parser, IRestRuleSetStore rules, IRestLlmStore llm, IRestWorkflowStore workflow,
            IRestAgentStore agent, IRestMcpCallsStore mcp, IRestDocumentDescriptorStore descriptors) {
    }

    private static Response created(String location) {
        // A default answer rather than when(): this is called inside other stubbings.
        return mock(Response.class, inv -> "getHeaderString".equals(inv.getMethod().getName()) ? location : null);
    }

    private static Fixture fixture() throws Exception {
        var factory = mock(IRestInterfaceFactory.class);
        var f = new Fixture(null, factory, mock(ISecretProvider.class), mock(IRestAgentAdministration.class), mock(IRestApiCallsStore.class),
                mock(IRestParserStore.class), mock(IRestRuleSetStore.class), mock(IRestLlmStore.class), mock(IRestWorkflowStore.class),
                mock(IRestAgentStore.class), mock(IRestMcpCallsStore.class), mock(IRestDocumentDescriptorStore.class));
        when(factory.get(IRestApiCallsStore.class)).thenReturn(f.apiCalls());
        when(factory.get(IRestParserStore.class)).thenReturn(f.parser());
        when(factory.get(IRestRuleSetStore.class)).thenReturn(f.rules());
        when(factory.get(IRestLlmStore.class)).thenReturn(f.llm());
        when(factory.get(IRestWorkflowStore.class)).thenReturn(f.workflow());
        when(factory.get(IRestAgentStore.class)).thenReturn(f.agent());
        when(factory.get(IRestMcpCallsStore.class)).thenReturn(f.mcp());
        when(factory.get(IRestDocumentDescriptorStore.class)).thenReturn(f.descriptors());
        var service = new AgentSetupService(factory, f.admin(), f.secrets(), "http://localhost:11434");
        return new Fixture(service, factory, f.secrets(), f.admin(), f.apiCalls(), f.parser(), f.rules(), f.llm(), f.workflow(), f.agent(), f.mcp(),
                f.descriptors());
    }

    private static CreateApiAgentRequest request(String apiAuth) {
        return new CreateApiAgentRequest("Ops", "You are ops.", SPEC, "ollama", "llama3", null, null, apiAuth, null, null, null, false, null,
                null, null, "https://mcp.example.com/sse", null, null, null, null, null);
    }

    @Test
    @DisplayName("a dry run touches no store, no vault, no descriptor and no deployer")
    void writesNothing() throws Exception {
        var f = fixture();

        SetupResult result = f.service().createApiAgent(request("Bearer ${context:userToken}").asDryRun());

        assertEquals("api_agent_dry_run", result.action());
        assertNull(result.agentId());
        verifyNoInteractions(f.apiCalls(), f.parser(), f.rules(), f.llm(), f.workflow(), f.agent(), f.mcp(), f.descriptors(), f.secrets(),
                f.admin());
        assertTrue(result.resources().containsKey("apiCallsConfigurations"));
        assertTrue(result.resources().containsKey("agentConfiguration"));
        assertEquals(List.of("listItems", "createItem"), result.resources().get("toolNames"));
        var llm = (LlmConfiguration) result.resources().get("llmConfiguration");
        assertEquals(1, llm.tasks().getFirst().getTools().size());
        assertTrue(llm.tasks().getFirst().getTools().getFirst().startsWith(AgentSetupService.APICALLS_PREFIX + AgentSetupService.DRY_RUN_ID_PREFIX));
        assertFalse(result.resources().containsKey("httpCallsLocations"), "no location is recorded for rollback");
    }

    @Test
    @DisplayName("what a dry run shows is what a real setup creates")
    @SuppressWarnings("unchecked")
    void matchesRealSetup() throws Exception {
        var dry = fixture();
        SetupResult preview = dry.service().createApiAgent(request("Bearer ${context:userToken}").asDryRun());
        var previewed = (Map<String, ApiCallsConfiguration>) preview.resources().get("apiCallsConfigurations");

        var real = fixture();
        when(real.apiCalls().createApiCalls(any()))
                .thenReturn(created("eddi://ai.labs.apicalls/apicallstore/apicalls/aaaaaaaaaaaaaaaaaaaaaaaa?version=1"));
        when(real.parser().createParser(any())).thenReturn(created("eddi://ai.labs.parser/parserstore/parsers/bbbbbbbbbbbbbbbbbbbbbbbb?version=1"));
        when(real.rules().createRuleSet(any())).thenReturn(created("eddi://ai.labs.rules/rulestore/rulesets/cccccccccccccccccccccccc?version=1"));
        when(real.llm().createLlm(any())).thenReturn(created("eddi://ai.labs.llm/llmstore/llms/dddddddddddddddddddddddd?version=1"));
        when(real.mcp().createMcpCalls(any()))
                .thenReturn(created("eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/eeeeeeeeeeeeeeeeeeeeeeee?version=1"));
        when(real.workflow().createWorkflow(any()))
                .thenReturn(created("eddi://ai.labs.workflow/workflowstore/workflows/ffffffffffffffffffffffff?version=1"));
        when(real.agent().createAgent(any())).thenReturn(created("eddi://ai.labs.agent/agentstore/agents/111111111111111111111111?version=1"));
        real.service().createApiAgent(request("Bearer ${context:userToken}"));

        ArgumentCaptor<ApiCallsConfiguration> createdConfig = ArgumentCaptor.forClass(ApiCallsConfiguration.class);
        verify(real.apiCalls()).createApiCalls(createdConfig.capture());
        assertEquals(mapper.writeValueAsString(createdConfig.getValue()), mapper.writeValueAsString(previewed.get("Items")));
        // The marker sync relies on is set on both.
        assertEquals("Items", createdConfig.getValue().getOpenApiOrigin().getGroup());
        assertEquals("Authorization", createdConfig.getValue().getOpenApiOrigin().getAuthHeader());

        ArgumentCaptor<LlmConfiguration> createdLlm = ArgumentCaptor.forClass(LlmConfiguration.class);
        verify(real.llm()).createLlm(createdLlm.capture());
        var previewedLlm = (LlmConfiguration) preview.resources().get("llmConfiguration");
        assertEquals(createdLlm.getValue().tasks().getFirst().getParameters(), previewedLlm.tasks().getFirst().getParameters());
    }

    @Test
    @DisplayName("a plaintext apiAuth is never vaulted or echoed by a dry run")
    @SuppressWarnings("unchecked")
    void plaintextApiAuthNotEchoed() throws Exception {
        var f = fixture();

        SetupResult result = f.service().createApiAgent(request("Bearer sk-live-plaintext-credential").asDryRun());

        String shown = mapper.writeValueAsString(result.resources().get("apiCallsConfigurations"));
        assertFalse(shown.contains("sk-live-plaintext-credential"), shown);
        assertTrue(shown.contains(AgentSetupService.DRY_RUN_VAULT_PLACEHOLDER), shown);
        verifyNoInteractions(f.secrets());
    }

    @Test
    @DisplayName("a dry run refuses what a real setup refuses: apiAuth mixing a reference with text")
    void refusesLikeRealSetup() throws Exception {
        var f = fixture();

        assertThrows(AgentSetupService.AgentSetupException.class,
                () -> f.service().createApiAgent(request("Bearer ${context:userToken} trailing").asDryRun()));
        verifyNoInteractions(f.apiCalls(), f.secrets());
    }

    @Test
    @DisplayName("dryRun is hidden from the OpenAPI schema, so the operator's generated setup-api tool cannot carry it")
    void hiddenFromSchema() throws Exception {
        var component = CreateApiAgentRequest.class.getRecordComponents()[CreateApiAgentRequest.class.getRecordComponents().length - 1];
        assertEquals("dryRun", component.getName());
        // @Schema does not target record components; javac propagates it to the field.
        Schema schema = CreateApiAgentRequest.class.getDeclaredField("dryRun").getAnnotation(Schema.class);
        assertTrue(schema != null && schema.hidden(), "dryRun must stay @Schema(hidden = true)");
    }
}
