/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A2A protocol data types.
 * <p>
 * <b>Pinned to A2A 1.0</b> (specification release v1.0.1). The 0.3 dialect
 * ({@code message/send}, {@code kind} discriminators, kebab-case states) and
 * the pre-0.2 {@code tasks/send} call are still accepted; see
 * {@link A2AWireFormat} for how a task is rendered in each, and
 * {@code docs/a2a-protocol.md} for the compatibility table.
 * <p>
 * The task types here are dialect-neutral: {@link A2AWireFormat} turns them
 * into the JSON a given peer expects, so the handler never has to know which
 * dialect it is answering.
 *
 * @author ginccc
 */
public final class A2AModels {

    /** The A2A specification version EDDI implements natively. */
    public static final String PROTOCOL_VERSION = "1.0";

    /** The older protocol version still served for 0.3 peers. */
    public static final String LEGACY_PROTOCOL_VERSION = "0.3";

    /**
     * The value 0.3 cards carry in {@code protocolVersion}, full semver as that
     * version's schema writes it.
     */
    public static final String LEGACY_CARD_PROTOCOL_VERSION = "0.3.0";

    /** The A2A transport binding EDDI serves. */
    public static final String TRANSPORT_JSONRPC = "JSONRPC";

    /** HTTP header in which an A2A 1.0 client names the protocol version. */
    public static final String VERSION_HEADER = "A2A-Version";

    private A2AModels() {
    }

    // === Dialects ===

    /**
     * Which version of the wire format a request arrived in — and therefore which
     * one its answer is rendered in. Decided by the JSON-RPC method name, which
     * differs between all three.
     */
    public enum Dialect {
        /** A2A 1.0: {@code SendMessage}, {@code TASK_STATE_*}, oneof parts. */
        V1_0,
        /** A2A 0.2/0.3: {@code message/send}, {@code kind}, kebab-case states. */
        V0_3,
        /**
         * Pre-0.2 {@code tasks/send}, which EDDI served until 6.5 and still accepts.
         * Rendered like 0.3, with the old {@code type} part discriminator added.
         */
        LEGACY
    }

    // === Agent Card ===

    /**
     * An Agent Card readable by both A2A 1.0 and 0.3 clients.
     * <p>
     * {@code supportedInterfaces} is the 1.0 field and lists both protocol
     * versions; {@code url}, {@code protocolVersion} and {@code preferredTransport}
     * are what a 0.3 client reads, and a 1.0 client ignores them.
     * {@code authentication} is EDDI's pre-0.2 field, kept so an older EDDI client
     * still finds its token endpoint.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentCard(String name, String description, String url, String protocolVersion, String preferredTransport,
            List<AgentInterface> supportedInterfaces, AgentProvider provider, String version, AgentCapabilities capabilities,
            Map<String, Object> securitySchemes, List<Map<String, Object>> securityRequirements, List<String> defaultInputModes,
            List<String> defaultOutputModes, List<AgentSkill> skills, AgentAuthentication authentication) {
    }

    /** One protocol binding the agent answers on (A2A 1.0). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentInterface(String url, String protocolBinding, String protocolVersion) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentProvider(String organization, String url) {
    }

    /**
     * Pre-0.2 authentication hint: the schemes and the token endpoint. Superseded
     * by {@code securitySchemes}, kept for older EDDI clients.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentAuthentication(List<String> schemes, String credentials) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentCapabilities(boolean streaming, boolean pushNotifications, boolean extendedAgentCard) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentSkill(String id, String name, String description, List<String> tags, List<String> examples) {
    }

    // === JSON-RPC 2.0 ===

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JsonRpcRequest(String jsonrpc, String method, Map<String, Object> params, Object id) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcResponse(String jsonrpc, Object id, Object result, JsonRpcError error) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcError(int code, String message, Object data) {
    }

    // === A2A Task (dialect-neutral) ===

    /**
     * The lifecycle state of a task, with its name in each wire dialect.
     */
    public enum TaskState {
        submitted("submitted", "TASK_STATE_SUBMITTED"), working("working", "TASK_STATE_WORKING"), input_required("input-required",
                "TASK_STATE_INPUT_REQUIRED"), completed("completed", "TASK_STATE_COMPLETED"), canceled("canceled", "TASK_STATE_CANCELED"), failed(
                        "failed", "TASK_STATE_FAILED"), rejected("rejected", "TASK_STATE_REJECTED"), auth_required("auth-required",
                                "TASK_STATE_AUTH_REQUIRED"), unknown("unknown", "TASK_STATE_UNSPECIFIED");

        private final String v03Name;
        private final String v10Name;

        TaskState(String v03Name, String v10Name) {
            this.v03Name = v03Name;
            this.v10Name = v10Name;
        }

        /** The name in the given dialect. */
        public String wireName(Dialect dialect) {
            return dialect == Dialect.V1_0 ? v10Name : v03Name;
        }

        /**
         * Terminal states end a task for good: no further message can continue it and
         * it cannot be cancelled.
         */
        public boolean isTerminal() {
            return this == completed || this == canceled || this == failed || this == rejected;
        }

