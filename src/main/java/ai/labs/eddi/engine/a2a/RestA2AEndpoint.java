/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.engine.a2a.A2AModels.*;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.security.Authenticated;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * JAX-RS endpoints for the A2A protocol.
 * <ul>
 * <li>{@code GET /.well-known/agent.json} — default Agent Card</li>
 * <li>{@code GET /a2a/agents/{agentId}/agent.json} — per-agent Agent Card</li>
 * <li>{@code POST /a2a/agents/{agentId}} — JSON-RPC 2.0 endpoint</li>
 * <li>{@code GET /a2a/agents} — list all A2A-enabled agents</li>
 * <li>{@code GET /.well-known/capabilities} — public capability discovery</li>
 * <li>{@code GET /.well-known/capabilities/skills} — list all registered
 * skills</li>
 * </ul>
 * <p>
 * <b>Anonymous access.</b> {@code @PermitAll} alone does not make an endpoint
 * reachable without a token: Quarkus evaluates the path policies under
 * {@code quarkus.http.auth.permission.*} <em>before</em> declarative RBAC, and
 * this deployment's catch-all covers {@code /*} with {@code authenticated}. The
 * four {@code @PermitAll} endpoints below are therefore also named in an
 * explicit {@code permit} entry in {@code application.properties}, and the
 * annotation here is only half of that decision.
 * {@code A2aEndpointPermissionsTest} fails if the two halves ever disagree.
 *
 * @author ginccc
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Integrations / A2A Protocol", description = "Agent-to-Agent protocol endpoints")
@ApplicationScoped
public class RestA2AEndpoint {

    private static final Logger LOGGER = Logger.getLogger(RestA2AEndpoint.class);

    /**
     * Fixed body returned for any unexpected failure. A2A peers are remote parties
     * outside this deployment's trust boundary, so the exception text — which can
     * name hosts, stores or credentials — never reaches the wire.
     */
    static final String INTERNAL_ERROR_MESSAGE = "Internal error while processing the request";

    private final AgentCardService agentCardService;
    private final A2ATaskHandler taskHandler;
    private final CapabilityRegistryService capabilityRegistryService;
    private final boolean a2aEnabled;
    private final boolean capabilitiesPublic;

    @Inject
    public RestA2AEndpoint(AgentCardService agentCardService, A2ATaskHandler taskHandler,
            CapabilityRegistryService capabilityRegistryService,
            @ConfigProperty(name = "eddi.a2a.enabled", defaultValue = "true") boolean a2aEnabled,
            @ConfigProperty(name = "eddi.a2a.capabilities.public", defaultValue = "false") boolean capabilitiesPublic) {
        this.agentCardService = agentCardService;
        this.taskHandler = taskHandler;
        this.capabilityRegistryService = capabilityRegistryService;
        this.a2aEnabled = a2aEnabled;
        this.capabilitiesPublic = capabilitiesPublic;
    }

    /**
     * Default Agent Card — returns the first A2A-enabled agent's card.
     * <p>
     * Anonymous by design: a peer discovers an A2A deployment through the
     * well-known URI before it holds any credential for it. Paired with the
     * {@code a2a-agent-card} permission entry.
     */
    @PermitAll
    @GET
    @Path(".well-known/agent.json")
    public Response getDefaultAgentCard() {
        if (!a2aEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        AgentCard card = agentCardService.getDefaultAgentCard();
        if (card == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "No A2A-enabled agents found")).build();
        }

