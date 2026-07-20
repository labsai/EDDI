/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IRestAgentEngineStreaming;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;

import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.model.InputData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.List;
import java.util.Map;

/**
 * SSE streaming implementation — maps ConversationService streaming events to
 * JAX-RS SSE events.
 * <p>
 * Event types:
 * <ul>
 * <li>{@code task_start} — lifecycle task began execution</li>
 * <li>{@code task_complete} — lifecycle task finished</li>
 * <li>{@code token} — LLM response token</li>
 * <li>{@code cascade_step_start} — a multi-model cascade step began
 * ({@code stepIndex}, {@code modelType}, {@code modelName},
 * {@code totalSteps})</li>
 * <li>{@code cascade_escalation} — a cascade step was rejected and escalated
 * ({@code fromStep}, {@code toStep}, {@code confidence}, {@code threshold},
 * {@code reason}, {@code durationMs})</li>
 * <li>{@code done} — full conversation snapshot (final event)</li>
 * <li>{@code error} — error during processing</li>
 * </ul>
 */
@ApplicationScoped
public class RestAgentEngineStreaming implements IRestAgentEngineStreaming {

    private static final Logger LOGGER = Logger.getLogger(RestAgentEngineStreaming.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final IConversationService conversationService;

    @Inject
    public RestAgentEngineStreaming(IConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public void sayStreaming(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields,
                             InputData inputData, SseEventSink eventSink, Sse sse) {

        final String safeConversationId = sanitize(conversationId);
        try {
            conversationService.sayStreaming(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData,
                    new IConversationService.StreamingResponseHandler() {
                        @Override
                        public void onTaskStart(TaskId taskId, String taskType, int index) {
                            sendEvent(eventSink, sse, "task_start",
                                    String.format("{\"taskId\":\"%s\",\"taskType\":\"%s\",\"index\":%d}", taskId.getIdentifier(), taskType, index));
                        }

                        @Override
                        public void onTaskComplete(TaskId taskId, String taskType, long durationMs, Map<String, Object> summary) {
                            var sb = new StringBuilder();
                            sb.append(String.format("{\"taskId\":\"%s\",\"taskType\":\"%s\",\"durationMs\":%d", taskId.getIdentifier(), taskType,
                                    durationMs));
                            if (summary.containsKey("actions")) {
                                sb.append(",\"actions\":").append(toJsonArray(summary.get("actions")));
                            }
                            if (summary.containsKey("toolTrace")) {
                                try {
                                    sb.append(",\"toolTrace\":").append(
                                            MAPPER.writeValueAsString(summary.get("toolTrace")));
                                } catch (Exception ex) {
                                    LOGGER.debugf("Failed to serialize toolTrace: %s", ex.getMessage());
                                }
                            }
                            if (summary.containsKey("confidence")) {
                                sb.append(",\"confidence\":").append(summary.get("confidence"));
                            }
                            sb.append("}");
                            sendEvent(eventSink, sse, "task_complete", sb.toString());
                        }

                        @Override
                        public void onToken(String token) {
                            sendEvent(eventSink, sse, "token", token);
                        }

                        @Override
                        public void onCascadeStepStart(int stepIndex, String modelType, String modelName, int totalSteps) {
                            sendJsonEvent(eventSink, sse, "cascade_step_start",
                                    new CascadeStepStartEvent(stepIndex, modelType, modelName, totalSteps));
                        }

                        @Override
                        public void onCascadeEscalation(int fromStep, int toStep, double confidence, double threshold, String reason,
                                                        long durationMs) {
                            sendJsonEvent(eventSink, sse, "cascade_escalation",
                                    new CascadeEscalationEvent(fromStep, toStep, finite(confidence), finite(threshold), reason, durationMs));
                        }

                        @Override
                        public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                            try {
                                // Send the final snapshot as JSON
                                sendEvent(eventSink, sse, "done", toJson(snapshot));
                            } finally {
                                closeQuietly(eventSink);
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            try {
                                LOGGER.errorf("Streaming error for conversation %s: %s", safeConversationId, error.getMessage());
                                sendEvent(eventSink, sse, "error", String.format("{\"message\":\"%s\"}", escapeJson(error.getMessage())));
                            } finally {
                                closeQuietly(eventSink);
                            }
                        }

                        @Override
                        public void onTaskFailed(TaskId taskId, String taskType, long durationMs,
                                                 String errorType, String errorSummary) {
                            sendEvent(eventSink, sse, "task_failed",
                                    String.format("{\"taskId\":\"%s\",\"taskType\":\"%s\",\"durationMs\":%d,\"errorType\":\"%s\",\"error\":\"%s\"}",
                                            escapeJson(taskId.getIdentifier()), escapeJson(taskType), durationMs,
                                            escapeJson(errorType), escapeJson(errorSummary)));
                        }
                    });
        } catch (Exception e) {
            LOGGER.errorf("Failed to start streaming for conversation %s: %s", safeConversationId, e.getMessage());
            sendEvent(eventSink, sse, "error", String.format("{\"message\":\"%s\"}", escapeJson(e.getMessage())));
            closeQuietly(eventSink);
        }
    }

    private void sendEvent(SseEventSink eventSink, Sse sse, String eventName, String data) {
        if (eventSink.isClosed()) {
            LOGGER.debugf("SSE sink closed, dropping event: %s", eventName);
            return;
        }
        try {
            eventSink.send(sse.newEventBuilder().name(eventName).data(String.class, data).build());
        } catch (Exception e) {
            LOGGER.warnf("Failed to send SSE event '%s': %s", eventName, e.getMessage());
        }
    }

    /**
     * Serialize a typed event payload to JSON via Jackson and send it. Preferred
     * over hand-built JSON strings — the mapper handles string escaping and number
     * formatting. Falls back to an empty object on the (unexpected) serialization
     * failure so a single bad payload cannot break the stream.
     */
    private void sendJsonEvent(SseEventSink eventSink, Sse sse, String eventName, Object payload) {
        String data;
        try {
            data = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            LOGGER.warnf("Failed to serialize '%s' event payload: %s", eventName, e.getMessage());
            data = "{}";
        }
        sendEvent(eventSink, sse, eventName, data);
    }

    /** Typed payload for the {@code cascade_step_start} SSE event. */
    private record CascadeStepStartEvent(int stepIndex, String modelType, String modelName, int totalSteps) {
    }

    /** Typed payload for the {@code cascade_escalation} SSE event. */
    private record CascadeEscalationEvent(int fromStep, int toStep, double confidence, double threshold, String reason, long durationMs) {
    }

    private void closeQuietly(SseEventSink eventSink) {
        try {
            if (!eventSink.isClosed()) {
                eventSink.close();
            }
        } catch (Exception e) {
            LOGGER.debugf("Error closing SSE sink: %s", e.getMessage());
        }
    }

    /**
     * Coerce a non-finite double (NaN/Infinity) to 0.0 so it serializes as valid
     * JSON.
     */
    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }

    private String escapeJson(String text) {
        if (text == null)
            return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String toJsonArray(Object obj) {
        if (obj instanceof List<?> list) {
            var sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0)
                    sb.append(",");
                sb.append("\"").append(escapeJson(String.valueOf(list.get(i)))).append("\"");
            }
            sb.append("]");
            return sb.toString();
        }
        return "[]";
    }

    private String toJson(SimpleConversationMemorySnapshot snapshot) {
        // Simplified JSON for the done event — the full snapshot is available
        // via the standard GET endpoint. We include the essential fields.
        try {
            var sb = new StringBuilder("{");
            sb.append("\"conversationState\":\"").append(snapshot.getConversationState()).append("\"");
            if (snapshot.getConversationOutputs() != null) {
                sb.append(",\"conversationOutputs\":")
                        .append(MAPPER.writeValueAsString(snapshot.getConversationOutputs()));
            }
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            LOGGER.warnf("Failed to serialize snapshot: %s", e.getMessage());
            return "{\"conversationState\":\"" + snapshot.getConversationState() + "\"}";
        }
    }
}
