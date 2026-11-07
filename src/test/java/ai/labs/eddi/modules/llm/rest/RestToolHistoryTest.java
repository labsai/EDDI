/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.rest;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.modules.llm.model.ToolHistory;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestToolHistory} — all REST endpoints, error handling,
 * and internal logic.
 */
class RestToolHistoryTest {

    private RestToolHistory restToolHistory;
    private IConversationMemoryStore memoryStore;
    private ToolCacheService cacheService;
    private ToolRateLimiter rateLimiter;
    private ToolCostTracker costTracker;
    private ConversationAccessGuard conversationAccessGuard;

    @BeforeEach
    void setUp() {
        memoryStore = mock(IConversationMemoryStore.class);
        cacheService = mock(ToolCacheService.class);
        rateLimiter = mock(ToolRateLimiter.class);
        costTracker = mock(ToolCostTracker.class);
        conversationAccessGuard = mock(ConversationAccessGuard.class);

        restToolHistory = new RestToolHistory(cacheService, rateLimiter, costTracker, memoryStore, conversationAccessGuard);
    }

    // ==================== Access control (finding A4) ====================

    @Nested
    @DisplayName("A4 — tool control plane access control")
    class AccessControl {

        private List<String> rolesOn(Class<?> type) {
            RolesAllowed roles = type.getAnnotation(RolesAllowed.class);
            assertNotNull(roles, type.getSimpleName() + " must declare @RolesAllowed");
            return Arrays.asList(roles.value());
        }

        private List<String> rolesOn(String methodName, Class<?>... params) throws Exception {
            RolesAllowed roles = RestToolHistory.class.getMethod(methodName, params).getAnnotation(RolesAllowed.class);
            assertNotNull(roles, methodName + " must declare @RolesAllowed");
            return Arrays.asList(roles.value());
        }

        @Test
        @DisplayName("the control plane is admin-only, so rate-limit/cost resets are unreachable for non-admins")
        void controlPlaneIsAdminOnly() {
            assertEquals(List.of("eddi-admin"), rolesOn(RestToolHistory.class));
        }

        @Test
        @DisplayName("the reset endpoints inherit the admin-only class role (no widening override)")
        void resetEndpointsStayAdminOnly() throws Exception {
            assertNull(RestToolHistory.class.getMethod("resetRateLimit", String.class).getAnnotation(RolesAllowed.class),
                    "resetRateLimit must not override the admin-only class role");
            assertNull(RestToolHistory.class.getMethod("resetCosts").getAnnotation(RolesAllowed.class),
                    "resetCosts must not override the admin-only class role");
            assertNull(RestToolHistory.class.getMethod("clearCache").getAnnotation(RolesAllowed.class),
                    "clearCache must not override the admin-only class role");
        }

        @Test
        @DisplayName("tool history is readable by ordinary users — gated on conversation ownership, not role alone")
        void historyIsOwnerScoped() throws Exception {
            assertTrue(rolesOn("getToolHistory", String.class).contains("eddi-user"));
        }

        @Test
        @DisplayName("history of a foreign conversation is denied before any trace is read")
        void foreignHistoryIsDenied() throws Exception {
            doThrow(new ForbiddenException("Access denied: you do not own this conversation"))
                    .when(conversationAccessGuard).requireConversationOwner("conversation-of-user-a");

            assertThrows(ForbiddenException.class,
                    () -> restToolHistory.getToolHistory("conversation-of-user-a"));

            // Tool arguments and results must never be loaded for a foreign caller.
            verify(memoryStore, never()).loadConversationMemorySnapshot(anyString());
        }

        @Test
        @DisplayName("history of an owned conversation still runs the ownership check")
        void ownedHistoryIsChecked() throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationSteps(List.of());
            when(memoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(snapshot);

            assertEquals(200, restToolHistory.getToolHistory("conv-1").getStatus());

            verify(conversationAccessGuard).requireConversationOwner("conv-1");
        }
    }

    // ==================== Error bodies (finding A12) ====================

    @Nested
    @DisplayName("A12 — 500 bodies carry no internal detail")
    class OpaqueErrorBodies {

