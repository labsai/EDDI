/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.A2AMessage;
import ai.labs.eddi.engine.a2a.A2AModels.A2ATask;
import ai.labs.eddi.engine.a2a.A2AModels.Artifact;
import ai.labs.eddi.engine.a2a.A2AModels.Dialect;
import ai.labs.eddi.engine.a2a.A2AModels.InvalidA2ARequestException;
import ai.labs.eddi.engine.a2a.A2AModels.Part;
import ai.labs.eddi.engine.a2a.A2AModels.TaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.a2a.A2AModels.ERROR_CONTENT_TYPE_NOT_SUPPORTED;

/**
 * Reads A2A requests and writes A2A results in each supported wire dialect.
 * <p>
 * Three dialects reach the same endpoint and differ in almost every name:
 * <table>
 * <caption>What differs between the dialects</caption>
 * <tr>
 * <th></th>
 * <th>1.0 (pinned)</th>
 * <th>0.3</th>
 * <th>legacy (pre-0.2)</th>
 * </tr>
 * <tr>
 * <td>send</td>
 * <td>{@code SendMessage}</td>
 * <td>{@code message/send}</td>
 * <td>{@code tasks/send}</td>
 * </tr>
 * <tr>
 * <td>state</td>
 * <td>{@code TASK_STATE_COMPLETED}</td>
 * <td>{@code completed}</td>
 * <td>{@code completed}</td>
 * </tr>
 * <tr>
 * <td>role</td>
 * <td>{@code ROLE_AGENT}</td>
 * <td>{@code agent}</td>
 * <td>{@code agent}</td>
 * </tr>
 * <tr>
 * <td>part</td>
 * <td>{@code {"text": …}}</td>
 * <td>{@code {"kind":"text","text": …}}</td>
 * <td>{@code {"kind":…,"type":"text",…}}</td>
 * </tr>
 * <tr>
 * <td>send result</td>
 * <td>{@code {"task": {…}}}</td>
 * <td>the task, {@code "kind":"task"}</td>
 * <td>the task</td>
 * </tr>
 * </table>
 * A 1.0 client parses strictly (protobuf JSON without unknown fields), so a 1.0
 * answer carries <em>only</em> 1.0 names — a stray {@code kind} would fail it.
 */
public final class A2AWireFormat {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String KEY_TEXT = "text";
    private static final String KEY_DATA = "data";
    private static final String KEY_KIND = "kind";
    private static final String KEY_TYPE = "type";

    private A2AWireFormat() {
    }

    // === Methods ===

    /** What a JSON-RPC method asks for, independent of its dialect. */
    public enum Operation {
        SEND, STREAM, GET, CANCEL,
        /** Recognised, deliberately not implemented: list, resubscribe. */
        UNSUPPORTED,
        /** Recognised push-notification configuration methods. */
        PUSH_NOTIFICATIONS,
        /** Recognised extended-card method; EDDI publishes none. */
        EXTENDED_CARD
    }

    /** A JSON-RPC method resolved to its operation and dialect. */
    public record MethodRef(Operation operation, Dialect dialect) {
    }

