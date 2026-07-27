/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.integrations.openai.model.ChatCompletionRequest;
import ai.labs.eddi.integrations.openai.model.ChatCompletionResponse;
import ai.labs.eddi.integrations.openai.model.ChatMessage;
import ai.labs.eddi.integrations.openai.model.Choice;
import ai.labs.eddi.integrations.openai.model.ModelObject;
import ai.labs.eddi.integrations.openai.model.ModelsResponse;
import ai.labs.eddi.integrations.openai.model.OpenAiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * OpenAI-compatible API surface — {@code GET /v1/models} and
 * {@code POST /v1/chat/completions}.
 * <p>
 * Presents deployed EDDI agents as OpenAI "models" so Open WebUI, the Python
 * {@code openai} SDK, LangChain, LiteLLM and anything else speaking the
 * protocol can drive an EDDI conversation without knowing about EDDI.
 * <p>
 * Authentication is handled by {@link OpenAiAuthFilter}, not by
 * {@code @RolesAllowed}: the whole point of this surface is that callers
 * present an OpenAI-style bearer token rather than an EDDI role.
 *
 * @since 6.1.0
 */
@ApplicationScoped
@Path("/v1")
@Tag(name = "OpenAI Compatibility", description = "OpenAI-protocol adapter for Open WebUI and OpenAI SDK clients")
public class RestOpenAiAdapter {

    /** Response header correlating an OpenAI request with an EDDI conversation. */
    public static final String HEADER_CONVERSATION_ID = "X-EDDI-Conversation-Id";

    private final AgentModelResolver modelResolver;
    private final OpenAiConversationBridge bridge;
    private final OpenAiCompatConfig config;
    private final ObjectMapper objectMapper;

    /**
     * Bounds in-flight completions. Each holds a worker thread for the duration of
     * a conversation turn (the bridge blocks on it, as the Slack handler does), so
     * without this an agent that is merely slow would exhaust the pool and take the
     * rest of EDDI's REST surface down with it.
     */
    private final Semaphore inFlight;

    @Inject
    public RestOpenAiAdapter(AgentModelResolver modelResolver,
            OpenAiConversationBridge bridge,
            OpenAiCompatConfig config,
            ObjectMapper objectMapper) {
        this.modelResolver = modelResolver;
        this.bridge = bridge;
        this.config = config;
        this.objectMapper = objectMapper;
        this.inFlight = new Semaphore(Math.max(1, config.getMaxConcurrentRequests()));
    }

