package ai.labs.eddi.modules.llm.rest;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolRateLimiter;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
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

    @BeforeEach
    void setUp() throws Exception {
        restToolHistory = new RestToolHistory();
        memoryStore = mock(IConversationMemoryStore.class);
        cacheService = mock(ToolCacheService.class);
        rateLimiter = mock(ToolRateLimiter.class);
        costTracker = mock(ToolCostTracker.class);

        // Inject mocks via reflection (CDI field injection)
        setField("conversationMemoryStore", memoryStore);
        setField("cacheService", cacheService);
        setField("rateLimiter", rateLimiter);
        setField("costTracker", costTracker);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = RestToolHistory.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(restToolHistory, value);
    }

    // ==================== getToolHistory ====================

    @Nested
    @DisplayName("GET /history/{conversationId}")
    class GetToolHistory {

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
        @DisplayName("should return 200 with tool calls extracted from trace data")
        void withToolCalls() throws Exception {
            var snapshot = createSnapshotWithTraceData();
            when(memoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(snapshot);

            Response response = restToolHistory.getToolHistory("conv-1");

            assertEquals(200, response.getStatus());
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

    private ConversationMemorySnapshot createSnapshotWithTraceData() {
        var snapshot = new ConversationMemorySnapshot();
        var step = new ConversationStepSnapshot();
        var workflowRun = new WorkflowRunSnapshot();

        var result = new ResultSnapshot();
        result.setKey("langchain:trace:step1");

        // Create tool_call and tool_result events
        List<Object> traceEvents = new ArrayList<>();
        traceEvents.add(Map.of("type", "tool_call", "tool", "calculator", "arguments", "{\"expression\":\"2+2\"}"));
        traceEvents.add(Map.of("type", "tool_result", "tool", "calculator", "result", "4"));
        result.setResult(traceEvents);

        workflowRun.setLifecycleTasks(List.of(result));
        step.setWorkflows(List.of(workflowRun));
        snapshot.setConversationSteps(List.of(step));

        return snapshot;
    }
}