        /**
         * Reads a state in any dialect — {@code TASK_STATE_INPUT_REQUIRED},
         * {@code input-required} and {@code input_required} alike.
         *
         * @return the state, or {@link #unknown} for anything unrecognised
         */
        public static TaskState fromWire(String raw) {
            if (raw == null) {
                return unknown;
            }
            String normalized = raw.trim().toLowerCase();
            if (normalized.startsWith("task_state_")) {
                normalized = normalized.substring("task_state_".length());
            }
            normalized = normalized.replace('-', '_');
            if ("cancelled".equals(normalized)) {
                normalized = canceled.name();
            }
            for (TaskState state : values()) {
                if (state.name().equals(normalized)) {
                    return state;
                }
            }
            return unknown;
        }
    }

    /** A message on the wire: who said it, and what. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record A2AMessage(String messageId, String role, List<Part> parts, String taskId, String contextId) {

        public static final String ROLE_USER = "user";
        public static final String ROLE_AGENT = "agent";
    }

    /**
     * A content part. Exactly one of {@code text} and {@code data} is set; the
     * dialect decides whether a discriminator is written next to it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Part(String text, Object data) {

        public static Part textPart(String text) {
            return new Part(text, null);
        }

        public static Part dataPart(Object data) {
            return new Part(null, data);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Artifact(String artifactId, String name, List<Part> parts) {
    }

    /** A task's state, the agent message explaining it, and when it was reached. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskStatus(TaskState state, A2AMessage message, Instant timestamp) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record A2ATask(String id, String contextId, TaskStatus status, List<A2AMessage> history, List<Artifact> artifacts) {

        public TaskState state() {
            return status == null ? TaskState.unknown : status.state();
        }
    }

    /**
     * What EDDI remembers about one task between requests — see
     * {@link IA2ATaskStore}.
     *
     * @param taskId
     *            the id the peer addresses the task by
     * @param contextId
     *            the context the task belongs to; null only for a legacy
     *            {@code tasks/send} that named none
     * @param conversationId
     *            the EDDI conversation the task's turn ran in
     * @param agentId
     *            the agent the task was sent to
     * @param state
     *            the last known state
     * @param statusText
     *            agent-authored explanation of the state, if any
     * @param responseText
     *            the turn's answer, set once the task completed
     * @param userText
     *            the peer's message
     * @param updatedAt
     *            when {@code state} was reached
     * @param generation
     *            which send this record belongs to. A pre-0.2 peer chooses its own
     *            task id and may send under the same id again while an earlier turn
     *            is still running; the earlier turn's late outcome must not
     *            overwrite the newer send's record. Null for a record re-derived
     *            from the conversation store.
     */
    public record A2ATaskRecord(String taskId, String contextId, String conversationId, String agentId, TaskState state, String statusText,
            String responseText, String userText, Instant updatedAt, String generation) implements Serializable {

        @Serial
        private static final long serialVersionUID = 2L;

        /** A record that belongs to no particular send. */
        public A2ATaskRecord(String taskId, String contextId, String conversationId, String agentId, TaskState state, String statusText,
                String responseText, String userText, Instant updatedAt) {
            this(taskId, contextId, conversationId, agentId, state, statusText, responseText, userText, updatedAt, null);
        }

        public A2ATaskRecord withOutcome(TaskState newState, String newStatusText, String newResponseText) {
            return new A2ATaskRecord(taskId, contextId, conversationId, agentId, newState, newStatusText, newResponseText, userText,
                    Instant.now(), generation);
        }
    }

    // === Errors ===

    /**
     * The peer's request is malformed. Its message is authored inside the A2A layer
     * and is therefore safe to hand back to an arbitrary remote peer verbatim —
     * unlike the text of an arbitrary downstream exception, which may carry
     * connection strings, internal host names or other deployment detail.
     * <p>
     * Extends {@link IllegalArgumentException} so callers that already handle that
     * type keep working.
     */
    public static class InvalidA2ARequestException extends IllegalArgumentException {

        private final int code;

        public InvalidA2ARequestException(String message) {
            this(ERROR_INVALID_PARAMS, message);
        }

        public InvalidA2ARequestException(int code, String message) {
            super(message);
            this.code = code;
        }

        /** The JSON-RPC error code the request is refused with. */
        public int getCode() {
            return code;
        }
    }

    /**
     * Every A2A in-flight slot is taken — see
     * {@code eddi.a2a.max-concurrent-requests}.
     */
    public static class A2ABusyException extends RuntimeException {

        public A2ABusyException(String message) {
            super(message);
        }
    }

    // === JSON-RPC Error Codes ===

    public static final int ERROR_TASK_NOT_FOUND = -32001;
    public static final int ERROR_TASK_NOT_CANCELABLE = -32002;
    public static final int ERROR_PUSH_NOTIFICATION_NOT_SUPPORTED = -32003;
    public static final int ERROR_UNSUPPORTED_OPERATION = -32004;
    public static final int ERROR_CONTENT_TYPE_NOT_SUPPORTED = -32005;
    public static final int ERROR_EXTENDED_CARD_NOT_CONFIGURED = -32007;
    public static final int ERROR_VERSION_NOT_SUPPORTED = -32009;
    public static final int ERROR_INVALID_REQUEST = -32600;
    public static final int ERROR_METHOD_NOT_FOUND = -32601;
    public static final int ERROR_INVALID_PARAMS = -32602;
    public static final int ERROR_INTERNAL = -32603;
}