    private static final Map<String, MethodRef> METHODS = Map.ofEntries(
            // A2A 1.0
            Map.entry("SendMessage", new MethodRef(Operation.SEND, Dialect.V1_0)),
            Map.entry("SendStreamingMessage", new MethodRef(Operation.STREAM, Dialect.V1_0)),
            Map.entry("GetTask", new MethodRef(Operation.GET, Dialect.V1_0)),
            Map.entry("CancelTask", new MethodRef(Operation.CANCEL, Dialect.V1_0)),
            Map.entry("ListTasks", new MethodRef(Operation.UNSUPPORTED, Dialect.V1_0)),
            Map.entry("SubscribeToTask", new MethodRef(Operation.UNSUPPORTED, Dialect.V1_0)),
            Map.entry("CreateTaskPushNotificationConfig", new MethodRef(Operation.PUSH_NOTIFICATIONS, Dialect.V1_0)),
            Map.entry("GetTaskPushNotificationConfig", new MethodRef(Operation.PUSH_NOTIFICATIONS, Dialect.V1_0)),
            Map.entry("ListTaskPushNotificationConfigs", new MethodRef(Operation.PUSH_NOTIFICATIONS, Dialect.V1_0)),
            Map.entry("DeleteTaskPushNotificationConfig", new MethodRef(Operation.PUSH_NOTIFICATIONS, Dialect.V1_0)),
            Map.entry("GetExtendedAgentCard", new MethodRef(Operation.EXTENDED_CARD, Dialect.V1_0)),
            // A2A 0.2 / 0.3
            Map.entry("message/send", new MethodRef(Operation.SEND, Dialect.V0_3)),
            Map.entry("message/stream", new MethodRef(Operation.STREAM, Dialect.V0_3)),
            Map.entry("tasks/get", new MethodRef(Operation.GET, Dialect.V0_3)),
            Map.entry("tasks/cancel", new MethodRef(Operation.CANCEL, Dialect.V0_3)),
            Map.entry("tasks/list", new MethodRef(Operation.UNSUPPORTED, Dialect.V0_3)),
            Map.entry("tasks/resubscribe", new MethodRef(Operation.UNSUPPORTED, Dialect.V0_3)),
            Map.entry("tasks/pushNotificationConfig/set", new MethodRef(Operation.PUSH_NOTIFICATIONS, Dialect.V0_3)),
            Map.entry("tasks/pushNotificationConfig/get", new MethodRef(Operation.PUSH_NOTIFICATIONS, Dialect.V0_3)),
            Map.entry("tasks/pushNotificationConfig/list", new MethodRef(Operation.PUSH_NOTIFICATIONS, Dialect.V0_3)),
            Map.entry("tasks/pushNotificationConfig/delete", new MethodRef(Operation.PUSH_NOTIFICATIONS, Dialect.V0_3)),
            Map.entry("agent/getAuthenticatedExtendedCard", new MethodRef(Operation.EXTENDED_CARD, Dialect.V0_3)),
            // Pre-0.2 — deprecated, still accepted
            Map.entry("tasks/send", new MethodRef(Operation.SEND, Dialect.LEGACY)),
            Map.entry("tasks/sendSubscribe", new MethodRef(Operation.STREAM, Dialect.LEGACY)));

    /**
     * @return the operation and dialect of a JSON-RPC method, or null when the
     *         method is not an A2A method at all
     */
    public static MethodRef resolveMethod(String method) {
        return method == null ? null : METHODS.get(method);
    }

    // === Requests ===

    /**
     * A send request, normalised across dialects.
     *
     * @param text
     *            the message's text parts, joined; data parts as JSON
     * @param messageId
     *            the peer's message id (generated when absent)
     * @param contextId
     *            the context to continue, or null for a new one
     * @param taskId
     *            the task the message continues (1.0/0.3 {@code message.taskId}),
     *            or null to start a new task
     * @param legacyTaskId
     *            the id a pre-0.2 peer chose for its task ({@code params.id})
     * @param returnImmediately
     *            answer as soon as the task is accepted instead of waiting for the
     *            turn (1.0 {@code returnImmediately}, 0.3 {@code blocking=false})
     * @param historyLength
     *            how many history messages to return; null for the default
     * @param dialect
     *            the dialect the request arrived in
     */
    public record SendRequest(String text, String messageId, String contextId, String taskId, String legacyTaskId,
            boolean returnImmediately, Integer historyLength, Dialect dialect) {
    }

