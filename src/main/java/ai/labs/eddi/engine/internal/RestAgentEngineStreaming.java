package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IRestAgentEngineStreaming;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;

import ai.labs.eddi.engine.model.InputData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

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

    private static String sanitizeForLog(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\n', '_').replace('\r', '_');
    }

    @Override
    public void sayStreaming(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields,
                             InputData inputData, SseEventSink eventSink, Sse sse) {

        final String safeConversationId = sanitizeForLog(conversationId);
        try {
            conversationService.sayStreaming(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData,
                    new IConversationService.StreamingResponseHandler() {
                        @Override
                        public void onTaskStart(String taskId, String taskType, int index) {
                            sendEvent(eventSink, sse, "task_start",
                                    String.format("{\"taskId\":\"%s\",\"taskType\":\"%s\",\"index\":%d}", taskId, taskType, index));
                        }

                        @Override
                        public void onTaskComplete(String taskId, String taskType, long durationMs, Map<String, Object> summary) {
                            var sb = new StringBuilder();
                            sb.append(String.format("{\"taskId\":\"%s\",\"taskType\":\"%s\",\"durationMs\":%d", taskId, taskType, durationMs));
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

    private void closeQuietly(SseEventSink eventSink) {
        try {
            if (!eventSink.isClosed()) {
                eventSink.close();
            }
        } catch (Exception e) {
            LOGGER.debugf("Error closing SSE sink: %s", e.getMessage());
        }
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
