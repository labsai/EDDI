/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.engine.a2a.A2AModels.*;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RestA2AEndpoint — JAX-RS endpoints for A2A protocol.
 */
class RestA2AEndpointTest {

    private static final String AGENT_ID = "test-agent-id";

    private AgentCardService agentCardService;
    private A2ATaskHandler taskHandler;
    private CapabilityRegistryService capabilityRegistryService;
    private RestA2AEndpoint endpoint;

    @BeforeEach
    void setUp() {
        agentCardService = mock(AgentCardService.class);
        taskHandler = mock(A2ATaskHandler.class);
        capabilityRegistryService = mock(CapabilityRegistryService.class);
    }

    private RestA2AEndpoint createEndpoint(boolean a2aEnabled, boolean capabilitiesPublic) {
        return new RestA2AEndpoint(agentCardService, taskHandler, capabilityRegistryService,
                a2aEnabled, capabilitiesPublic);
    }

    // ==================== getDefaultAgentCard ====================

    @Test
    void getDefaultAgentCard_disabled_returns404() {
        endpoint = createEndpoint(false, false);

        Response response = endpoint.getDefaultAgentCard();

        assertEquals(404, response.getStatus());
    }

    @Test
    void getDefaultAgentCard_noAgents_returns404() {
        endpoint = createEndpoint(true, false);
        when(agentCardService.listA2AAgents()).thenReturn(List.of());

        Response response = endpoint.getDefaultAgentCard();

        assertEquals(404, response.getStatus());
        // Should contain error message
        assertNotNull(response.getEntity());
    }

    @Test
    void getDefaultAgentCard_success_returnsFirstCard() {
        endpoint = createEndpoint(true, false);
        var card1 = new AgentCard("Agent1", "First agent", "http://localhost/a2a/agents/1",
                "EDDI", "1.0", null, null, null);
        var card2 = new AgentCard("Agent2", "Second agent", "http://localhost/a2a/agents/2",
                "EDDI", "1.0", null, null, null);
        when(agentCardService.listA2AAgents()).thenReturn(List.of(card1, card2));

        Response response = endpoint.getDefaultAgentCard();

        assertEquals(200, response.getStatus());
        assertEquals(card1, response.getEntity());
    }

    // ==================== getAgentCard ====================

    @Test
    void getAgentCard_disabled_returns404() {
        endpoint = createEndpoint(false, false);

        Response response = endpoint.getAgentCard(AGENT_ID);

        assertEquals(404, response.getStatus());
    }

    @Test
    void getAgentCard_notFound_returns404() {
        endpoint = createEndpoint(true, false);
        when(agentCardService.getAgentCard(AGENT_ID)).thenReturn(null);

        Response response = endpoint.getAgentCard(AGENT_ID);

        assertEquals(404, response.getStatus());
        assertNotNull(response.getEntity());
    }

    @Test
    void getAgentCard_success_returnsCard() {
        endpoint = createEndpoint(true, false);
        var card = new AgentCard("TestAgent", "A test agent",
                "http://localhost/a2a/agents/" + AGENT_ID,
                "EDDI", "1.0",
                new AgentCapabilities(true, false, true),
                List.of(new AgentSkill("skill1", "Greeting", "Says hello", List.of("greeting"), List.of("Hello!"))),
                null);
        when(agentCardService.getAgentCard(AGENT_ID)).thenReturn(card);

        Response response = endpoint.getAgentCard(AGENT_ID);

        assertEquals(200, response.getStatus());
        assertEquals(card, response.getEntity());
    }

    // ==================== listA2AAgents ====================

    @Test
    void listA2AAgents_disabled_returnsEmptyList() {
        endpoint = createEndpoint(false, false);

        Response response = endpoint.listA2AAgents();

        assertEquals(200, response.getStatus());
        assertEquals(List.of(), response.getEntity());
    }

    @Test
    void listA2AAgents_enabled_returnsCards() {
        endpoint = createEndpoint(true, false);
        var card = new AgentCard("Agent", "desc", "http://localhost", "EDDI", "1.0", null, null, null);
        when(agentCardService.listA2AAgents()).thenReturn(List.of(card));

        Response response = endpoint.listA2AAgents();

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        var cards = (List<AgentCard>) response.getEntity();
        assertEquals(1, cards.size());
        assertEquals("Agent", cards.getFirst().name());
    }

    // ==================== searchCapabilities ====================

    @Test
    void searchCapabilities_disabled_returns404() {
        endpoint = createEndpoint(false, false);

        Response response = endpoint.searchCapabilities("greeting", "highest_confidence");

        assertEquals(404, response.getStatus());
    }

    @Test
    void searchCapabilities_notPublic_returns404() {
        endpoint = createEndpoint(true, false);

        Response response = endpoint.searchCapabilities("greeting", "highest_confidence");

        assertEquals(404, response.getStatus());
    }

