/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.mcpcalls.model.McpToolDiscoveryRequest;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.modules.apicalls.impl.RequestRedactor;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * REST implementation for MCP Calls configuration store.
 */
@ApplicationScoped
public class RestMcpCallsStore implements IRestMcpCallsStore {

    private static final Logger LOGGER = Logger.getLogger(RestMcpCallsStore.class);

    private final IMcpCallsStore mcpCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final McpToolProviderManager mcpToolProviderManager;
    private final RestVersionInfo<McpCallsConfiguration> restVersionInfo;

    @Inject
    public RestMcpCallsStore(IMcpCallsStore mcpCallsStore, IDocumentDescriptorStore documentDescriptorStore, IJsonSchemaCreator jsonSchemaCreator,
            McpToolProviderManager mcpToolProviderManager) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, mcpCallsStore, documentDescriptorStore);
        this.mcpCallsStore = mcpCallsStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        this.mcpToolProviderManager = mcpToolProviderManager;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(McpCallsConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readMcpCallsDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors(filter, index, limit);
    }

    @Override
    public McpCallsConfiguration readMcpCalls(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateMcpCalls(String id, Integer version, McpCallsConfiguration mcpCallsConfiguration) {
        validateForWrite(mcpCallsConfiguration);
        return restVersionInfo.update(id, version, mcpCallsConfiguration);
    }

    @Override
    public Response createMcpCalls(McpCallsConfiguration mcpCallsConfiguration) {
        validateForWrite(mcpCallsConfiguration);
        return restVersionInfo.create(mcpCallsConfiguration);
    }

    @Override
    public Response deleteMcpCalls(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response duplicateMcpCalls(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        McpCallsConfiguration config = restVersionInfo.read(id, version);
        return restVersionInfo.create(config);
    }

    /**
     * Rejects a configuration the engine cannot honour, at the only boundary where
     * rejecting is safe.
     * <p>
     * {@code McpCallsTask.configure()} deliberately only logs a validation failure:
     * it runs at workflow load, where throwing would make an already-stored
     * configuration take down the whole workflow — configs already in MongoDB are
     * the one backward-compatibility contract that matters. That leniency is only
     * defensible if the strict check lives here, on the way in; otherwise an
     * unusable MCP server is accepted with 201 and fails much later, at connect
     * time, far from the person who typed the URL.
     * <p>
     * {@code duplicateMcpCalls} deliberately does <em>not</em> call this: it copies
     * an already-stored config, and refusing to duplicate a document the store is
     * happy to serve would be a new failure mode rather than a guard.
     */
    private void validateForWrite(McpCallsConfiguration mcpCallsConfiguration) {
        if (mcpCallsConfiguration == null) {
            // RestVersionInfo produces its own error for a missing body.
            return;
        }

        try {
            mcpCallsConfiguration.validate();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    @Override
    public Response discoverTools(McpToolDiscoveryRequest request, String apiKey) {
        if (request == null) {
            return badRequest("a request body with a 'url' is required");
        }
        return probe(request.url(), request.transport(), apiKey);
    }

    @Override
    public Response discoverToolsUnauthenticated(String url, String transport, UriInfo uriInfo) {
        String strayCredential = findCredentialQueryParam(uriInfo);
        if (strayCredential != null) {
            return badRequest("'" + strayCredential + "' is no longer accepted as a query parameter — a credential in a URL is logged by "
                    + "every hop before EDDI sees it. Use POST /mcpcallsstore/mcpcalls/discover-tools with the "
                    + MCP_AUTHORIZATION_HEADER + " header instead.");
        }
        return probe(url, transport, null);
    }

    /**
     * Names a credential-bearing query parameter if the caller sent one.
     * <p>
     * Checked against every parameter present rather than a fixed name, because a
     * client that has not migrated may have been passing any of the historical
     * spellings, and the point is to tell them once rather than to enumerate.
     */
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

    /**
     * Connects to an MCP server and lists its tools.
     * <p>
     * The failure branch reports the exception TYPE and not its message. The
     * message from a failed outbound connection routinely contains the resolved
     * URL, and with a credential templated into it that URL is the credential;
     * handing it back to the HTTP caller turned a connection error into a
     * disclosure. The full throwable still reaches the log, which is where an
     * operator debugging a bad URL should be looking anyway. Same discipline as
     * {@code HttpCallToolsProvider}.
     */
    private Response probe(String url, String transport, String apiKey) {
        if (url == null || url.isBlank()) {
            return badRequest("url is required");
        }

        try {
            LOGGER.infof("Discovering tools from MCP server at '%s'", url);

            // Build a temporary McpServerConfig for probing
            McpServerConfig tempConfig = new McpServerConfig();
            tempConfig.setUrl(url);
            tempConfig.setTransport(transport != null && !transport.isBlank() ? transport : "http");
            tempConfig.setName("discovery-probe");
            if (apiKey != null && !apiKey.isBlank()) {
                tempConfig.setApiKey(apiKey);
            }

            McpToolProviderManager.McpToolsResult result = mcpToolProviderManager.discoverTools(List.of(tempConfig));

            // Convert to a simple JSON-serializable list of tool info
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolSpecification spec : result.toolSpecs()) {
                Map<String, Object> toolInfo = new HashMap<>();
                toolInfo.put("name", spec.name());
                toolInfo.put("description", spec.description());
                if (spec.parameters() != null) {
                    toolInfo.put("parameters", spec.parameters());
                }
                tools.add(toolInfo);
            }

            return Response.ok(Map.of("tools", tools, "count", tools.size())).build();

        } catch (Exception e) {
            LOGGER.warnf(e, "Failed to discover tools from MCP server at '%s'", url);
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("error", "Failed to connect to MCP server (" + e.getClass().getSimpleName() + ") — see server logs for details"))
                    .build();
        }
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return mcpCallsStore.getCurrentResourceId(id);
    }
}
