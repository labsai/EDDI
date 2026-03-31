package ai.labs.eddi.engine.a2a;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * A2A protocol data types — Java records for clean serialization. These are
 * EDDI's lightweight representation of the A2A spec, independent of the
 * langchain4j-agentic-a2a SDK types, to keep the server endpoints decoupled.
 *
 * @author ginccc
 */
public final class A2AModels {

    private A2AModels() {
    }

    // === Agent Card ===

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentCard(String name, String description, String url, String provider, String version, AgentCapabilities capabilities,
            List<AgentSkill> skills, AgentAuthentication authentication) {
    }

    /**
     * Authentication requirements for an A2A agent endpoint. When auth is enabled,
     * consuming agents need to present a valid Bearer token obtained from the OIDC
     * provider.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentAuthentication(List<String> schemes, String credentials) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentCapabilities(boolean streaming, boolean pushNotifications, boolean stateTransitionHistory) {
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

    // === A2A Task ===

    public enum TaskState {
        submitted, working, input_required, completed, canceled, failed, unknown
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record A2ATask(String id, String contextId, TaskState status, List<A2AMessage> history, List<Artifact> artifacts,
            Map<String, Object> metadata) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record A2AMessage(String role, List<Part> parts, Map<String, Object> metadata) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Part(String type, String text, Map<String, Object> data, Map<String, Object> metadata) {

        public static Part textPart(String text) {
            return new Part("text", text, null, null);
        }

        public static Part dataPart(Map<String, Object> data) {
            return new Part("data", null, data, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Artifact(String name, String description, List<Part> parts, int index, Map<String, Object> metadata) {
    }

    // === JSON-RPC Error Codes ===

    public static final int ERROR_TASK_NOT_FOUND = -32001;
    public static final int ERROR_TASK_NOT_CANCELABLE = -32002;
    public static final int ERROR_METHOD_NOT_FOUND = -32601;
    public static final int ERROR_INVALID_PARAMS = -32602;
    public static final int ERROR_INTERNAL = -32603;
}