    @Test
    void searchCapabilities_nullSkill_returns400() {
        endpoint = createEndpoint(true, true);

        Response response = endpoint.searchCapabilities(null, "highest_confidence");

        assertEquals(400, response.getStatus());
    }

    @Test
    void searchCapabilities_blankSkill_returns400() {
        endpoint = createEndpoint(true, true);

        Response response = endpoint.searchCapabilities("  ", "highest_confidence");

        assertEquals(400, response.getStatus());
    }

    @Test
    void searchCapabilities_success_returnsMatches() {
        endpoint = createEndpoint(true, true);
        var match = new CapabilityMatch(AGENT_ID, "greeting", "0.95", Map.of());
        when(capabilityRegistryService.findBySkill("greeting", "highest_confidence"))
                .thenReturn(List.of(match));

        Response response = endpoint.searchCapabilities("greeting", "highest_confidence");

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        var matches = (List<CapabilityMatch>) response.getEntity();
        assertEquals(1, matches.size());
        assertEquals(AGENT_ID, matches.getFirst().agentId());
    }

    @Test
    void searchCapabilities_emptyMatches_returns200() {
        endpoint = createEndpoint(true, true);
        when(capabilityRegistryService.findBySkill("unknown", "highest_confidence"))
                .thenReturn(List.of());

        Response response = endpoint.searchCapabilities("unknown", "highest_confidence");

        assertEquals(200, response.getStatus());
    }

    // ==================== listCapabilitySkills ====================

    @Test
    void listCapabilitySkills_disabled_returns404() {
        endpoint = createEndpoint(false, false);

        Response response = endpoint.listCapabilitySkills();

        assertEquals(404, response.getStatus());
    }

    @Test
    void listCapabilitySkills_notPublic_returns404() {
        endpoint = createEndpoint(true, false);

        Response response = endpoint.listCapabilitySkills();

        assertEquals(404, response.getStatus());
    }

    @Test
    void listCapabilitySkills_success_returnsSkills() {
        endpoint = createEndpoint(true, true);
        when(capabilityRegistryService.getAllSkills())
                .thenReturn(Set.of("greeting", "weather", "support"));

        Response response = endpoint.listCapabilitySkills();

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        var skills = (Set<String>) response.getEntity();
        assertEquals(3, skills.size());
        assertTrue(skills.contains("greeting"));
    }

    // ==================== handleJsonRpc ====================

