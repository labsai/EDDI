/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.integrations.openai.model.ModelObject;
import ai.labs.eddi.integrations.openai.model.ModelsResponse;
import ai.labs.eddi.integrations.openai.model.OpenAiErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

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

    private final AgentModelResolver modelResolver;
    private final OpenAiCompatConfig config;

    @Inject
    public RestOpenAiAdapter(AgentModelResolver modelResolver, OpenAiCompatConfig config) {
        this.modelResolver = modelResolver;
        this.config = config;
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
