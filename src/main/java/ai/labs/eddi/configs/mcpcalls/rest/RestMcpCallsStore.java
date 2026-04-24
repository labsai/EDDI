/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
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
        return restVersionInfo.readDescriptors("ai.labs.mcpcalls", filter, index, limit);
    }

    @Override
    public McpCallsConfiguration readMcpCalls(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateMcpCalls(String id, Integer version, McpCallsConfiguration mcpCallsConfiguration) {
        return restVersionInfo.update(id, version, mcpCallsConfiguration);
    }

    @Override
    public Response createMcpCalls(McpCallsConfiguration mcpCallsConfiguration) {
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

    @Override
    public Response discoverTools(String url, String transport, String apiKey) {
        if (url == null || url.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "url parameter is required")).build();
        }

        try {
            LOGGER.infof("Discovering tools from MCP server at '%s'", url);

            // Build a temporary McpServerConfig for probing
            McpServerConfig tempConfig = new McpServerConfig();
            tempConfig.setUrl(url);
            tempConfig.setTransport(transport != null ? transport : "http");
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
            return Response.status(Response.Status.BAD_GATEWAY).entity(Map.of("error", "Failed to connect to MCP server: " + e.getMessage())).build();
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
