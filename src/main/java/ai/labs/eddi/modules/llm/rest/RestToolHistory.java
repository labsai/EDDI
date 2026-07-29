/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.rest;

import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.modules.llm.model.ToolExecutionTrace;
import ai.labs.eddi.modules.llm.model.ToolExecutionTrace.ToolCall;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolRateLimiter;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ai.labs.eddi.engine.memory.MemoryKeys.LANGCHAIN_TRACE_PREFIX;

/**
 * REST API for tool execution history, metrics, and management. Phase 4:
 * Exposes tool call history and metrics to clients.
 * <p>
 * This is the tool <em>control plane</em>: resetting a rate limiter unenforces
 * a per-tool budget, resetting costs unenforces the spend ceiling, and clearing
 * the cache invalidates deployment-wide state. It is therefore
 * {@code eddi-admin}-only by default; the single conversation-scoped read
 * ({@link #getToolHistory(String)}) widens that to the conversation's own
 * owner.
 */
@Path("/llm/tools")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Tools / Tool History", description = "Tool execution history, cache, rate limits, and cost tracking")
@RolesAllowed("eddi-admin")
@ApplicationScoped
public class RestToolHistory {
    private static final Logger LOGGER = Logger.getLogger(RestToolHistory.class);

    private final ToolCacheService cacheService;
    private final ToolRateLimiter rateLimiter;
    private final ToolCostTracker costTracker;
    private final IConversationMemoryStore conversationMemoryStore;
    private final ConversationAccessGuard conversationAccessGuard;

    @Inject
    public RestToolHistory(ToolCacheService cacheService,
            ToolRateLimiter rateLimiter,
            ToolCostTracker costTracker,
            IConversationMemoryStore conversationMemoryStore,
            ConversationAccessGuard conversationAccessGuard) {
        this.cacheService = cacheService;
        this.rateLimiter = rateLimiter;
        this.costTracker = costTracker;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationAccessGuard = conversationAccessGuard;
    }

    /**
     * Get tool execution history for a conversation.
     * <p>
     * The trace carries the raw tool ARGUMENTS and RESULTS of the conversation, so
     * it is gated on conversation ownership — a role check alone would let any
     * authenticated user read any conversation's tool traffic.
     */
    @GET
    @Path("/history/{conversationId}")
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user"})
    public Response getToolHistory(@PathParam("conversationId") String conversationId) {
        conversationAccessGuard.requireConversationOwner(conversationId);
        try {
            ConversationMemorySnapshot snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            ToolExecutionTrace trace = new ToolExecutionTrace();
            List<ToolCall> toolCalls = new ArrayList<>();

            for (ConversationStepSnapshot step : snapshot.getConversationSteps()) {
                for (WorkflowRunSnapshot packageRun : step.getWorkflows()) {
                    for (ResultSnapshot data : packageRun.getLifecycleTasks()) {
                        if (data.getKey() != null && data.getKey().startsWith(LANGCHAIN_TRACE_PREFIX)) {
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
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Conversation not found")).build();
        } catch (Exception e) {
            return internalError("Error retrieving tool history", e);
        }
    }

    /**
     * Builds a 500 that carries NO internal detail. The raw exception message from
     * a store or driver names collections, hosts, and replica-set members — logging
     * it is fine, returning it is an information leak. The correlation id is the
     * only thing shared with the caller: it ties their failed request to the logged
     * stack trace without describing the deployment.
     */
    private Response internalError(String context, Exception e) {
        String correlationId = UUID.randomUUID().toString();
        LOGGER.errorf(e, "%s [correlationId=%s]", context, correlationId);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", "Internal server error", "correlationId", correlationId))
                .build();
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
            return internalError("Error fetching cache stats", e);
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

            Map<String, Object> response = Map.of("toolName", toolName, "ttlSeconds", ttlSeconds, "ttlMinutes", ttlSeconds / 60, "ttlHours",
                    ttlSeconds / 3600, "description", getSmartTTLDescription(ttlSeconds));

            return Response.ok(response).build();

        } catch (Exception e) {
            return internalError("Error fetching tool TTL", e);
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
            return internalError("Error clearing cache", e);
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

            Map<String, Object> response = Map.of("tool", toolName, "limit", info.limit, "remaining", info.remaining, "resetTimeMs",
                    info.resetTimeMs);

            return Response.ok(response).build();

        } catch (Exception e) {
            return internalError("Error fetching rate limit info", e);
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
            return internalError("Error resetting rate limit", e);
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

            Map<String, Object> response = Map.of("totalCost", costTracker.getTotalCost(), "summary", summary);

            return Response.ok(response).build();

        } catch (Exception e) {
            return internalError("Error fetching cost summary", e);
        }
    }

    /**
     * Get costs for a specific conversation
     */
    @GET
    @Path("/costs/conversation/{conversationId}")
    public Response getConversationCosts(@PathParam("conversationId") String conversationId) {
        try {
            ToolCostTracker.ConversationCostMetrics metrics = costTracker.getConversationCosts(conversationId);

            if (metrics == null) {
                return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "No cost data found for conversation")).build();
            }

            Map<String, Object> response = Map.of("conversationId", conversationId, "totalCost", metrics.getTotalCost(), "toolCallCount",
                    metrics.getToolCallCount(), "toolUsage", metrics.getToolUsage());

            return Response.ok(response).build();

        } catch (Exception e) {
            return internalError("Error fetching conversation costs", e);
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
                return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "No cost data found for tool")).build();
            }

            Map<String, Object> response = Map.of("toolName", toolName, "totalCost", metrics.getTotalCost(), "callCount", metrics.getCallCount(),
                    "averageCost", metrics.getAverageCost());

            return Response.ok(response).build();

        } catch (Exception e) {
            return internalError("Error fetching tool costs", e);
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
            return internalError("Error resetting costs", e);
        }
    }
}
