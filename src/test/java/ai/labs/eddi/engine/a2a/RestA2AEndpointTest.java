/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.engine.a2a.A2AModels.A2ABusyException;
import ai.labs.eddi.engine.a2a.A2AModels.A2ATask;
import ai.labs.eddi.engine.a2a.A2AModels.AgentAuthentication;
import ai.labs.eddi.engine.a2a.A2AModels.AgentCapabilities;
import ai.labs.eddi.engine.a2a.A2AModels.AgentCard;
import ai.labs.eddi.engine.a2a.A2AModels.AgentSkill;
import ai.labs.eddi.engine.a2a.A2AModels.Artifact;
import ai.labs.eddi.engine.a2a.A2AModels.Dialect;
import ai.labs.eddi.engine.a2a.A2AModels.InvalidA2ARequestException;
import ai.labs.eddi.engine.a2a.A2AModels.JsonRpcRequest;
import ai.labs.eddi.engine.a2a.A2AModels.JsonRpcResponse;
import ai.labs.eddi.engine.a2a.A2AModels.Part;
import ai.labs.eddi.engine.a2a.A2AModels.TaskState;
import ai.labs.eddi.engine.a2a.A2AModels.TaskStatus;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.CancelOutcomeAndTask;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.CancelResult;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.ChunkEvent;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.FinalEvent;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.StreamEvent;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.TaskEvent;
import ai.labs.eddi.engine.a2a.A2AWireFormat.SendRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

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
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        agentCardService = mock(AgentCardService.class);
        taskHandler = mock(A2ATaskHandler.class);
        capabilityRegistryService = mock(CapabilityRegistryService.class);
        meterRegistry = new SimpleMeterRegistry();
    }

    private RestA2AEndpoint createEndpoint(boolean a2aEnabled, boolean capabilitiesPublic) {
        return new RestA2AEndpoint(agentCardService, taskHandler, capabilityRegistryService,
                a2aEnabled, capabilitiesPublic, new ObjectMapper(), meterRegistry);
    }

    /** A card in the old eight-field shape these tests were written against. */
    private static AgentCard card(String name, String description, String url, String provider, String version,
                                  AgentCapabilities capabilities, List<AgentSkill> skills, AgentAuthentication authentication) {
        return new AgentCard(name, description, url, "0.3.0", "JSONRPC", null, null, version, capabilities, null, null,
                null, null, skills, authentication);
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
        when(agentCardService.getDefaultAgentCard()).thenReturn(null);

        Response response = endpoint.getDefaultAgentCard();

        assertEquals(404, response.getStatus());
        // Should contain error message
        assertNotNull(response.getEntity());
    }

    @Test
    void getDefaultAgentCard_success_returnsFirstCard() {
        endpoint = createEndpoint(true, false);
        var card1 = card("Agent1", "First agent", "http://localhost/a2a/agents/1",
                "EDDI", "1.0", null, null, null);
        when(agentCardService.getDefaultAgentCard()).thenReturn(card1);

        Response response = endpoint.getDefaultAgentCard();

        assertEquals(200, response.getStatus());
        assertEquals(card1, response.getEntity());
    }

    /**
     * The endpoint is anonymous, so what it costs per request is part of its
     * contract. It used to ask for the whole roster and return element zero;
     * AgentCardService.getDefaultAgentCard stops at the first match instead.
     */
    @Test
    void getDefaultAgentCard_doesNotEnumerateTheRoster() {
        endpoint = createEndpoint(true, false);
        when(agentCardService.getDefaultAgentCard()).thenReturn(
                card("Agent1", "First agent", "http://localhost/a2a/agents/1",
                        "EDDI", "1.0", null, null, null));

        endpoint.getDefaultAgentCard();

        verify(agentCardService, never()).listA2AAgents();
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
        var card = card("TestAgent", "A test agent",
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
        var card = card("Agent", "desc", "http://localhost", "EDDI", "1.0", null, null, null);
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

    private static A2ATask completedTask(String id) {
        return new A2ATask(id, "ctx-1", new TaskStatus(TaskState.completed, null, Instant.parse("2026-10-03T10:00:00Z")),
                null, List.of(new Artifact("response", "response", List.of(Part.textPart("hi")))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Response response) {
        return (Map<String, Object>) ((JsonRpcResponse) response.getEntity()).result();
    }

    private static int errorCode(Response response) {
        var body = (JsonRpcResponse) response.getEntity();
        assertNotNull(body.error(), "expected a JSON-RPC error");
        return body.error().code();
    }

    @Test
    void handleJsonRpc_disabled_returnsMethodNotFound() {
        endpoint = createEndpoint(false, false);
        var request = new JsonRpcRequest("2.0", "SendMessage", Map.of(), "req-1");

        Response response = endpoint.handleJsonRpc(AGENT_ID, null, request);

        assertEquals(200, response.getStatus()); // JSON-RPC errors are 200 OK
        assertEquals(A2AModels.ERROR_METHOD_NOT_FOUND, errorCode(response));
    }

    @Test
    void handleJsonRpc_nullRequest_isInvalidRequest_evenWhenDisabled() {
        endpoint = createEndpoint(false, false);

        Response response = endpoint.handleJsonRpc(AGENT_ID, null, null);

        assertEquals(A2AModels.ERROR_INVALID_REQUEST, errorCode(response));
    }

    @Test
    void handleJsonRpc_nullMethod_returnsInvalidRequest() {
        endpoint = createEndpoint(true, false);
        var request = new JsonRpcRequest("2.0", null, Map.of(), "req-1");

        assertEquals(A2AModels.ERROR_INVALID_REQUEST, errorCode(endpoint.handleJsonRpc(AGENT_ID, null, request)));
    }

    @Test
    void handleJsonRpc_unknownMethod_returnsError() {
        endpoint = createEndpoint(true, false);
        var request = new JsonRpcRequest("2.0", "tasks/unknown", Map.of(), "req-1");

        Response response = endpoint.handleJsonRpc(AGENT_ID, null, request);

        assertEquals(A2AModels.ERROR_METHOD_NOT_FOUND, errorCode(response));
        assertTrue(((JsonRpcResponse) response.getEntity()).error().message().contains("Unknown method"));
    }

    @Test
    void handleJsonRpc_unsupportedVersionHeader_isVersionNotSupported() {
        endpoint = createEndpoint(true, false);
        var request = new JsonRpcRequest("2.0", "SendMessage", Map.of(), "req-1");

        assertEquals(A2AModels.ERROR_VERSION_NOT_SUPPORTED, errorCode(endpoint.handleJsonRpc(AGENT_ID, "2.0", request)));
        verifyNoInteractions(taskHandler);
    }

    @Test
    void isSupportedVersion_acceptsAbsentAnd0xAnd1x() {
        assertTrue(RestA2AEndpoint.isSupportedVersion(null));
        assertTrue(RestA2AEndpoint.isSupportedVersion(" "));
        assertTrue(RestA2AEndpoint.isSupportedVersion("0.3"));
        assertTrue(RestA2AEndpoint.isSupportedVersion("1.0"));
        assertFalse(RestA2AEndpoint.isSupportedVersion("2.0"));
    }

    @Test
    void handleJsonRpc_recognisedButUnimplementedMethods_getTheirSpecErrors() {
        endpoint = createEndpoint(true, false);

        assertEquals(A2AModels.ERROR_UNSUPPORTED_OPERATION,
                errorCode(endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "ListTasks", Map.of(), 1))));
        assertEquals(A2AModels.ERROR_PUSH_NOTIFICATION_NOT_SUPPORTED, errorCode(endpoint.handleJsonRpc(AGENT_ID, null,
                new JsonRpcRequest("2.0", "tasks/pushNotificationConfig/set", Map.of(), 2))));
        assertEquals(A2AModels.ERROR_EXTENDED_CARD_NOT_CONFIGURED,
                errorCode(endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "GetExtendedAgentCard", Map.of(), 3))));
    }

    // ==================== send, per dialect ====================

    @Test
    void sendMessage_v10_wrapsTheTask_andUses10Names() throws Exception {
        endpoint = createEndpoint(true, false);
        Map<String, Object> params = Map.of("message", Map.of("messageId", "m1", "role", "ROLE_USER", "parts", List.of(Map.of("text", "Hello"))));
        when(taskHandler.send(eq(AGENT_ID), any())).thenReturn(completedTask("task-1"));

        Response response = endpoint.handleJsonRpc(AGENT_ID, "1.0", new JsonRpcRequest("2.0", "SendMessage", params, "req-1"));

        @SuppressWarnings("unchecked")
        var task = (Map<String, Object>) result(response).get("task");
        assertEquals("task-1", task.get("id"));
        assertEquals("TASK_STATE_COMPLETED", ((Map<?, ?>) task.get("status")).get("state"));
        assertFalse(task.containsKey("kind"), "a 1.0 client parses strictly; a kind field would fail it");
        var captor = ArgumentCaptor.forClass(SendRequest.class);
        verify(taskHandler).send(eq(AGENT_ID), captor.capture());
        assertEquals("Hello", captor.getValue().text());
        assertEquals(Dialect.V1_0, captor.getValue().dialect());
    }

    @Test
    void messageSend_v03_returnsTheTaskWithKind() throws Exception {
        endpoint = createEndpoint(true, false);
        Map<String, Object> params = Map.of("message",
                Map.of("kind", "message", "messageId", "m1", "role", "user", "parts", List.of(Map.of("kind", "text", "text", "Hello"))));
        when(taskHandler.send(eq(AGENT_ID), any())).thenReturn(completedTask("task-1"));

        var task = result(endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "message/send", params, "req-1")));

        assertEquals("task", task.get("kind"));
        assertEquals("completed", ((Map<?, ?>) task.get("status")).get("state"));
    }

    @Test
    void tasksSend_legacy_isStillAccepted() throws Exception {
        endpoint = createEndpoint(true, false);
        Map<String, Object> params = Map.of("id", "legacy-1", "message",
                Map.of("role", "user", "parts", List.of(Map.of("type", "text", "text", "Hello"))));
        when(taskHandler.send(eq(AGENT_ID), any())).thenReturn(completedTask("legacy-1"));

        var task = result(endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "tasks/send", params, "req-1")));

        assertEquals("legacy-1", task.get("id"));
        var captor = ArgumentCaptor.forClass(SendRequest.class);
        verify(taskHandler).send(eq(AGENT_ID), captor.capture());
        assertEquals("legacy-1", captor.getValue().legacyTaskId());
        assertEquals(Dialect.LEGACY, captor.getValue().dialect());
    }

    @Test
    void send_nullParams_returnsInvalidParams() {
        endpoint = createEndpoint(true, false);

        assertEquals(A2AModels.ERROR_INVALID_PARAMS,
                errorCode(endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "tasks/send", null, "req-1"))));
    }

    @Test
    void send_handlerThrows_returnsInternalError_withoutDetail() throws Exception {
        endpoint = createEndpoint(true, false);
        Map<String, Object> params = Map.of("message", Map.of("parts", List.of(Map.of("text", "Hello"))));
        when(taskHandler.send(eq(AGENT_ID), any()))
                .thenThrow(new IllegalStateException("mongodb://admin:s3cr3t@internal-db.corp:27017 connection refused"));

        Response response = endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "SendMessage", params, "req-1"));

        var body = (JsonRpcResponse) response.getEntity();
        assertEquals(A2AModels.ERROR_INTERNAL, body.error().code());
        assertEquals(RestA2AEndpoint.INTERNAL_ERROR_MESSAGE, body.error().message());
        assertFalse(body.error().message().contains("s3cr3t"));
    }

    @Test
    void send_invalidRequest_returnsAuthoredMessageAndCode() throws Exception {
        endpoint = createEndpoint(true, false);
        Map<String, Object> params = Map.of("message", Map.of("parts", List.of(Map.of("text", "Hello"))));
        when(taskHandler.send(eq(AGENT_ID), any()))
                .thenThrow(new InvalidA2ARequestException(A2AModels.ERROR_TASK_NOT_FOUND, "Task not found"));

        Response response = endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "SendMessage", params, "req-1"));

        assertEquals(A2AModels.ERROR_TASK_NOT_FOUND, errorCode(response));
        assertEquals("Task not found", ((JsonRpcResponse) response.getEntity()).error().message());
    }

    @Test
    void send_whenEverySlotIsTaken_is503WithRetryAfter_andCounted() throws Exception {
        endpoint = createEndpoint(true, false);
        Map<String, Object> params = Map.of("message", Map.of("parts", List.of(Map.of("text", "Hello"))));
        when(taskHandler.send(eq(AGENT_ID), any())).thenThrow(new A2ABusyException("busy"));

        Response response = endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "SendMessage", params, "req-9"));

        assertEquals(503, response.getStatus());
        assertEquals("1", response.getHeaderString("Retry-After"));
        var body = (JsonRpcResponse) response.getEntity();
        assertEquals("req-9", body.id());
        assertEquals(A2AModels.ERROR_INTERNAL, body.error().code());
        assertEquals(RestA2AEndpoint.BUSY_MESSAGE, body.error().message());
        assertEquals(1.0, meterRegistry.counter("eddi.a2a.requests", "method", "send", "dialect", "1.0", "outcome", "busy").count());
    }

    @Test
    void send_success_isCountedAsOk() throws Exception {
        endpoint = createEndpoint(true, false);
        Map<String, Object> params = Map.of("message", Map.of("parts", List.of(Map.of("kind", "text", "text", "Hello"))));
        when(taskHandler.send(eq(AGENT_ID), any())).thenReturn(completedTask("t"));

        endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "message/send", params, "req-1"));

        assertEquals(1.0, meterRegistry.counter("eddi.a2a.requests", "method", "send", "dialect", "0.3", "outcome", "ok").count());
    }

    // ==================== get / cancel ====================

    @Test
    void getTask_success_rendersInTheCallersDialect() {
        endpoint = createEndpoint(true, false);
        when(taskHandler.get("task-1", null)).thenReturn(completedTask("task-1"));

        var v10 = result(endpoint.handleJsonRpc(AGENT_ID, "1.0", new JsonRpcRequest("2.0", "GetTask", Map.of("id", "task-1"), 1)));
        var v03 = result(endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "tasks/get", Map.of("id", "task-1"), 2)));

        assertEquals("TASK_STATE_COMPLETED", ((Map<?, ?>) v10.get("status")).get("state"));
        assertEquals("completed", ((Map<?, ?>) v03.get("status")).get("state"));
    }

    @Test
    void getTask_passesHistoryLength() {
        endpoint = createEndpoint(true, false);
        when(taskHandler.get("task-1", 0)).thenReturn(completedTask("task-1"));

        endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "GetTask", Map.of("id", "task-1", "historyLength", 0), 1));

        verify(taskHandler).get("task-1", 0);
    }

    @Test
    void getTask_missingId_returnsInvalidParams() {
        endpoint = createEndpoint(true, false);

        assertEquals(A2AModels.ERROR_INVALID_PARAMS,
                errorCode(endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "tasks/get", Map.of("other", "x"), 1))));
        assertEquals(A2AModels.ERROR_INVALID_PARAMS,
                errorCode(endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "tasks/get", null, 1))));
    }

    @Test
    void getTask_notFound_doesNotEchoCallerSuppliedTaskId() {
        endpoint = createEndpoint(true, false);
        String probedTaskId = "task-of-another-peer-42";
        when(taskHandler.get(probedTaskId, null)).thenReturn(null);

        Response response = endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "tasks/get", Map.of("id", probedTaskId), 1));

        assertEquals(A2AModels.ERROR_TASK_NOT_FOUND, errorCode(response));
        assertFalse(((JsonRpcResponse) response.getEntity()).error().message().contains(probedTaskId));
    }

    @Test
    void cancelTask_success_returnsTheCanceledTask() {
        endpoint = createEndpoint(true, false);
        var canceled = new A2ATask("task-1", "ctx", new TaskStatus(TaskState.canceled, null, Instant.now()), null, null);
        when(taskHandler.cancel("task-1")).thenReturn(new CancelOutcomeAndTask(CancelResult.CANCELED, canceled));

        var task = result(endpoint.handleJsonRpc(AGENT_ID, "1.0", new JsonRpcRequest("2.0", "CancelTask", Map.of("id", "task-1"), 1)));

        assertEquals("TASK_STATE_CANCELED", ((Map<?, ?>) task.get("status")).get("state"));
    }

    @Test
    void cancelTask_notFoundAndNotCancelable_haveDistinctCodes() {
        endpoint = createEndpoint(true, false);
        when(taskHandler.cancel("gone")).thenReturn(new CancelOutcomeAndTask(CancelResult.NOT_FOUND, null));
        when(taskHandler.cancel("done")).thenReturn(new CancelOutcomeAndTask(CancelResult.NOT_CANCELABLE, completedTask("done")));

        assertEquals(A2AModels.ERROR_TASK_NOT_FOUND,
                errorCode(endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "tasks/cancel", Map.of("id", "gone"), 1))));
        assertEquals(A2AModels.ERROR_TASK_NOT_CANCELABLE,
                errorCode(endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "tasks/cancel", Map.of("id", "done"), 2))));
    }

    // ==================== streaming ====================

    @Test
    @SuppressWarnings("unchecked")
    void stream_v10_writesJsonRpcEventsUntilTheFinalStatus() throws Exception {
        endpoint = createEndpoint(true, false);
        when(taskHandler.taskTimeoutSeconds()).thenReturn(5);
        var working = new A2ATask("t1", "c1", new TaskStatus(TaskState.working, null, Instant.now()), null, null);
        doAnswer(invocation -> {
            Consumer<StreamEvent> sink = invocation.getArgument(2);
            sink.accept(new TaskEvent(working));
            sink.accept(new ChunkEvent(working, "Hel", false));
            sink.accept(new ChunkEvent(working, "lo", true));
            sink.accept(new FinalEvent(completedTask("t1")));
            return null;
        }).when(taskHandler).stream(eq(AGENT_ID), any(), any());
        Map<String, Object> params = Map.of("message", Map.of("parts", List.of(Map.of("text", "Hello"))));

        Response response = endpoint.handleJsonRpc(AGENT_ID, "1.0", new JsonRpcRequest("2.0", "SendStreamingMessage", params, "s-1"));

        assertEquals(MediaType.SERVER_SENT_EVENTS_TYPE, response.getMediaType());
        var out = new ByteArrayOutputStream();
        ((StreamingOutput) response.getEntity()).write(out);
        var mapper = new ObjectMapper();
        List<Map<String, Object>> results = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("data: ")) {
                Map<String, Object> event = mapper.readValue(line.substring(6), Map.class);
                assertEquals("s-1", event.get("id"));
                results.add((Map<String, Object>) event.get("result"));
            }
        }
        assertEquals(List.of("task", "artifactUpdate", "artifactUpdate", "artifactUpdate", "statusUpdate"),
                results.stream().map(r -> r.keySet().iterator().next()).toList());
        var finalArtifact = (Map<String, Object>) results.get(3).get("artifactUpdate");
        assertEquals(false, finalArtifact.get("append"), "the settled answer replaces the streamed preview");
        assertEquals(true, finalArtifact.get("lastChunk"));
        var status = (Map<String, Object>) ((Map<String, Object>) results.get(4).get("statusUpdate")).get("status");
        assertEquals("TASK_STATE_COMPLETED", status.get("state"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stream_endsOnTheLastKnownState_whenTheTurnOutlivesIt() throws Exception {
        endpoint = createEndpoint(true, false);
        var queue = new LinkedBlockingQueue<StreamEvent>();
        var working = new A2ATask("t1", "c1", new TaskStatus(TaskState.working, null, Instant.now()), null, null);
        queue.add(new TaskEvent(working));
        var out = new ByteArrayOutputStream();

        endpoint.drain(out, queue, "s-2", Dialect.V0_3, 200);

        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n\n");
        assertEquals(2, lines.length);
        Map<String, Object> last = new ObjectMapper().readValue(lines[1].substring(6), Map.class);
        var result = (Map<String, Object>) last.get("result");
        assertEquals("status-update", result.get("kind"));
        assertEquals(true, result.get("final"));
        assertEquals("working", ((Map<String, Object>) result.get("status")).get("state"));
    }

    @Test
    void stream_refusalBeforeTheStream_isAnOrdinaryJsonRpcError() throws Exception {
        endpoint = createEndpoint(true, false);
        doThrow(new A2ABusyException("busy")).when(taskHandler).stream(eq(AGENT_ID), any(), any());
        Map<String, Object> params = Map.of("message", Map.of("parts", List.of(Map.of("kind", "text", "text", "Hello"))));

        Response response = endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "message/stream", params, "s-3"));

        assertEquals(503, response.getStatus());
    }

    // ==================== JSON-RPC response structure ====================

    @Test
    void handleJsonRpc_responseContainsJsonRpcVersionAndId() {
        endpoint = createEndpoint(true, false);
        when(taskHandler.get("t1", null)).thenReturn(completedTask("t1"));

        var body = (JsonRpcResponse) endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "tasks/get", Map.of("id", "t1"), "req-id"))
                .getEntity();

        assertEquals("2.0", body.jsonrpc());
        assertEquals("req-id", body.id());
    }

    @Test
    void handleJsonRpc_errorResponsePreservesId() {
        endpoint = createEndpoint(true, false);

        var body = (JsonRpcResponse) endpoint.handleJsonRpc(AGENT_ID, null, new JsonRpcRequest("2.0", "unknown_method", Map.of(), 42))
                .getEntity();

        assertEquals(42, body.id());
        assertNotNull(body.error());
        assertNull(body.result());
    }

    // ==================== card paths ====================

    @Test
    void wellKnownCardPaths_serveTheSameCards() {
        endpoint = createEndpoint(true, false);
        var defaultCard = card("A", "d", "u", "EDDI", "1", null, null, null);
        var agentCard = card("B", "d", "u", "EDDI", "1", null, null, null);
        when(agentCardService.getDefaultAgentCard()).thenReturn(defaultCard);
        when(agentCardService.getAgentCard(AGENT_ID)).thenReturn(agentCard);

        assertEquals(defaultCard, endpoint.getDefaultAgentCardLegacyPath().getEntity());
        assertEquals(agentCard, endpoint.getAgentCardWellKnown(AGENT_ID).getEntity());
    }
}