        @Test
        @DisplayName("a store failure returns a fixed message plus a correlation id, never the driver message")
        void storeFailureIsOpaque() throws Exception {
            when(memoryStore.loadConversationMemorySnapshot("conv-1"))
                    .thenThrow(new RuntimeException("Timed out connecting to replica set [mongo-01.internal:27017] db=eddi coll=conversations"));

            Response response = restToolHistory.getToolHistory("conv-1");

            assertEquals(500, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("Internal server error", body.get("error"));
            assertNotNull(body.get("correlationId"));
            assertFalse(body.toString().contains("mongo-01.internal"), "hostname leaked into the 500 body");
            assertFalse(body.toString().contains("conversations"), "collection name leaked into the 500 body");
        }

        @Test
        @DisplayName("a cost-tracker failure is opaque too")
        void costFailureIsOpaque() {
            when(costTracker.getCostSummary()).thenThrow(new RuntimeException("jdbc:postgresql://db-primary.internal:5432/eddi refused"));

            Response response = restToolHistory.getCosts();

            assertEquals(500, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("Internal server error", body.get("error"));
            assertFalse(body.toString().contains("db-primary.internal"), "hostname leaked into the 500 body");
        }
    }

    // ==================== getToolHistory ====================

    @Nested
    @DisplayName("GET /history/{conversationId}")
    class GetToolHistory {

        @Test
        @DisplayName("M-E5: an unknown conversation is a 404, not a 500 from a null dereference")
        void unknownConversationIsNotFound() {
            // The store answers null (not NotFound) for an unknown id.
            Response response = restToolHistory.getToolHistory("conv-missing");

            assertEquals(404, response.getStatus());
        }

        @Test
        @DisplayName("should return 200 with empty trace for conversation without tool calls")
        void emptyConversation() throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationSteps(List.of());
            when(memoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(snapshot);

            Response response = restToolHistory.getToolHistory("conv-1");

            assertEquals(200, response.getStatus());
            assertNotNull(response.getEntity());
        }

        @Test
        @DisplayName("a trace without call ids (older traces) still pairs a result with its call by tool name")
        void legacyTraceWithoutCallIds() throws Exception {
            ToolHistory history = historyOf(
                    Map.of("type", "tool_call", "tool", "calculator", "arguments", "{\"expression\":\"2+2\"}"),
                    Map.of("type", "tool_result", "tool", "calculator", "result", "4"));

            assertEquals(List.of(new ToolHistory.Call("calculator", null, "{\"expression\":\"2+2\"}", "4", true, null)),
                    history.toolCalls());
            assertFalse(history.hasErrors());
        }

        @Test
        @DisplayName("should return 404 when conversation not found")
        void conversationNotFound() throws Exception {
            when(memoryStore.loadConversationMemorySnapshot("nonexistent"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("Not found"));

            Response response = restToolHistory.getToolHistory("nonexistent");

            assertEquals(404, response.getStatus());
        }

        @Test
        @DisplayName("should return 500 on unexpected error")
        void unexpectedError() throws Exception {
            when(memoryStore.loadConversationMemorySnapshot("conv-1"))
                    .thenThrow(new RuntimeException("DB connection failed"));

            Response response = restToolHistory.getToolHistory("conv-1");

            assertEquals(500, response.getStatus());
        }

        @Test
        @DisplayName("should handle non-list trace data gracefully")
        void nonListTraceData() throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            var step = new ConversationStepSnapshot();
            var workflowRun = new WorkflowRunSnapshot();
            var result = new ResultSnapshot();
            result.setKey("langchain:trace:step1");
            result.setResult("not-a-list"); // Not a List — should be skipped
            workflowRun.setLifecycleTasks(List.of(result));
            step.setWorkflows(List.of(workflowRun));
            snapshot.setConversationSteps(List.of(step));
            when(memoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(snapshot);

            Response response = restToolHistory.getToolHistory("conv-1");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should skip non-trace keys")
        void nonTraceKeysSkipped() throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            var step = new ConversationStepSnapshot();
            var workflowRun = new WorkflowRunSnapshot();
            var result = new ResultSnapshot();
            result.setKey("other:key");
            result.setResult(List.of());
            workflowRun.setLifecycleTasks(List.of(result));
            step.setWorkflows(List.of(workflowRun));
            snapshot.setConversationSteps(List.of(step));
            when(memoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(snapshot);

            Response response = restToolHistory.getToolHistory("conv-1");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should handle null key gracefully")
        void nullKeySkipped() throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            var step = new ConversationStepSnapshot();
            var workflowRun = new WorkflowRunSnapshot();
            var result = new ResultSnapshot();
            result.setKey(null);
            workflowRun.setLifecycleTasks(List.of(result));
            step.setWorkflows(List.of(workflowRun));
            snapshot.setConversationSteps(List.of(step));
            when(memoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(snapshot);

            Response response = restToolHistory.getToolHistory("conv-1");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should handle list with non-Map items in trace")
        void nonMapItemsInTraceList() throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            var step = new ConversationStepSnapshot();
            var workflowRun = new WorkflowRunSnapshot();
            var result = new ResultSnapshot();
            result.setKey("langchain:trace:step1");
            // List with a String instead of Map — should log warning
            List<Object> traceItems = new ArrayList<>();
            traceItems.add("not-a-map");
            result.setResult(traceItems);
            workflowRun.setLifecycleTasks(List.of(result));
            step.setWorkflows(List.of(workflowRun));
            snapshot.setConversationSteps(List.of(step));
            when(memoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(snapshot);

            Response response = restToolHistory.getToolHistory("conv-1");

            assertEquals(200, response.getStatus());
        }
    }

    // ==================== Outcomes (finding LLM-5) ====================

    @Nested
    @DisplayName("LLM-5 — the history reports what the trace records")
    class Outcomes {

        @Test
        @DisplayName("failed results, tool errors and calls refused before they ran are reported as failed")
        void failuresAreReportedAsFailed() throws Exception {
            Map<String, Object> schemaRefusal = new HashMap<>();
            schemaRefusal.put("type", "tool_error");
            schemaRefusal.put("tool", "lookup");
            schemaRefusal.put("error", "argument_schema");
            schemaRefusal.put("violations", List.of("$.id: required"));

            ToolHistory history = historyOf(
                    Map.of("type", "tool_call", "tool", "http", "callId", "c1", "arguments", "{}"),
                    Map.of("type", "tool_result", "tool", "http", "callId", "c1", "result", "{\"error\":\"boom\",\"status\":500}"),
                    Map.of("type", "tool_call", "tool", "search", "callId", "c2", "arguments", "{\"q\":\"x\"}"),
                    Map.of("type", "tool_error", "tool", "search", "callId", "c2", "error", "Budget exceeded for conversation conv-1"),
                    schemaRefusal,
                    Map.of("type", "tool_call", "tool", "calculator", "callId", "c3", "arguments", "{\"expression\":\"1+1\"}"),
                    Map.of("type", "tool_result", "tool", "calculator", "callId", "c3", "result", "2"));

            assertEquals(List.of(
                    new ToolHistory.Call("http", "c1", "{}", "{\"error\":\"boom\",\"status\":500}", false, "failed_retryable"),
                    new ToolHistory.Call("search", "c2", "{\"q\":\"x\"}", null, false, "Budget exceeded for conversation conv-1"),
                    new ToolHistory.Call("lookup", null, null, null, false, "argument_schema"),
                    new ToolHistory.Call("calculator", "c3", "{\"expression\":\"1+1\"}", "2", true, null)),
                    history.toolCalls());
            assertTrue(history.hasErrors());
        }

        @Test
        @DisplayName("an HTTP 4xx result is a terminal failure and a NOT_EXECUTED envelope is not a success")
        void resultOutcomesComeFromTheClassifier() throws Exception {
            ToolHistory history = historyOf(
                    Map.of("type", "tool_call", "tool", "http", "callId", "c1", "arguments", "{}"),
                    Map.of("type", "tool_result", "tool", "http", "callId", "c1", "result", "{\"httpCode\":404,\"body\":\"gone\"}"),
                    Map.of("type", "tool_call", "tool", "http", "callId", "c2", "arguments", "{}"),
                    Map.of("type", "tool_result", "tool", "http", "callId", "c2", "result", "{\"status\":\"NOT_EXECUTED\"}"));

            assertEquals(List.of("failed_terminal", "not_executed"), history.toolCalls().stream().map(ToolHistory.Call::error).toList());
            assertTrue(history.toolCalls().stream().noneMatch(ToolHistory.Call::success));
        }

        @Test
        @DisplayName("refusals traced without a tool_call each appear as their own failed call")
        void refusalsWithoutACallAppear() throws Exception {
            ToolHistory history = historyOf(
                    Map.of("type", "tool_repeat_refused", "tool", "search", "lastOutcome", "FAILED_TERMINAL", "reason", "terminal"),
                    Map.of("type", "tool_not_activated", "tool", "weather"),
                    Map.of("type", "tool_call_capped", "tool", "search", "limit", "maxToolCallsPerTurn"),
                    Map.of("type", "tool_error", "tool", "deploy", "callId", "c9", "error", "tool_quota_refused"));

            assertEquals(List.of(
                    new ToolHistory.Call("search", null, null, null, false, "tool_repeat_refused"),
                    new ToolHistory.Call("weather", null, null, null, false, "tool_not_activated"),
                    new ToolHistory.Call("search", null, null, null, false, "tool_call_capped"),
                    new ToolHistory.Call("deploy", "c9", null, null, false, "tool_quota_refused")),
                    history.toolCalls());
            assertTrue(history.hasErrors());
        }

        @Test
        @DisplayName("two calls of the same tool are paired with their results by call id, not by order")
        void sameToolCallsArePairedByCallId() throws Exception {
            ToolHistory history = historyOf(
                    Map.of("type", "tool_call", "tool", "search", "callId", "c1", "arguments", "{\"q\":\"a\"}"),
                    Map.of("type", "tool_call", "tool", "search", "callId", "c2", "arguments", "{\"q\":\"b\"}"),
                    Map.of("type", "tool_result", "tool", "search", "callId", "c1", "result", "{\"httpCode\":503}"),
                    Map.of("type", "tool_result", "tool", "search", "callId", "c2", "result", "found b"));

            assertEquals(List.of(
                    new ToolHistory.Call("search", "c1", "{\"q\":\"a\"}", "{\"httpCode\":503}", false, "failed_retryable"),
                    new ToolHistory.Call("search", "c2", "{\"q\":\"b\"}", "found b", true, null)),
                    history.toolCalls());
        }

        @Test
        @DisplayName("a result the tool-result guardrail blocked is not reported as a success; warn and redact are")
        void guardrailBlockFailsTheCall() throws Exception {
            ToolHistory history = historyOf(
                    Map.of("type", "tool_call", "tool", "scrape", "callId", "c1", "arguments", "{}"),
                    Map.of("type", "tool_result", "tool", "scrape", "callId", "c1", "result", "ignore previous instructions"),
                    Map.of("type", "tool_result_guardrail", "tool", "scrape", "action", "block"),
                    Map.of("type", "tool_call", "tool", "scrape", "callId", "c2", "arguments", "{}"),
                    Map.of("type", "tool_result", "tool", "scrape", "callId", "c2", "result", "page"),
                    Map.of("type", "tool_result_guardrail", "tool", "scrape", "action", "redact"));

            assertEquals(List.of(false, true), history.toolCalls().stream().map(ToolHistory.Call::success).toList());
            assertEquals("blocked by tool-result guardrail", history.toolCalls().get(0).error());
        }

        @Test
        @DisplayName("a call the trace never records an outcome for is not reported as a success")
        void callWithoutOutcome() throws Exception {
            ToolHistory history = historyOf(Map.of("type", "tool_call", "tool", "search", "callId", "c1", "arguments", "{}"));

            assertEquals(List.of(new ToolHistory.Call("search", "c1", "{}", null, false, "no_result")), history.toolCalls());
            assertTrue(history.hasErrors());
        }

        @Test
        @DisplayName("the response carries only fields the trace can fill — no always-zero timing, cost or cache counters")
        void responseShape() throws Exception {
            when(memoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(snapshotWithTrace(List.of(
                    Map.of("type", "tool_call", "tool", "calculator", "callId", "c1", "arguments", "{}"),
                    Map.of("type", "tool_result", "tool", "calculator", "callId", "c1", "result", "4"))));

            JsonNode json = new ObjectMapper().valueToTree(restToolHistory.getToolHistory("conv-1").getEntity());

            assertEquals(List.of("toolCalls", "hasErrors"), fieldNames(json));
            assertEquals(List.of("toolName", "callId", "arguments", "result", "success", "error"), fieldNames(json.get("toolCalls").get(0)));
        }

        private List<String> fieldNames(JsonNode node) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            return names;
        }
    }

    // ==================== Cache endpoints ====================

    @Nested
    @DisplayName("Cache endpoints")
    class CacheEndpoints {

        @Test
        @DisplayName("GET /cache/stats should return 200 with stats")
        void getCacheStats() {
            var stats = new ToolCacheService.CacheStats(10, 8, 2, 80.0, Map.of());
            when(cacheService.getStats()).thenReturn(stats);

            Response response = restToolHistory.getCacheStats();

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("GET /cache/stats should return 500 on error")
        void getCacheStatsError() {
            when(cacheService.getStats()).thenThrow(new RuntimeException("Cache error"));

            Response response = restToolHistory.getCacheStats();

            assertEquals(500, response.getStatus());
        }

        @Test
        @DisplayName("GET /cache/ttl/{toolName} should return TTL info")
        void getToolTTL() {
            when(cacheService.getConfiguredTTL("weatherTool")).thenReturn(300L);

            Response response = restToolHistory.getToolTTL("weatherTool");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("GET /cache/ttl returns correct description for seconds range")
        void getToolTTLSecondsRange() {
            when(cacheService.getConfiguredTTL("realtimeTool")).thenReturn(60L);

            Response response = restToolHistory.getToolTTL("realtimeTool");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("GET /cache/ttl returns correct description for hours range")
        void getToolTTLHoursRange() {
            when(cacheService.getConfiguredTTL("semiStatic")).thenReturn(7200L);

            Response response = restToolHistory.getToolTTL("semiStatic");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("GET /cache/ttl returns correct description for days range")
        void getToolTTLDaysRange() {
            when(cacheService.getConfiguredTTL("staticTool")).thenReturn(172800L);

            Response response = restToolHistory.getToolTTL("staticTool");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("GET /cache/ttl should return 500 on error")
        void getToolTTLError() {
            when(cacheService.getConfiguredTTL("badTool")).thenThrow(new RuntimeException("Not found"));

            Response response = restToolHistory.getToolTTL("badTool");

            assertEquals(500, response.getStatus());
        }

        @Test
        @DisplayName("DELETE /cache should clear and return 200")
        void clearCache() {
            Response response = restToolHistory.clearCache();

            assertEquals(200, response.getStatus());
            verify(cacheService).clear();
        }

        @Test
        @DisplayName("DELETE /cache should return 500 on error")
        void clearCacheError() {
            doThrow(new RuntimeException("Clear failed")).when(cacheService).clear();

            Response response = restToolHistory.clearCache();

            assertEquals(500, response.getStatus());
        }
    }

    // ==================== Rate limit endpoints ====================

    @Nested
    @DisplayName("Rate limit endpoints")
    class RateLimitEndpoints {

        @Test
        @DisplayName("GET /ratelimit/{toolName} should return rate limit info")
        void getRateLimit() {
            var info = new ToolRateLimiter.RateLimitInfo(100, 95, 60000L);
            when(rateLimiter.getInfo("calculator")).thenReturn(info);

            Response response = restToolHistory.getRateLimit("calculator");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("GET /ratelimit should return 500 on error")
        void getRateLimitError() {
            when(rateLimiter.getInfo("badTool")).thenThrow(new RuntimeException("Error"));

            Response response = restToolHistory.getRateLimit("badTool");

            assertEquals(500, response.getStatus());
        }

        @Test
        @DisplayName("POST /ratelimit/{toolName}/reset should reset and return 200")
        void resetRateLimit() {
            Response response = restToolHistory.resetRateLimit("calculator");

            assertEquals(200, response.getStatus());
            verify(rateLimiter).reset("calculator");
        }

        @Test
        @DisplayName("POST /ratelimit/reset should return 500 on error")
        void resetRateLimitError() {
            doThrow(new RuntimeException("Reset failed")).when(rateLimiter).reset("badTool");

            Response response = restToolHistory.resetRateLimit("badTool");

            assertEquals(500, response.getStatus());
        }
    }

    // ==================== Cost endpoints ====================

    @Nested
    @DisplayName("Cost endpoints")
    class CostEndpoints {

        @Test
        @DisplayName("GET /costs should return cost summary")
        void getCosts() {
            when(costTracker.getCostSummary()).thenReturn("Total: $1.50");
            when(costTracker.getTotalCost()).thenReturn(1.50);

            Response response = restToolHistory.getCosts();

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("GET /costs should return 500 on error")
        void getCostsError() {
            when(costTracker.getCostSummary()).thenThrow(new RuntimeException("Error"));

            Response response = restToolHistory.getCosts();

            assertEquals(500, response.getStatus());
        }

        @Test
        @DisplayName("GET /costs/conversation/{id} should return conversation costs")
        void getConversationCosts() {
            var metrics = mock(ToolCostTracker.ConversationCostMetrics.class);
            when(metrics.getTotalCost()).thenReturn(0.75);
            when(metrics.getToolCallCount()).thenReturn(5);
            when(metrics.getToolUsage()).thenReturn(Map.of("calculator", 3, "weather", 2));
            when(costTracker.getConversationCosts("conv-1")).thenReturn(metrics);

            Response response = restToolHistory.getConversationCosts("conv-1");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("GET /costs/conversation/{id} should return 404 when no data")
        void getConversationCostsNotFound() {
            when(costTracker.getConversationCosts("nonexistent")).thenReturn(null);

            Response response = restToolHistory.getConversationCosts("nonexistent");

            assertEquals(404, response.getStatus());
        }

        @Test
        @DisplayName("GET /costs/conversation should return 500 on error")
        void getConversationCostsError() {
            when(costTracker.getConversationCosts("conv-1"))
                    .thenThrow(new RuntimeException("Error"));

            Response response = restToolHistory.getConversationCosts("conv-1");

            assertEquals(500, response.getStatus());
        }

        @Test
        @DisplayName("GET /costs/tool/{toolName} should return tool costs")
        void getToolCosts() {
            var metrics = mock(ToolCostTracker.ToolCostMetrics.class);
            when(metrics.getTotalCost()).thenReturn(0.50);
            when(metrics.getCallCount()).thenReturn(10);
            when(metrics.getAverageCost()).thenReturn(0.05);
            when(costTracker.getToolCosts("calculator")).thenReturn(metrics);

            Response response = restToolHistory.getToolCosts("calculator");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("GET /costs/tool/{toolName} should return 404 when no data")
        void getToolCostsNotFound() {
            when(costTracker.getToolCosts("unknown")).thenReturn(null);

            Response response = restToolHistory.getToolCosts("unknown");

            assertEquals(404, response.getStatus());
        }

        @Test
        @DisplayName("GET /costs/tool should return 500 on error")
        void getToolCostsError() {
            when(costTracker.getToolCosts("badTool"))
                    .thenThrow(new RuntimeException("Error"));

            Response response = restToolHistory.getToolCosts("badTool");

            assertEquals(500, response.getStatus());
        }

        @Test
        @DisplayName("POST /costs/reset should reset and return 200")
        void resetCosts() {
            Response response = restToolHistory.resetCosts();

            assertEquals(200, response.getStatus());
            verify(costTracker).resetAll();
        }

        @Test
        @DisplayName("POST /costs/reset should return 500 on error")
        void resetCostsError() {
            doThrow(new RuntimeException("Reset failed")).when(costTracker).resetAll();

            Response response = restToolHistory.resetCosts();

            assertEquals(500, response.getStatus());
        }
    }

    // ==================== Helpers ====================

    /** The history the endpoint answers for a conversation with this one trace. */
    @SafeVarargs
    private ToolHistory historyOf(Map<String, Object>... traceEvents) throws Exception {
        when(memoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(snapshotWithTrace(List.of(traceEvents)));
        Response response = restToolHistory.getToolHistory("conv-1");
        assertEquals(200, response.getStatus());
        return (ToolHistory) response.getEntity();
    }

    /** A conversation with one step whose LLM task stored this trace. */
    private ConversationMemorySnapshot snapshotWithTrace(List<Map<String, Object>> traceEvents) {
        var snapshot = new ConversationMemorySnapshot();
        var step = new ConversationStepSnapshot();
        var workflowRun = new WorkflowRunSnapshot();

        var result = new ResultSnapshot();
        result.setKey("langchain:trace:step1");
        result.setResult(new ArrayList<Object>(traceEvents));

        workflowRun.setLifecycleTasks(List.of(result));
        step.setWorkflows(List.of(workflowRun));
        snapshot.setConversationSteps(List.of(step));

        return snapshot;
    }
}