    /**
     * Normalises the params of a send / stream call.
     *
     * @throws InvalidA2ARequestException
     *             when there is no message, or nothing in it EDDI can read
     */
    public static SendRequest parseSend(Map<String, Object> params, Dialect dialect) {
        if (params == null) {
            throw new InvalidA2ARequestException("Missing params");
        }
        if (!(params.get("message") instanceof Map<?, ?> message)) {
            throw new InvalidA2ARequestException("Missing 'message' in params");
        }
        String text = extractText(message.get("parts"));

        String contextId = optionalString(message.get("contextId"));
        if (contextId == null) {
            // Pre-0.2 peers put it next to the message rather than inside it.
            contextId = optionalString(params.get("contextId"));
        }

        boolean returnImmediately = false;
        Integer historyLength = null;
        if (params.get("configuration") instanceof Map<?, ?> configuration) {
            if (dialect == Dialect.V1_0) {
                returnImmediately = Boolean.TRUE.equals(configuration.get("returnImmediately"));
            } else {
                returnImmediately = Boolean.FALSE.equals(configuration.get("blocking"));
            }
            historyLength = optionalInt(configuration.get("historyLength"));
        }

        String legacyTaskId = dialect == Dialect.LEGACY ? optionalString(params.get("id")) : null;
        String taskId = dialect == Dialect.LEGACY ? null : optionalString(message.get("taskId"));
        return new SendRequest(text, optionalString(message.get("messageId")), contextId, taskId, legacyTaskId, returnImmediately,
                historyLength, dialect);
    }

    /**
     * The task id of a get / cancel call.
     *
     * @throws InvalidA2ARequestException
     *             when it is missing
     */
    public static String requireTaskId(Map<String, Object> params) {
        String taskId = params == null ? null : optionalString(params.get("id"));
        if (taskId == null) {
            throw new InvalidA2ARequestException("Missing task id");
        }
        return taskId;
    }

    /** The {@code historyLength} of a get call, or null. */
    public static Integer historyLength(Map<String, Object> params) {
        return params == null ? null : optionalInt(params.get("historyLength"));
    }

