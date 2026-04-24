/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.mcp.McpApiToolBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return restVersionInfo.readDescriptors("ai.labs.httpcalls", filter, index, limit);
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

    @Override
    public Response discoverEndpoints(String specUrl, String apiBaseUrl, String apiAuth) {
        if (specUrl == null || specUrl.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "specUrl query parameter is required")).build();
        }
        try {
            LOGGER.infof("Discovering API endpoints from OpenAPI spec at '%s'", specUrl);

            String effectiveBaseUrl = (apiBaseUrl != null && !apiBaseUrl.isBlank()) ? apiBaseUrl : null;
            String effectiveAuth = (apiAuth != null && !apiAuth.isBlank()) ? apiAuth : null;

            McpApiToolBuilder.ApiBuildResult result = McpApiToolBuilder.parseAndBuild(specUrl, null, effectiveBaseUrl, effectiveAuth);

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
            LOGGER.warnf(e, "Failed to parse OpenAPI spec from '%s'", specUrl);
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            LOGGER.errorf(e, "Unexpected error discovering endpoints from '%s'", specUrl);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Failed to discover endpoints: " + e.getMessage()))
                    .build();
        }
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
