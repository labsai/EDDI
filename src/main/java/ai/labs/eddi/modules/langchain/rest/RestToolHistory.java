package ai.labs.eddi.modules.langchain.rest;

import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.PackageRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.modules.langchain.model.ToolExecutionTrace;
import ai.labs.eddi.modules.langchain.model.ToolExecutionTrace.ToolCall;
import ai.labs.eddi.modules.langchain.tools.ToolCacheService;
import ai.labs.eddi.modules.langchain.tools.ToolCostTracker;
import ai.labs.eddi.modules.langchain.tools.ToolRateLimiter;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for tool execution history, metrics, and management.
 * Phase 4: Exposes tool call history and metrics to clients.
 */
@Path("/langchain/tools")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RestToolHistory {
    private static final Logger LOGGER = Logger.getLogger(RestToolHistory.class);

    @Inject
    ToolCacheService cacheService;

    @Inject
    ToolRateLimiter rateLimiter;

    @Inject
    ToolCostTracker costTracker;

    @Inject
    IConversationMemoryStore conversationMemoryStore;

    /**
     * Get tool execution history for a conversation
     */
    @GET
    @Path("/history/{conversationId}")
    public Response getToolHistory(@PathParam("conversationId") String conversationId) {
        try {
            ConversationMemorySnapshot snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            ToolExecutionTrace trace = new ToolExecutionTrace();
            List<ToolCall> toolCalls = new ArrayList<>();

            for (ConversationStepSnapshot step : snapshot.getConversationSteps()) {
                for (PackageRunSnapshot packageRun : step.getPackages()) {
                    for (ResultSnapshot data : packageRun.getLifecycleTasks()) {
                        if (data.getKey() != null && data.getKey().startsWith("langchain:trace:")) {
                            Object result = data.getResult();
                            if (result instanceof List<?> rawList) {
                                List<Map<String, Object>> stepTrace = new ArrayList<>();
                                for (Object item : rawList) {
                                    if (item instanceof Map) {
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> mapItem = (Map<String, Object>) item;
                                        stepTrace.add(mapItem);
                                    } else {
                                        LOGGER.warn("Unexpected item type in tool trace list: " + item.getClass());
                                    }
                                }
                                processStepTrace(stepTrace, toolCalls);
                            }
                        }
                    }
                }
            }
            
            trace.setToolCalls(toolCalls);
            // Calculate totals
            trace.setTotalExecutionTimeMs(toolCalls.stream().mapToLong(ToolCall::getExecutionTimeMs).sum());
            trace.setHasErrors(toolCalls.stream().anyMatch(tc -> tc.getError() != null));
            trace.setTotalCost(toolCalls.stream().mapToDouble(ToolCall::getCost).sum());

            return Response.ok(trace).build();

        } catch (ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Conversation not found"))
                    .build();
        } catch (Exception e) {
            LOGGER.error("Error retrieving tool history", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    private void processStepTrace(List<Map<String, Object>> stepTrace, List<ToolCall> toolCalls) {
        ToolCall currentCall = null;
        for (Map<String, Object> event : stepTrace) {
            String type = (String) event.get("type");
            if ("tool_call".equals(type)) {
                currentCall = new ToolCall();
                currentCall.setToolName((String) event.get("tool"));
                currentCall.setArguments((String) event.get("arguments"));
                currentCall.setSuccess(true); // Assume success until error
                toolCalls.add(currentCall);
            } else if ("tool_result".equals(type) && currentCall != null) {
                // Match with last call - simplistic but works for sequential execution
                String toolName = currentCall.getToolName();
                Object eventTool = event.get("tool");
                if (toolName != null && toolName.equals(eventTool)) {
                    currentCall.setResult((String) event.get("result"));
                }
            }
        }
    }

    /**
     * Get cache statistics
     */
    @GET
    @Path("/cache/stats")
    public Response getCacheStats() {
        try {
            ToolCacheService.CacheStats stats = cacheService.getStats();

            Map<String, Object> response = new HashMap<>();
            response.put("size", stats.size);
            response.put("hits", stats.hits);
            response.put("misses", stats.misses);
            response.put("hitRate", stats.hitRate);
            response.put("perToolStats", stats.perToolStats);
            response.put("details", stats.toString());

            return Response.ok(response).build();

        } catch (Exception e) {
            LOGGER.error("Error fetching cache stats", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    /**
     * Get smart TTL configuration for a tool
     */
    @GET
    @Path("/cache/ttl/{toolName}")
    public Response getToolTTL(@PathParam("toolName") String toolName) {
        try {
            long ttlSeconds = cacheService.getConfiguredTTL(toolName);

            Map<String, Object> response = Map.of(
                "toolName", toolName,
                "ttlSeconds", ttlSeconds,
                "ttlMinutes", ttlSeconds / 60,
                "ttlHours", ttlSeconds / 3600,
                "description", getSmartTTLDescription(ttlSeconds)
            );

            return Response.ok(response).build();

        } catch (Exception e) {
            LOGGER.error("Error fetching tool TTL", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    /**
     * Get human-readable description for TTL
     */
    private String getSmartTTLDescription(long seconds) {
        if (seconds < 120) {
            return seconds + " seconds - Real-time data";
        } else if (seconds < 3600) {
            return (seconds / 60) + " minutes - Frequently changing data";
        } else if (seconds < 86400) {
            return (seconds / 3600) + " hours - Semi-static data";
        } else {
            return (seconds / 86400) + " days - Static data";
        }
    }

    /**
     * Clear tool cache
     */
    @DELETE
    @Path("/cache")
    public Response clearCache() {
        try {
            cacheService.clear();
            return Response.ok(Map.of("message", "Cache cleared successfully")).build();

        } catch (Exception e) {
            LOGGER.error("Error clearing cache", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    /**
     * Get rate limit info for a tool
     */
    @GET
    @Path("/ratelimit/{toolName}")
    public Response getRateLimit(@PathParam("toolName") String toolName) {
        try {
            ToolRateLimiter.RateLimitInfo info = rateLimiter.getInfo(toolName);

            Map<String, Object> response = Map.of(
                "tool", toolName,
                "limit", info.limit,
                "remaining", info.remaining,
                "resetTimeMs", info.resetTimeMs
            );

            return Response.ok(response).build();

        } catch (Exception e) {
            LOGGER.error("Error fetching rate limit info", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    /**
     * Reset rate limit for a tool
     */
    @POST
    @Path("/ratelimit/{toolName}/reset")
    public Response resetRateLimit(@PathParam("toolName") String toolName) {
        try {
            rateLimiter.reset(toolName);
            return Response.ok(Map.of("message", "Rate limit reset for " + toolName)).build();

        } catch (Exception e) {
            LOGGER.error("Error resetting rate limit", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    /**
     * Get cost summary for all tools
     */
    @GET
    @Path("/costs")
    public Response getCosts() {
        try {
            String summary = costTracker.getCostSummary();

            Map<String, Object> response = Map.of(
                "totalCost", costTracker.getTotalCost(),
                "summary", summary
            );

            return Response.ok(response).build();

        } catch (Exception e) {
            LOGGER.error("Error fetching cost summary", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    /**
     * Get costs for a specific conversation
     */
    @GET
    @Path("/costs/conversation/{conversationId}")
    public Response getConversationCosts(@PathParam("conversationId") String conversationId) {
        try {
            ToolCostTracker.ConversationCostMetrics metrics =
                costTracker.getConversationCosts(conversationId);

            if (metrics == null) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No cost data found for conversation"))
                    .build();
            }

            Map<String, Object> response = Map.of(
                "conversationId", conversationId,
                "totalCost", metrics.getTotalCost(),
                "toolCallCount", metrics.getToolCallCount(),
                "toolUsage", metrics.getToolUsage()
            );

            return Response.ok(response).build();

        } catch (Exception e) {
            LOGGER.error("Error fetching conversation costs", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    /**
     * Get costs for a specific tool
     */
    @GET
    @Path("/costs/tool/{toolName}")
    public Response getToolCosts(@PathParam("toolName") String toolName) {
        try {
            ToolCostTracker.ToolCostMetrics metrics = costTracker.getToolCosts(toolName);

            if (metrics == null) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No cost data found for tool"))
                    .build();
            }

            Map<String, Object> response = Map.of(
                "toolName", toolName,
                "totalCost", metrics.getTotalCost(),
                "callCount", metrics.getCallCount(),
                "averageCost", metrics.getAverageCost()
            );

            return Response.ok(response).build();

        } catch (Exception e) {
            LOGGER.error("Error fetching tool costs", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    /**
     * Reset all cost tracking
     */
    @POST
    @Path("/costs/reset")
    public Response resetCosts() {
        try {
            costTracker.resetAll();
            return Response.ok(Map.of("message", "All cost tracking reset")).build();

        } catch (Exception e) {
            LOGGER.error("Error resetting costs", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }
}

