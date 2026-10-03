/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.engine.a2a.A2AModels.A2ABusyException;
import ai.labs.eddi.engine.a2a.A2AModels.A2ATask;
import ai.labs.eddi.engine.a2a.A2AModels.AgentCard;
import ai.labs.eddi.engine.a2a.A2AModels.Artifact;
import ai.labs.eddi.engine.a2a.A2AModels.Dialect;
import ai.labs.eddi.engine.a2a.A2AModels.InvalidA2ARequestException;
import ai.labs.eddi.engine.a2a.A2AModels.JsonRpcError;
import ai.labs.eddi.engine.a2a.A2AModels.JsonRpcRequest;
import ai.labs.eddi.engine.a2a.A2AModels.JsonRpcResponse;
import ai.labs.eddi.engine.a2a.A2AModels.Part;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.CancelOutcomeAndTask;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.ChunkEvent;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.FinalEvent;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.StreamEvent;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.TaskEvent;
import ai.labs.eddi.engine.a2a.A2AWireFormat.MethodRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * JAX-RS endpoints for the A2A protocol.
 * <ul>
 * <li>{@code GET /.well-known/agent-card.json} — default Agent Card (the A2A
 * 0.3+ well-known path); {@code /.well-known/agent.json} serves the same card
 * for older peers</li>
 * <li>{@code GET /a2a/agents/{agentId}/.well-known/agent-card.json} and
 * {@code GET /a2a/agents/{agentId}/agent.json} — per-agent Agent Card</li>
 * <li>{@code POST /a2a/agents/{agentId}} — JSON-RPC 2.0 endpoint (A2A 1.0, 0.3
 * and the pre-0.2 {@code tasks/send})</li>
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
 * {@code @PermitAll} endpoints below are therefore also named in an explicit
 * {@code permit} entry in {@code application.properties}, and the annotation
 * here is only half of that decision. {@code A2aEndpointPermissionsTest} fails
 * if the two halves ever disagree.
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
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Message of the JSON-RPC error a peer gets when every in-flight slot is taken.
     */
    static final String BUSY_MESSAGE = "Server busy: too many concurrent A2A requests. Retry shortly.";

    /** Seconds a refused peer is told to wait, in {@code Retry-After}. */
    static final int BUSY_RETRY_AFTER_SECONDS = 1;

    @Inject
    public RestA2AEndpoint(AgentCardService agentCardService, A2ATaskHandler taskHandler,
            CapabilityRegistryService capabilityRegistryService,
            @ConfigProperty(name = "eddi.a2a.enabled", defaultValue = "true") boolean a2aEnabled,
            @ConfigProperty(name = "eddi.a2a.capabilities.public", defaultValue = "false") boolean capabilitiesPublic,
            ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.agentCardService = agentCardService;
        this.taskHandler = taskHandler;
        this.capabilityRegistryService = capabilityRegistryService;
        this.a2aEnabled = a2aEnabled;
        this.capabilitiesPublic = capabilitiesPublic;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
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
    @Path(".well-known/agent-card.json")
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
     * The default Agent Card at its pre-0.3 well-known path, kept for peers that
     * still look there. Same card, same rules as {@link #getDefaultAgentCard()}.
     */
    @PermitAll
    @GET
    @Path(".well-known/agent.json")
    public Response getDefaultAgentCardLegacyPath() {
        return getDefaultAgentCard();
    }

    /**
     * Per-agent Agent Card at the well-known path below the agent's own URL — where
     * an A2A SDK client given {@code .../a2a/agents/{agentId}} as its base URL
     * looks for it.
     */
    @PermitAll
    @GET
    @Path("a2a/agents/{agentId}/.well-known/agent-card.json")
    public Response getAgentCardWellKnown(@PathParam("agentId") String agentId) {
        return getAgentCard(agentId);
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
     * <p>
     * Beyond authentication it requires a real EDDI role: conversing with an agent
     * is the same capability {@code /agents/{id}/start} gates, so a role-less realm
     * user must not be able to drive any A2A agent merely by holding a valid token.
     * EDDI has no dedicated peer role, so the standard user tier is reused.
     * <p>
     * The dialect is decided by the method name — {@code SendMessage} (1.0),
     * {@code message/send} (0.3), {@code tasks/send} (pre-0.2) — and the answer is
     * written in the same dialect. Streaming methods answer with
     * {@code text/event-stream}, everything else with JSON. While A2A is disabled
     * every call answers HTTP 200 with a JSON-RPC "method not found" error.
     */
    @POST
    @Path("a2a/agents/{agentId}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.SERVER_SENT_EVENTS})
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user"})
    public Response handleJsonRpc(@PathParam("agentId") String agentId, @HeaderParam(A2AModels.VERSION_HEADER) String protocolVersion,
                                  JsonRpcRequest request) {
        if (request == null || request.method() == null) {
            count("invalid", "none", "error");
            return jsonRpcError(request == null ? null : request.id(), A2AModels.ERROR_INVALID_REQUEST, "Invalid JSON-RPC request");
        }
        if (!a2aEnabled) {
            count("disabled", "none", "error");
            return jsonRpcError(request.id(), A2AModels.ERROR_METHOD_NOT_FOUND, "A2A is disabled");
        }

        MethodRef method = A2AWireFormat.resolveMethod(request.method());
        if (method == null) {
            count("unknown", "none", "error");
            return jsonRpcError(request.id(), A2AModels.ERROR_METHOD_NOT_FOUND, "Unknown method: " + request.method());
        }
        String operation = method.operation().name().toLowerCase(Locale.ROOT);
        String dialect = dialectTag(method.dialect());

        if (!isSupportedVersion(protocolVersion)) {
            count(operation, dialect, "error");
            return jsonRpcError(request.id(), A2AModels.ERROR_VERSION_NOT_SUPPORTED,
                    "A2A protocol version not supported (supported: " + A2AModels.PROTOCOL_VERSION + ", "
                            + A2AModels.LEGACY_PROTOCOL_VERSION + ")");
        }

        try {
            Response response = switch (method.operation()) {
                case SEND -> jsonRpcSuccess(request.id(), A2AWireFormat.sendResult(
                        taskHandler.send(agentId, A2AWireFormat.parseSend(request.params(), method.dialect())), method.dialect()));
                case STREAM -> stream(agentId, request, method.dialect());
                case GET -> handleTasksGet(request, method.dialect());
                case CANCEL -> handleTasksCancel(request, method.dialect());
                case PUSH_NOTIFICATIONS -> jsonRpcError(request.id(), A2AModels.ERROR_PUSH_NOTIFICATION_NOT_SUPPORTED,
                        "Push notifications are not supported");
                case EXTENDED_CARD -> jsonRpcError(request.id(), A2AModels.ERROR_EXTENDED_CARD_NOT_CONFIGURED,
                        "No extended Agent Card is configured");
                case UNSUPPORTED -> jsonRpcError(request.id(), A2AModels.ERROR_UNSUPPORTED_OPERATION,
                        "Method not supported: " + request.method());
            };
            count(operation, dialect, response.getEntity() instanceof JsonRpcResponse rpc && rpc.error() != null ? "error" : "ok");
            return response;
        } catch (InvalidA2ARequestException e) {
            // Message authored in the A2A layer and about the peer's own request —
            // safe to return, and useful for a legitimate peer to fix its call.
            LOGGER.debugf("A2A invalid request for method=%s, agentId=%s: %s",
                    sanitize(request.method()), sanitize(agentId), sanitize(e.getMessage()));
            count(operation, dialect, "invalid");
            return jsonRpcError(request.id(), e.getCode(), e.getMessage());
        } catch (A2ABusyException e) {
            LOGGER.debugf("A2A request refused, every in-flight slot is taken: method=%s, agentId=%s",
                    sanitize(request.method()), sanitize(agentId));
            count(operation, dialect, "busy");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .header("Retry-After", String.valueOf(BUSY_RETRY_AFTER_SECONDS))
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new JsonRpcResponse("2.0", request.id(), null,
                            new JsonRpcError(A2AModels.ERROR_INTERNAL, BUSY_MESSAGE, null)))
                    .build();
        } catch (Exception e) {
            // The peer is an arbitrary remote party: the exception detail stays in the
            // server log, the wire gets a curated, non-revealing message.
            LOGGER.errorf(e, "A2A JSON-RPC error for method=%s, agentId=%s",
                    sanitize(request.method()), sanitize(agentId));
            count(operation, dialect, "error");
            return jsonRpcError(request.id(), A2AModels.ERROR_INTERNAL, INTERNAL_ERROR_MESSAGE);
        }
    }

    /**
     * An absent header is a 0.3 client (A2A 1.0 says so); any 0.x or 1.x value is
     * served, anything else is refused with VersionNotSupportedError.
     */
    static boolean isSupportedVersion(String version) {
        if (version == null || version.isBlank()) {
            return true;
        }
        String trimmed = version.trim();
        return trimmed.equals("1") || trimmed.startsWith("1.") || trimmed.startsWith("0.");
    }

    private static String dialectTag(Dialect dialect) {
        return switch (dialect) {
            case V1_0 -> "1.0";
            case V0_3 -> "0.3";
            case LEGACY -> "legacy";
        };
    }

    private void count(String method, String dialect, String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.a2a.requests", "method", method, "dialect", dialect, "outcome", outcome).increment();
        }
    }

    // === Method handlers ===

    private Response handleTasksGet(JsonRpcRequest request, Dialect dialect) {
        String taskId = A2AWireFormat.requireTaskId(request.params());
        A2ATask task = taskHandler.get(taskId, A2AWireFormat.historyLength(request.params()));

        if (task == null) {
            // No taskId echo: the id is caller-supplied, the JSON-RPC id already
            // correlates the response, and "unknown" must be indistinguishable from
            // "belongs to a different peer".
            LOGGER.debugf("A2A tasks/get missed for taskId=%s", sanitize(taskId));
            return jsonRpcError(request.id(), A2AModels.ERROR_TASK_NOT_FOUND, "Task not found");
        }

        return jsonRpcSuccess(request.id(), A2AWireFormat.task(task, dialect));
    }

    private Response handleTasksCancel(JsonRpcRequest request, Dialect dialect) {
        String taskId = A2AWireFormat.requireTaskId(request.params());
        CancelOutcomeAndTask outcome = taskHandler.cancel(taskId);

        return switch (outcome.result()) {
            case CANCELED -> jsonRpcSuccess(request.id(), A2AWireFormat.task(outcome.task(), dialect));
            case NOT_FOUND -> {
                LOGGER.debugf("A2A tasks/cancel missed for taskId=%s", sanitize(taskId));
                yield jsonRpcError(request.id(), A2AModels.ERROR_TASK_NOT_FOUND, "Task not found");
            }
            case NOT_CANCELABLE -> {
                LOGGER.debugf("A2A tasks/cancel refused for taskId=%s", sanitize(taskId));
                yield jsonRpcError(request.id(), A2AModels.ERROR_TASK_NOT_CANCELABLE, "Task cannot be canceled: it has already finished");
            }
        };
    }

    /**
     * A streamed send. The task is accepted — and anything that can refuse it is
     * refused, as an ordinary JSON-RPC error — before the response starts; the body
     * then relays the turn's events as Server-Sent Events, each one a JSON-RPC
     * response carrying the request's id.
     * <p>
     * The body only drains a queue the turn fills. If it never runs — a peer that
     * disconnects first — the turn still settles and releases its slot on its own.
     */
    private Response stream(String agentId, JsonRpcRequest request, Dialect dialect) throws Exception {
        var send = A2AWireFormat.parseSend(request.params(), dialect);
        BlockingQueue<StreamEvent> events = new LinkedBlockingQueue<>();
        taskHandler.stream(agentId, send, events::add);
        long waitMillis = TimeUnit.SECONDS.toMillis((long) taskHandler.taskTimeoutSeconds() + A2ATaskHandler.SLOT_LEASE_GRACE_SECONDS);
        Object rpcId = request.id();

        StreamingOutput body = out -> drain(out, events, rpcId, dialect, waitMillis);
        return Response.ok(body)
                .type(MediaType.SERVER_SENT_EVENTS)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .build();
    }

    /**
     * Writes stream events until the final one, or until {@code waitMillis} has
     * passed.
     */
    void drain(OutputStream out, BlockingQueue<StreamEvent> events, Object rpcId, Dialect dialect, long waitMillis) throws IOException {
        drain(out, events, rpcId, dialect, waitMillis, KEEPALIVE_INTERVAL_MILLIS);
    }

    /**
     * How often an idle stream writes an SSE comment. A turn can run for minutes
     * without a token (a tool loop, a model cascade), and a proxy that sees no
     * bytes for its idle timeout drops the connection before the final status
     * arrives. SSE clients ignore comment lines.
     */
    static final long KEEPALIVE_INTERVAL_MILLIS = 15_000;

    void drain(OutputStream out, BlockingQueue<StreamEvent> events, Object rpcId, Dialect dialect, long waitMillis, long keepaliveMillis)
            throws IOException {
        long deadline = System.currentTimeMillis() + waitMillis;
        A2ATask lastSeen = null;
        try {
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                StreamEvent event = remaining > 0 ? events.poll(Math.min(remaining, keepaliveMillis), TimeUnit.MILLISECONDS) : null;
                if (event == null && remaining > keepaliveMillis) {
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                if (event == null) {
                    // The turn outlived the stream: end it on the last known state; the
                    // peer can poll the task.
                    if (lastSeen != null) {
                        writeEvent(out, rpcId, A2AWireFormat.statusUpdate(lastSeen, true, dialect));
                    }
                    return;
                }
                switch (event) {
                    case TaskEvent taskEvent -> {
                        lastSeen = taskEvent.task();
                        writeEvent(out, rpcId, A2AWireFormat.streamTask(taskEvent.task(), dialect));
                    }
                    case ChunkEvent chunk -> writeEvent(out, rpcId, A2AWireFormat.artifactUpdate(chunk.task(),
                            new Artifact(A2ATaskHandler.RESPONSE_ARTIFACT_ID, A2ATaskHandler.RESPONSE_ARTIFACT_ID,
                                    List.of(Part.textPart(chunk.text()))),
                            chunk.append(), false, dialect));
                    case FinalEvent finalEvent -> {
                        A2ATask task = finalEvent.task();
                        // The settled answer replaces whatever was streamed: tokens are a
                        // preview, the turn's output is the result.
                        if (task.artifacts() != null) {
                            for (Artifact artifact : task.artifacts()) {
                                writeEvent(out, rpcId, A2AWireFormat.artifactUpdate(task, artifact, false, true, dialect));
                            }
                        }
                        writeEvent(out, rpcId, A2AWireFormat.statusUpdate(task, true, dialect));
                        return;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeEvent(OutputStream out, Object rpcId, Object result) throws IOException {
        String json = objectMapper.writeValueAsString(new JsonRpcResponse("2.0", rpcId, result, null));
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // === JSON-RPC response helpers ===

    private Response jsonRpcSuccess(Object id, Object result) {
        return Response.ok(new JsonRpcResponse("2.0", id, result, null)).type(MediaType.APPLICATION_JSON).build();
    }

    private Response jsonRpcError(Object id, int code, String message) {
        return Response.ok(new JsonRpcResponse("2.0", id, null, new JsonRpcError(code, message, null))).type(MediaType.APPLICATION_JSON)
                .build();
    }
}