    @GET
    @NoCache
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List available models",
               description = "Every agent deployed to the configured environment, as an OpenAI model. "
                       + "Each agent appears twice when stateless variants are enabled: the plain id keeps "
                       + "conversation state, the ':stateless' id runs one throwaway conversation per request.")
    public ModelsResponse listModels() {
        requireEnabled();
        return ModelsResponse.of(modelResolver.listModels());
    }

    @GET
    @NoCache
    @Path("/models/{modelId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Retrieve a model",
               description = "Looks up a single model id. Some clients probe this before starting a conversation.")
    public ModelObject retrieveModel(@PathParam("modelId") String modelId) {
        requireEnabled();
        var resolved = resolveOrFail(modelId);
        // The catalogue carries the creation timestamp; re-deriving it here would
        // mean a second store round-trip for a field no client acts on.
        return ModelObject.of(resolved.modelId(), 0L);
    }

    /**
     * Chat completions — the whole adapter in one method.
     * <p>
     * <b>There is exactly one method for both sync and streaming, dispatching on
     * the {@code stream} field of the body.</b> Content negotiation cannot work
     * here: {@code openai-python} hardcodes {@code Accept: application/json} and
     * never varies it by {@code stream}, and Open WebUI sends no {@code Accept}
     * header at all (it detects streaming from the <em>response</em> content-type).
     * Two {@code @Produces} methods would therefore route every streaming request
     * to the JSON one. The request body is the only reliable dispatch signal, which
     * is also what the OpenAI specification says.
     */
    @POST
    @Path("/chat/completions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({MediaType.APPLICATION_JSON, MediaType.SERVER_SENT_EVENTS})
    @Blocking
    @Operation(summary = "Create a chat completion",
               description = "Sends the last user message to the agent behind the requested model. "
                       + "Set stream=true for an SSE token stream. Prior messages in the array are "
                       + "ignored: EDDI keeps its own conversation memory.")
    public Response chatCompletions(ChatCompletionRequest request,
                                    @Context HttpHeaders httpHeaders,
                                    @Context ContainerRequestContext requestContext) {
        requireEnabled();

        var model = resolveOrFail(request == null ? null : request.model());
        String userId = requireUserId(requestContext);
        Map<String, String> headers = flatten(httpHeaders);

        if (!inFlight.tryAcquire()) {
            throw OpenAiApiException.busy("Too many concurrent requests. Please retry shortly.");
        }
        boolean handedOffToStream = false;
        try {
            var turn = bridge.prepare(model, request, headers, userId);
            String completionId = "chatcmpl-eddi-" + UUID.randomUUID();
            long created = Instant.now().getEpochSecond();

            if (request.isStreaming()) {
                handedOffToStream = true;
                return streamingResponse(turn, completionId, request.model(), created);
            }

            var outcome = bridge.say(turn, model, userId, headers, request);
            var response = ChatCompletionResponse.of(completionId, request.model(), created,
                    new Choice(0, ChatMessage.assistant(outcome.text(), objectMapper), Choice.FINISH_STOP));
            return Response.ok(response)
                    .type(MediaType.APPLICATION_JSON)
                    .header(HEADER_CONVERSATION_ID, turn.conversationId())
                    .build();
        } finally {
            // The streaming body runs after this method returns, so it releases
            // the permit itself once the stream is done.
            if (!handedOffToStream) {
                inFlight.release();
            }
        }
    }

    /**
     * Build the SSE response. {@code type()} is set explicitly so the
     * {@code @Produces} ordering cannot decide it, and {@code X-Accel-Buffering}
     * defeats proxy buffering that would otherwise hold tokens back until the
     * response completed — which looks exactly like a hung agent.
     */
    private Response streamingResponse(OpenAiConversationBridge.PreparedTurn turn,
                                       String completionId, String model, long created) {
        StreamingOutput body = out -> {
            var writer = new OpenAiSseWriter(out, objectMapper, completionId, model, created);
            try {
                bridge.stream(turn, writer);
            } finally {
                inFlight.release();
            }
        };
        return Response.ok(body)
                .type(MediaType.SERVER_SENT_EVENTS)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .header(HEADER_CONVERSATION_ID, turn.conversationId())
                .build();
    }

    /**
     * The userId published by {@link OpenAiAuthFilter}. Absent means the filter did
     * not run (misconfigured provider registration) — refusing is the only safe
     * answer, since a fabricated identity would merge callers into a shared
     * conversation.
     */
    private String requireUserId(ContainerRequestContext requestContext) {
        Object userId = requestContext == null ? null : requestContext.getProperty(OpenAiAuthFilter.PROP_USER_ID);
        if (userId instanceof String value && !value.isBlank()) {
            return value;
        }
        throw OpenAiApiException.unauthorized("Could not determine the calling user.");
    }

    /** The subset of request headers the bridge inspects. */
    private Map<String, String> flatten(HttpHeaders httpHeaders) {
        Map<String, String> headers = new HashMap<>();
        for (String name : List.of(OpenAiAuthFilter.HEADER_CHAT_ID, OpenAiAuthFilter.HEADER_USER_ID)) {
            String value = httpHeaders.getHeaderString(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        return headers;
    }

    /**
     * Resolve a model id, translating both resolver failures into the OpenAI error
     * envelope. An ambiguous match is a 400 rather than a guess: silently routing a
     * conversation to the wrong agent is worse than an error the caller can fix.
     */
    AgentModelResolver.ResolvedModel resolveOrFail(String modelId) {
        try {
            return modelResolver.resolve(modelId);
        } catch (AgentModelResolver.UnknownModelException e) {
            throw OpenAiApiException.notFound(OpenAiErrorResponse.CODE_MODEL_NOT_FOUND, e.getMessage());
        } catch (AgentModelResolver.AmbiguousModelException e) {
            throw OpenAiApiException.badRequest(OpenAiErrorResponse.CODE_AMBIGUOUS_MODEL, e.getMessage());
        }
    }

    /**
     * The adapter ships disabled. When it is off the routes still exist (JAX-RS
     * scanning is build-time), so they must refuse explicitly rather than serve.
     */
    private void requireEnabled() {
        if (!config.isEnabled()) {
            throw OpenAiApiException.notFound(OpenAiErrorResponse.CODE_UNKNOWN_ENDPOINT,
                    "The OpenAI-compatible API is disabled. Set eddi.openai-compat.enabled=true to enable it.");
        }
    }
}