        return Response.ok(card).build();
    }

    /**
     * Per-agent Agent Card.
     * <p>
     * Anonymous by design, for the same reason as the default card, and the apiKey
     * an A2A client may send with it is optional — see
     * {@code A2AToolProviderManager.fetchAgentCard}. Reading a card requires
     * knowing the agent id, so it discloses one agent rather than the roster.
     * Paired with the {@code a2a-agent-card} permission entry.
     */
    @PermitAll
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
     * <p>
     * <b>Authenticated</b>, unlike the two card endpoints above. This is the
     * deployment's whole A2A roster — every agent's name, description, skills and
     * URL — which is strictly more than the skill-name list that sits behind
     * {@code eddi.a2a.capabilities.public}, and no part of the A2A protocol needs
     * it: a peer is given a card URL, it does not enumerate. It carried
     * {@code @PermitAll} until 6.4.0, which never took effect because no permission
     * entry matched the path; the annotation was removed rather than a permit entry
     * added.
     */
    @Authenticated
    @GET
    @Path("a2a/agents")
    public Response listA2AAgents() {
        if (!a2aEnabled) {
            return Response.ok(List.of()).build();
        }

        return Response.ok(agentCardService.listA2AAgents()).build();
    }

    /**
     * Public capability discovery endpoint. Returns agents matching a skill,
     * sanitized (no tenant IDs or private metadata). Gated behind
     * {@code eddi.a2a.capabilities.public} (default {@code false}).
     * <p>
     * Path follows the well-known URI convention. {@code capabilitiesPublic} is the
     * only <em>authorization</em> gate — {@code a2aEnabled} gates it too, but
     * neither of them inspects the caller. While either is {@code false} this
     * answers 404 to authenticated and anonymous callers alike, so the
     * {@code a2a-capabilities} permission entry can permit the path unconditionally
     * without widening anything. While both are {@code true}, anonymous is what
     * "public" means.
     */
    @PermitAll
    @GET
    @Path(".well-known/capabilities")
    @Tag(name = "Integrations / Capability Registry", description = "A2A agent capability discovery")
    @Operation(operationId = "publicSearchCapabilities",
               description = "Public endpoint: find agents matching a skill. Requires eddi.a2a.capabilities.public=true.")
    public Response searchCapabilities(@QueryParam("skill") String skill,
                                       @QueryParam("strategy")
                                       @DefaultValue("highest_confidence") String strategy) {
        if (!a2aEnabled || !capabilitiesPublic) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (skill == null || skill.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Query parameter 'skill' is required")).build();
        }

        List<CapabilityMatch> matches = capabilityRegistryService.findBySkill(skill, strategy);

        // Sanitize: expose only agentId, skill, confidence, attributes
        // (CapabilityMatch already has this shape — no internal fields to strip)
        return Response.ok(matches).build();
    }

    /**
     * Public endpoint listing all registered skill names. Gated behind
     * {@code eddi.a2a.capabilities.public} (default {@code false}) on exactly the
     * same terms as {@link #searchCapabilities(String, String)}.
     */
    @PermitAll
    @GET
    @Path(".well-known/capabilities/skills")
    @Tag(name = "Integrations / Capability Registry", description = "A2A agent capability discovery")
    @Operation(operationId = "publicListSkills",
               description = "Public endpoint: list all registered skill names. Requires eddi.a2a.capabilities.public=true.")
    public Response listCapabilitySkills() {
        if (!a2aEnabled || !capabilitiesPublic) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Set<String> skills = capabilityRegistryService.getAllSkills();
        return Response.ok(skills).build();
    }

    /**
     * JSON-RPC 2.0 endpoint for A2A task operations. Protected by OIDC when
     * authentication is enabled (quarkus.oidc.tenant-enabled=true). Agent Card
     * discovery stays public per the A2A protocol spec; the agent listing does not,
     * and neither does this.
     */
    @POST
    @Path("a2a/agents/{agentId}")
    @Authenticated
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
        } catch (InvalidA2ARequestException e) {
            // Message authored in the A2A layer and about the peer's own request —
            // safe to return, and useful for a legitimate peer to fix its call.
            LOGGER.debugf("A2A invalid request for method=%s, agentId=%s: %s",
                    sanitize(request.method()), sanitize(agentId), sanitize(e.getMessage()));
            return jsonRpcError(request.id(), A2AModels.ERROR_INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            // The peer is an arbitrary remote party: the exception detail stays in the
            // server log, the wire gets a curated, non-revealing message.
            LOGGER.errorf(e, "A2A JSON-RPC error for method=%s, agentId=%s",
                    sanitize(request.method()), sanitize(agentId));
            return jsonRpcError(request.id(), A2AModels.ERROR_INTERNAL, INTERNAL_ERROR_MESSAGE);
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
            // No taskId echo: the id is caller-supplied, the JSON-RPC id already
            // correlates the response, and "unknown" must be indistinguishable from
            // "belongs to a different peer".
            LOGGER.debugf("A2A tasks/get missed for taskId=%s", sanitize(taskId));
            return jsonRpcError(request.id(), A2AModels.ERROR_TASK_NOT_FOUND, "Task not found");
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
            LOGGER.debugf("A2A tasks/cancel refused for taskId=%s", sanitize(taskId));
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
