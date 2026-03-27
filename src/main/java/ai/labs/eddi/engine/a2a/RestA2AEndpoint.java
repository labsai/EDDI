package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * JAX-RS endpoints for the A2A protocol.
 * <ul>
 * <li>{@code GET /.well-known/agent.json} — default Agent Card</li>
 * <li>{@code GET /a2a/agents/{agentId}/agent.json} — per-agent Agent Card</li>
 * <li>{@code POST /a2a/agents/{agentId}} — JSON-RPC 2.0 endpoint</li>
 * <li>{@code GET /a2a/agents} — list all A2A-enabled agents</li>
 * </ul>
 *
 * @author ginccc
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.enterprise.context.ApplicationScoped
public class RestA2AEndpoint {

    private static final Logger LOGGER = Logger.getLogger(RestA2AEndpoint.class);

    private final AgentCardService agentCardService;
    private final A2ATaskHandler taskHandler;
    private final boolean a2aEnabled;

    @Inject
    public RestA2AEndpoint(AgentCardService agentCardService, A2ATaskHandler taskHandler,
            @ConfigProperty(name = "eddi.a2a.enabled", defaultValue = "true") boolean a2aEnabled) {
        this.agentCardService = agentCardService;
        this.taskHandler = taskHandler;
        this.a2aEnabled = a2aEnabled;
    }

    /**
     * Default Agent Card — returns the first A2A-enabled agent's card.
     */
    @GET
    @Path(".well-known/agent.json")
    public Response getDefaultAgentCard() {
        if (!a2aEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        List<AgentCard> cards = agentCardService.listA2AAgents();
        if (cards.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "No A2A-enabled agents found")).build();
        }

        return Response.ok(cards.get(0)).build();
    }

    /**
     * Per-agent Agent Card.
     */
    @GET
    @Path("a2a/agents/{agentId}/agent.json")
    public Response getAgentCard(@PathParam("agentId") String agentId) {
        if (!a2aEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        AgentCard card = agentCardService.getAgentCard(agentId);
        if (card == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Agent not found or not A2A-enabled")).build();
        }

        return Response.ok(card).build();
    }

    /**
     * List all A2A-enabled agents.
     */
    @GET
    @Path("a2a/agents")
    public Response listA2AAgents() {
        if (!a2aEnabled) {
            return Response.ok(List.of()).build();
        }

        return Response.ok(agentCardService.listA2AAgents()).build();
    }

    /**
     * JSON-RPC 2.0 endpoint for A2A task operations. Protected by OIDC when
     * authentication is enabled (quarkus.oidc.tenant-enabled=true). GET endpoints
     * (Agent Card discovery) remain public per A2A protocol spec.
     */
    @POST
    @Path("a2a/agents/{agentId}")
    @io.quarkus.security.Authenticated
    public Response handleJsonRpc(@PathParam("agentId") String agentId, JsonRpcRequest request) {
        if (!a2aEnabled) {
            return jsonRpcError(request.id(), A2AModels.ERROR_METHOD_NOT_FOUND, "A2A is disabled");
        }

        if (request == null || request.method() == null) {
            return jsonRpcError(null, A2AModels.ERROR_INVALID_PARAMS, "Invalid JSON-RPC request");
        }

        try {
            return switch (request.method()) {
                case "tasks/send" -> handleTasksSend(agentId, request);
                case "tasks/get" -> handleTasksGet(request);
                case "tasks/cancel" -> handleTasksCancel(request);
                default -> jsonRpcError(request.id(), A2AModels.ERROR_METHOD_NOT_FOUND, "Unknown method: " + request.method());
            };
        } catch (Exception e) {
            LOGGER.errorf("A2A JSON-RPC error for method=%s, agentId=%s: %s", request.method(), agentId, e.getMessage());
            return jsonRpcError(request.id(), A2AModels.ERROR_INTERNAL, e.getMessage());
        }
    }

    // === Method handlers ===

    private Response handleTasksSend(String agentId, JsonRpcRequest request) throws Exception {
        if (request.params() == null) {
            return jsonRpcError(request.id(), A2AModels.ERROR_INVALID_PARAMS, "Missing params");
        }

        A2ATask task = taskHandler.handleTaskSend(agentId, request.params());
        return jsonRpcSuccess(request.id(), task);
    }

    private Response handleTasksGet(JsonRpcRequest request) {
        if (request.params() == null || !request.params().containsKey("id")) {
            return jsonRpcError(request.id(), A2AModels.ERROR_INVALID_PARAMS, "Missing task id");
        }

        String taskId = request.params().get("id").toString();
        A2ATask task = taskHandler.handleTaskGet(taskId);

        if (task == null) {
            return jsonRpcError(request.id(), A2AModels.ERROR_TASK_NOT_FOUND, "Task not found: " + taskId);
        }

        return jsonRpcSuccess(request.id(), task);
    }

    private Response handleTasksCancel(JsonRpcRequest request) {
        if (request.params() == null || !request.params().containsKey("id")) {
            return jsonRpcError(request.id(), A2AModels.ERROR_INVALID_PARAMS, "Missing task id");
        }

        String taskId = request.params().get("id").toString();
        boolean canceled = taskHandler.handleTaskCancel(taskId);

        if (!canceled) {
            return jsonRpcError(request.id(), A2AModels.ERROR_TASK_NOT_CANCELABLE, "Task not found or cannot be canceled");
        }

        return jsonRpcSuccess(request.id(), Map.of("id", taskId, "status", "canceled"));
    }

    // === JSON-RPC response helpers ===

    private Response jsonRpcSuccess(Object id, Object result) {
        return Response.ok(new JsonRpcResponse("2.0", id, result, null)).build();
    }

    private Response jsonRpcError(Object id, int code, String message) {
        return Response.ok(new JsonRpcResponse("2.0", id, null, new JsonRpcError(code, message, null))).build();
    }
}