    /**
     * Joins every readable part: text as is, structured data as JSON. A message
     * whose parts are all files is refused with ContentTypeNotSupported rather than
     * run as an empty turn.
     */
    static String extractText(Object partsObj) {
        if (!(partsObj instanceof List<?> parts) || parts.isEmpty()) {
            throw new InvalidA2ARequestException("No text content found in message parts");
        }
        List<String> texts = new ArrayList<>();
        boolean unreadable = false;
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> partMap)) {
                continue;
            }
            Object discriminator = partMap.get(KEY_KIND) != null ? partMap.get(KEY_KIND) : partMap.get(KEY_TYPE);
            Object text = partMap.get(KEY_TEXT);
            if (text instanceof String s && (discriminator == null || KEY_TEXT.equals(discriminator))) {
                if (!s.isBlank()) {
                    texts.add(s);
                }
            } else if (partMap.get(KEY_DATA) != null && (discriminator == null || KEY_DATA.equals(discriminator))) {
                texts.add(toJson(partMap.get(KEY_DATA)));
            } else {
                unreadable = true;
            }
        }
        if (texts.isEmpty()) {
            if (unreadable) {
                throw new InvalidA2ARequestException(ERROR_CONTENT_TYPE_NOT_SUPPORTED,
                        "Only text and data parts are supported; file parts are not");
            }
            throw new InvalidA2ARequestException("No text content found in message parts");
        }
        return String.join("\n", texts);
    }

    // === Results ===

    /** A {@code SendMessage} / {@code message/send} / {@code tasks/send} result. */
    public static Object sendResult(A2ATask task, Dialect dialect) {
        Map<String, Object> rendered = task(task, dialect);
        if (dialect == Dialect.V1_0) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("task", rendered);
            return wrapper;
        }
        return rendered;
    }

    /** A task, as {@code GetTask} / {@code CancelTask} return it. */
    public static Map<String, Object> task(A2ATask task, Dialect dialect) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", task.id());
        putIfNotNull(json, "contextId", task.contextId());
        json.put("status", status(task.status(), dialect));
        if (task.artifacts() != null && !task.artifacts().isEmpty()) {
            json.put("artifacts", task.artifacts().stream().map(artifact -> artifact(artifact, dialect)).toList());
        }
        if (task.history() != null && !task.history().isEmpty()) {
            json.put("history", task.history().stream().map(message -> message(message, dialect)).toList());
        }
        if (dialect != Dialect.V1_0) {
            json.put(KEY_KIND, "task");
        }
        return json;
    }

    /**
     * One streaming event wrapping the whole task — the first event of a stream.
     */
    public static Map<String, Object> streamTask(A2ATask task, Dialect dialect) {
        Map<String, Object> rendered = task(task, dialect);
        if (dialect == Dialect.V1_0) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("task", rendered);
            return wrapper;
        }
        return rendered;
    }

    /**
     * A status-update streaming event.
     *
     * @param last
     *            whether the stream ends after this event (0.3 {@code final}; 1.0
     *            infers it from a terminal or interrupted state)
     */
    public static Map<String, Object> statusUpdate(A2ATask task, boolean last, Dialect dialect) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("taskId", task.id());
        putIfNotNull(event, "contextId", task.contextId());
        event.put("status", status(task.status(), dialect));
        if (dialect == Dialect.V1_0) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("statusUpdate", event);
            return wrapper;
        }
        event.put(KEY_KIND, "status-update");
        event.put("final", last);
        return event;
    }

    /**
     * An artifact-update streaming event.
     *
     * @param append
     *            whether the parts extend the artifact rather than replace it
     * @param lastChunk
     *            whether this is the artifact's final content
     */
    public static Map<String, Object> artifactUpdate(A2ATask task, Artifact artifact, boolean append, boolean lastChunk, Dialect dialect) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("taskId", task.id());
        putIfNotNull(event, "contextId", task.contextId());
        event.put("artifact", artifact(artifact, dialect));
        event.put("append", append);
        event.put("lastChunk", lastChunk);
        if (dialect == Dialect.V1_0) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("artifactUpdate", event);
            return wrapper;
        }
        event.put(KEY_KIND, "artifact-update");
        return event;
    }

    static Map<String, Object> status(TaskStatus status, Dialect dialect) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("state", status.state().wireName(dialect));
        if (status.message() != null) {
            json.put("message", message(status.message(), dialect));
        }
        if (status.timestamp() != null) {
            json.put("timestamp", status.timestamp().toString());
        }
        return json;
    }

    static Map<String, Object> message(A2AMessage message, Dialect dialect) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("messageId", message.messageId());
        json.put("role", role(message.role(), dialect));
        json.put("parts", parts(message.parts(), dialect));
        putIfNotNull(json, "taskId", message.taskId());
        putIfNotNull(json, "contextId", message.contextId());
        if (dialect != Dialect.V1_0) {
            json.put(KEY_KIND, "message");
        }
        return json;
    }

    static Map<String, Object> artifact(Artifact artifact, Dialect dialect) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("artifactId", artifact.artifactId());
        putIfNotNull(json, "name", artifact.name());
        json.put("parts", parts(artifact.parts(), dialect));
        return json;
    }

    private static List<Map<String, Object>> parts(List<Part> parts, Dialect dialect) {
        if (parts == null) {
            return List.of();
        }
        return parts.stream().map(part -> part(part, dialect)).toList();
    }

    static Map<String, Object> part(Part part, Dialect dialect) {
        Map<String, Object> json = new LinkedHashMap<>();
        String kind = part.text() != null ? KEY_TEXT : KEY_DATA;
        if (dialect != Dialect.V1_0) {
            json.put(KEY_KIND, kind);
        }
        if (dialect == Dialect.LEGACY) {
            json.put(KEY_TYPE, kind);
        }
        if (part.text() != null) {
            json.put(KEY_TEXT, part.text());
        } else {
            json.put(KEY_DATA, part.data());
        }
        return json;
    }

    private static String role(String role, Dialect dialect) {
        if (dialect != Dialect.V1_0) {
            return role;
        }
        return A2AMessage.ROLE_USER.equals(role) ? "ROLE_USER" : "ROLE_AGENT";
    }

    // === Helpers ===

    private static void putIfNotNull(Map<String, Object> json, String key, Object value) {
        if (value != null) {
            json.put(key, value);
        }
    }

    /**
     * A missing key, an explicit JSON null and a blank string all read as absent.
     */
    static String optionalString(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private static Integer optionalInt(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException e) {
                throw new InvalidA2ARequestException("historyLength must be an integer");
            }
        }
        return null;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new InvalidA2ARequestException("Unreadable data part");
        }
    }
}