    @Test
    void handleJsonRpc_disabled_returnsError() {
        endpoint = createEndpoint(false, false);
        var request = new JsonRpcRequest("2.0", "tasks/send", Map.of(), "req-1");

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus()); // JSON-RPC errors are 200 OK
        var body = (JsonRpcResponse) response.getEntity();
        assertNotNull(body.error());
        assertEquals(A2AModels.ERROR_METHOD_NOT_FOUND, body.error().code());
    }

    @Test
    void handleJsonRpc_nullMethod_returnsInvalidParams() {
        endpoint = createEndpoint(true, false);
        var request = new JsonRpcRequest("2.0", null, Map.of(), "req-1");

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNotNull(body.error());
        assertEquals(A2AModels.ERROR_INVALID_PARAMS, body.error().code());
    }

    @Test
    void handleJsonRpc_unknownMethod_returnsError() {
        endpoint = createEndpoint(true, false);
        var request = new JsonRpcRequest("2.0", "tasks/unknown", Map.of(), "req-1");

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNotNull(body.error());
        assertEquals(A2AModels.ERROR_METHOD_NOT_FOUND, body.error().code());
        assertTrue(body.error().message().contains("Unknown method"));
    }

    // ==================== handleJsonRpc — tasks/send ====================

    @Test
    void handleJsonRpc_tasksSend_success() throws Exception {
        endpoint = createEndpoint(true, false);
        var params = Map.<String, Object>of("message", "Hello");
        var request = new JsonRpcRequest("2.0", "tasks/send", params, "req-1");
        var task = new A2ATask("task-1", null, TaskState.completed, List.of(), List.of(), null);
        when(taskHandler.handleTaskSend(AGENT_ID, params)).thenReturn(task);

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNull(body.error());
        assertEquals(task, body.result());
    }

    @Test
    void handleJsonRpc_tasksSend_nullParams_returnsError() throws Exception {
        endpoint = createEndpoint(true, false);
        var request = new JsonRpcRequest("2.0", "tasks/send", null, "req-1");

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNotNull(body.error());
        assertEquals(A2AModels.ERROR_INVALID_PARAMS, body.error().code());
    }

    @Test
    void handleJsonRpc_tasksSend_handlerThrows_returnsInternalError() throws Exception {
        endpoint = createEndpoint(true, false);
        var params = Map.<String, Object>of("message", "Hello");
        var request = new JsonRpcRequest("2.0", "tasks/send", params, "req-1");
        when(taskHandler.handleTaskSend(AGENT_ID, params)).thenThrow(new RuntimeException("Agent error"));

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNotNull(body.error());
        assertEquals(A2AModels.ERROR_INTERNAL, body.error().code());
    }

    // ==================== handleJsonRpc — tasks/get ====================

    @Test
    void handleJsonRpc_tasksGet_success() {
        endpoint = createEndpoint(true, false);
        var params = Map.<String, Object>of("id", "task-1");
        var request = new JsonRpcRequest("2.0", "tasks/get", params, "req-2");
        var task = new A2ATask("task-1", null, TaskState.completed, List.of(), List.of(), null);
        when(taskHandler.handleTaskGet("task-1")).thenReturn(task);

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNull(body.error());
        assertEquals(task, body.result());
    }

    @Test
    void handleJsonRpc_tasksGet_nullParams_returnsError() {
        endpoint = createEndpoint(true, false);
        var request = new JsonRpcRequest("2.0", "tasks/get", null, "req-2");

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNotNull(body.error());
        assertEquals(A2AModels.ERROR_INVALID_PARAMS, body.error().code());
    }

    @Test
    void handleJsonRpc_tasksGet_missingId_returnsError() {
        endpoint = createEndpoint(true, false);
        var params = Map.<String, Object>of("other", "value");
        var request = new JsonRpcRequest("2.0", "tasks/get", params, "req-2");

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNotNull(body.error());
        assertEquals(A2AModels.ERROR_INVALID_PARAMS, body.error().code());
    }

    @Test
    void handleJsonRpc_tasksGet_notFound_returnsError() {
        endpoint = createEndpoint(true, false);
        var params = Map.<String, Object>of("id", "nonexistent");
        var request = new JsonRpcRequest("2.0", "tasks/get", params, "req-2");
        when(taskHandler.handleTaskGet("nonexistent")).thenReturn(null);

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNotNull(body.error());
        assertEquals(A2AModels.ERROR_TASK_NOT_FOUND, body.error().code());
    }

    // ==================== handleJsonRpc — tasks/cancel ====================

    @Test
    void handleJsonRpc_tasksCancel_success() {
        endpoint = createEndpoint(true, false);
        var params = Map.<String, Object>of("id", "task-1");
        var request = new JsonRpcRequest("2.0", "tasks/cancel", params, "req-3");
        when(taskHandler.handleTaskCancel("task-1")).thenReturn(true);

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNull(body.error());
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) body.result();
        assertEquals("task-1", result.get("id"));
        assertEquals("canceled", result.get("status"));
    }

    @Test
    void handleJsonRpc_tasksCancel_nullParams_returnsError() {
        endpoint = createEndpoint(true, false);
        var request = new JsonRpcRequest("2.0", "tasks/cancel", null, "req-3");

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNotNull(body.error());
        assertEquals(A2AModels.ERROR_INVALID_PARAMS, body.error().code());
    }

    @Test
    void handleJsonRpc_tasksCancel_missingId_returnsError() {
        endpoint = createEndpoint(true, false);
        var params = Map.<String, Object>of("other", "value");
        var request = new JsonRpcRequest("2.0", "tasks/cancel", params, "req-3");

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNotNull(body.error());
        assertEquals(A2AModels.ERROR_INVALID_PARAMS, body.error().code());
    }

    @Test
    void handleJsonRpc_tasksCancel_notCancelable_returnsError() {
        endpoint = createEndpoint(true, false);
        var params = Map.<String, Object>of("id", "task-1");
        var request = new JsonRpcRequest("2.0", "tasks/cancel", params, "req-3");
        when(taskHandler.handleTaskCancel("task-1")).thenReturn(false);

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        assertEquals(200, response.getStatus());
        var body = (JsonRpcResponse) response.getEntity();
        assertNotNull(body.error());
        assertEquals(A2AModels.ERROR_TASK_NOT_CANCELABLE, body.error().code());
    }

    // ==================== JSON-RPC response structure ====================

    @Test
    void handleJsonRpc_responseContainsJsonRpcVersion() {
        endpoint = createEndpoint(true, false);
        var request = new JsonRpcRequest("2.0", "tasks/get", Map.of("id", "t1"), "req-id");
        var task = new A2ATask("t1", null, TaskState.completed, List.of(), List.of(), null);
        when(taskHandler.handleTaskGet("t1")).thenReturn(task);

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        var body = (JsonRpcResponse) response.getEntity();
        assertEquals("2.0", body.jsonrpc());
        assertEquals("req-id", body.id());
    }

    @Test
    void handleJsonRpc_errorResponsePreservesId() {
        endpoint = createEndpoint(true, false);
        var request = new JsonRpcRequest("2.0", "unknown_method", Map.of(), 42);

        Response response = endpoint.handleJsonRpc(AGENT_ID, request);

        var body = (JsonRpcResponse) response.getEntity();
        assertEquals("2.0", body.jsonrpc());
        assertEquals(42, body.id());
        assertNotNull(body.error());
        assertNull(body.result());
    }
}
