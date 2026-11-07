/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.rest;
import ai.labs.eddi.datastore.IResourceStore;

import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrailConfig;
import ai.labs.eddi.modules.llm.impl.ToolOutcomeClassifier;
import ai.labs.eddi.modules.llm.impl.ToolOutcomeClassifier.Outcome;
import ai.labs.eddi.modules.llm.model.ToolHistory;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    /** The error of a call whose result the tool-result guardrail blocked. */
    private static final String GUARDRAIL_BLOCKED = "blocked by tool-result guardrail";
    /** The error of a call the trace opened but never recorded an outcome for. */
    private static final String NO_RESULT = "no_result";

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
     * <p>
     * The body is a {@link ToolHistory} built from the stored trace: every call
     * with its outcome, including calls refused before they ran.
     */
    @GET
    @Path("/history/{conversationId}")
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user"})
    public Response getToolHistory(@PathParam("conversationId") String conversationId) {
        conversationAccessGuard.requireConversationOwner(conversationId);
        try {
            ConversationMemorySnapshot snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (snapshot == null) {
                // The store answers null for an unknown id — a 404, not the 500 that
                // dereferencing it below produced.
                return conversationNotFound();
            }
            List<ToolHistory.Call> toolCalls = new ArrayList<>();

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
                                toolCalls.addAll(callsOf(stepTrace));
                            }
                        }
                    }
                }
            }

            return Response.ok(new ToolHistory(toolCalls, toolCalls.stream().anyMatch(call -> !call.success()))).build();

        } catch (IResourceStore.ResourceNotFoundException e) {
            return conversationNotFound();
        } catch (Exception e) {
            return internalError("Error retrieving tool history", e);
        }
    }

    private static Response conversationNotFound() {
        return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Conversation not found")).build();
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

    /**
     * The calls one stored tool-loop trace records, in trace order.
     * <p>
     * A {@code tool_call} opens a call. Its {@code tool_result} or
     * {@code tool_error} closes it, found by {@code callId}, or, in a trace without
     * call ids, as the earliest open call of the same tool. A result's outcome is
     * {@link ToolOutcomeClassifier}'s, the definition the repeat guard and the
     * quota give-back use, so a failed HTTP call is not reported as a success. A
     * {@code tool_error} with no open call (a call refused before it ran: argument
     * schema, quota, approval-pause cap) and the refusals that are never preceded
     * by a {@code tool_call} ({@code tool_repeat_refused},
     * {@code tool_not_activated}, {@code tool_call_capped}) become calls of their
     * own. A {@code tool_result_guardrail} that blocked the result fails the call
     * whose result it follows.
     */
    private static List<ToolHistory.Call> callsOf(List<Map<String, Object>> trace) {
        List<TracedCall> calls = new ArrayList<>();
        Map<String, TracedCall> byCallId = new HashMap<>();
        TracedCall lastWithResult = null;
        for (Map<String, Object> event : trace) {
            String type = text(event.get("type"));
            if (type == null) {
                continue;
            }
            String tool = text(event.get("tool"));
            String callId = text(event.get("callId"));
            switch (type) {
                case "tool_call" -> {
                    TracedCall call = new TracedCall(tool, callId, text(event.get("arguments")));
                    calls.add(call);
                    if (callId != null) {
                        byCallId.put(callId, call);
                    }
                }
                case "tool_result" -> {
                    TracedCall call = openCall(calls, byCallId, tool, callId);
                    call.result = text(event.get("result"));
                    Outcome outcome = ToolOutcomeClassifier.classify(call.result);
                    call.close(outcome == Outcome.SUCCESS ? null : outcome.name().toLowerCase(Locale.ROOT));
                    lastWithResult = call;
                }
                case "tool_error" -> {
                    String error = text(event.get("error"));
                    openCall(calls, byCallId, tool, callId).close(error != null ? error : type);
                }
                case "tool_repeat_refused", "tool_not_activated", "tool_call_capped" -> {
                    TracedCall refused = new TracedCall(tool, callId, null);
                    refused.close(type);
                    calls.add(refused);
                }
                case "tool_result_guardrail" -> {
                    if (ToolResultGuardrailConfig.ACTION_BLOCK.equals(event.get("action")) && lastWithResult != null
                            && Objects.equals(lastWithResult.toolName, tool)) {
                        lastWithResult.close(GUARDRAIL_BLOCKED);
                    }
                }
                default -> {
                    // Loop-level entries (cost ceilings, answer tool, HITL markers) are
                    // not tool calls.
                }
            }
        }
        return calls.stream().map(TracedCall::toCall).toList();
    }

    /**
     * The open call this result or error closes: the one with its call id, or,
     * without an id, the earliest open call of the same tool. A new call when there
     * is none, because the trace records no {@code tool_call} for a call refused
     * before it ran.
     */
    private static TracedCall openCall(List<TracedCall> calls, Map<String, TracedCall> byCallId, String tool, String callId) {
        if (callId != null) {
            TracedCall call = byCallId.get(callId);
            if (call != null && !call.closed) {
                return call;
            }
        } else {
            for (TracedCall call : calls) {
                if (!call.closed && call.callId == null && Objects.equals(call.toolName, tool)) {
                    return call;
                }
            }
        }
        TracedCall unopened = new TracedCall(tool, callId, null);
        calls.add(unopened);
        if (callId != null) {
            byCallId.put(callId, unopened);
        }
        return unopened;
    }

    private static String text(Object value) {
        return value != null ? value.toString() : null;
    }

    /** One call while its trace is read. */
    private static final class TracedCall {
        private final String toolName;
        private final String callId;
        private final String arguments;
        private String result;
        private String error;
        private boolean closed;

        private TracedCall(String toolName, String callId, String arguments) {
            this.toolName = toolName;
            this.callId = callId;
            this.arguments = arguments;
        }

        /** Records the outcome; a null error is a success. */
        private void close(String error) {
            this.error = error;
            this.closed = true;
        }

        /** A call the trace never closed has no known outcome, so it is no success. */
        private ToolHistory.Call toCall() {
            if (!closed) {
                return new ToolHistory.Call(toolName, callId, arguments, result, false, NO_RESULT);
            }
            return new ToolHistory.Call(toolName, callId, arguments, result, error == null, error);
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
