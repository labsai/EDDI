/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiEndpointDiscoveryRequest;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.mcp.McpApiToolBuilder;
import ai.labs.eddi.modules.apicalls.impl.RequestRedactor;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import ai.labs.eddi.secrets.sanitize.UriRedactor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestApiCallsStore implements IRestApiCallsStore {
    private static final Logger LOGGER = Logger.getLogger(RestApiCallsStore.class);

    private final IApiCallsStore httpCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<ApiCallsConfiguration> restVersionInfo;

    @Inject
    public RestApiCallsStore(IApiCallsStore httpCallsStore, IDocumentDescriptorStore documentDescriptorStore, IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, httpCallsStore, documentDescriptorStore);
        this.httpCallsStore = httpCallsStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(ApiCallsConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readApiCallsDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors(filter, index, limit);
    }

    @Override
    public ApiCallsConfiguration readApiCalls(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateApiCalls(String id, Integer version, ApiCallsConfiguration httpCallsConfiguration) {
        return restVersionInfo.update(id, version, httpCallsConfiguration);
    }

    @Override
    public Response createApiCalls(ApiCallsConfiguration httpCallsConfiguration) {
        return restVersionInfo.create(httpCallsConfiguration);
    }

    @Override
    public Response deleteApiCalls(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response duplicateApiCalls(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        ApiCallsConfiguration httpCallsConfiguration = restVersionInfo.read(id, version);
        return restVersionInfo.create(httpCallsConfiguration);
    }

    /**
     * Values accepted in {@code authHeaderRef} — every one of them a reference the
     * engine resolves at call time, never a credential at rest.
     */
    private static final List<String> ALLOWED_AUTH_REFERENCE_PREFIXES = List.of("${vault:", "${eddivault:", "${vars:", "${caller:");

    @Override
    public Response discoverEndpoints(ApiEndpointDiscoveryRequest request) {
        if (request == null) {
            return badRequest("a request body with a 'specUrl' is required");
        }

        String authHeaderRef = trimToNull(request.authHeaderRef());
        if (authHeaderRef != null && !isReference(authHeaderRef)) {
            return badRequest("authHeaderRef must be a ${vault:…}, ${vars:…} or ${caller:…} reference, not a literal credential. "
                    + "Store the key with POST /secretstore/secrets and reference it here.");
        }

        return discover(request.specUrl(), request.apiBaseUrl(), authHeaderRef);
    }

    @Override
    public Response discoverEndpointsUnauthenticated(String specUrl, String apiBaseUrl, UriInfo uriInfo) {
        String strayCredential = findCredentialQueryParam(uriInfo);
        if (strayCredential != null) {
            return badRequest("'" + strayCredential + "' is no longer accepted as a query parameter — a credential in a URL is logged by every "
                    + "hop before EDDI sees it, and was previously echoed back inside every generated call. Use "
                    + "POST /apicallstore/apicalls/discover-endpoints with a vault reference in authHeaderRef instead.");
        }
        return discover(specUrl, apiBaseUrl, null);
    }

    /**
     * The caller's spec URL, safe to write to a log.
     * <p>
     * {@code LogSanitizer.sanitize} answers a different question — it stops a
     * forged log line — and leaves credential material alone, so
     * {@code https://user:token@host/spec.json} was logged with the token in it.
     * The URL is caller-supplied and reaches the log on every discovery attempt,
     * including the failures, where a URL carrying credentials is most likely.
     */
    private static String forLog(String url) {
        return sanitize(UriRedactor.redactUri(url));
    }

    /**
     * A parser complaint the caller may read.
     * <p>
     * The text is worth returning — it names the part of the spec that failed,
     * which is the whole value of a 400 here — but a parser routinely quotes the
     * offending input back, and the input is a caller-supplied URL that may carry
     * credentials. Redacting keeps the diagnosis and drops the credential.
     */
    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "The OpenAPI spec could not be parsed" : SecretRedactionFilter.redact(message);
    }

    private Response discover(String specUrl, String apiBaseUrl, String authHeaderRef) {
        if (specUrl == null || specUrl.isBlank()) {
            return badRequest("specUrl is required");
        }
        try {
            LOGGER.infof("Discovering API endpoints from OpenAPI spec at '%s'", forLog(specUrl));

            String effectiveBaseUrl = trimToNull(apiBaseUrl);

            McpApiToolBuilder.ApiBuildResult result = McpApiToolBuilder.parseAndBuild(specUrl, null, effectiveBaseUrl, authHeaderRef);

            if (result.configsByGroup().isEmpty()) {
                return Response.ok(Map.of("title", "API", "baseUrl", "", "endpointCount", 0, "groups", Map.of())).build();
            }

            // Extract title + baseUrl from result (avoid re-parsing the spec)
            String baseUrl = result.configsByGroup().values().iterator().next().getTargetServerUrl();

            var response = new LinkedHashMap<String, Object>();
            response.put("title", result.title() != null ? result.title() : "API");
            response.put("baseUrl", baseUrl != null ? baseUrl : "");
            response.put("endpointCount", result.endpointCount());
            response.put("groups", result.configsByGroup());

            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            LOGGER.warnf(e, "Failed to parse OpenAPI spec from '%s'", forLog(specUrl));
            return badRequest(safeMessage(e));
        } catch (Exception e) {
            LOGGER.errorf(e, "Unexpected error discovering endpoints from '%s'", forLog(specUrl));
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to discover endpoints (" + e.getClass().getSimpleName() + ") — see server logs for details"))
                    .build();
        }
    }

    private static boolean isReference(String value) {
        return ALLOWED_AUTH_REFERENCE_PREFIXES.stream().anyMatch(value::startsWith);
    }

    private static String trimToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /** Names a credential-bearing query parameter if the caller sent one. */
    private static String findCredentialQueryParam(UriInfo uriInfo) {
        if (uriInfo == null) {
            return null;
        }
        for (String name : uriInfo.getQueryParameters().keySet()) {
            if (RequestRedactor.isSensitiveHeaderName(name)) {
                return name;
            }
        }
        return null;
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", message)).build();
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return httpCallsStore.getCurrentResourceId(id);
    }
}
