/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpCallsModelsTest {

    // ==================== McpCallsConfiguration ====================

    @Test
    void mcpCallsConfiguration_defaults() {
        var config = new McpCallsConfiguration();
        assertNull(config.getMcpServerUrl());
        assertNull(config.getName());
        assertEquals("http", config.getTransport());
        assertNull(config.getApiKey());
        assertEquals(30000L, config.getTimeoutMs());
        assertNull(config.getToolsWhitelist());
        assertNull(config.getToolsBlacklist());
        assertNull(config.getMcpCalls());
    }

    @Test
    void mcpCallsConfiguration_settersAndGetters() {
        var config = new McpCallsConfiguration();
        config.setMcpServerUrl("http://localhost:7070/mcp");
        config.setName("GitHub MCP");
        config.setTransport("sse");
        config.setApiKey("${eddivault:github-key}");
        config.setTimeoutMs(60000L);
        config.setToolsWhitelist(List.of("list_repos", "create_issue"));
        config.setToolsBlacklist(List.of("delete_repo"));

        var call = new McpCall();
        call.setToolName("list_repos");
        config.setMcpCalls(List.of(call));

        assertEquals("http://localhost:7070/mcp", config.getMcpServerUrl());
        assertEquals("GitHub MCP", config.getName());
        assertEquals("sse", config.getTransport());
        assertEquals("${eddivault:github-key}", config.getApiKey());
        assertEquals(60000L, config.getTimeoutMs());
        assertEquals(2, config.getToolsWhitelist().size());
        assertEquals(1, config.getToolsBlacklist().size());
        assertEquals(1, config.getMcpCalls().size());
    }

    @Test
    void mcpCallsConfiguration_jacksonRoundTrip() throws Exception {
        var config = new McpCallsConfiguration();
        config.setMcpServerUrl("http://localhost:7070/mcp");
        config.setTransport("http");
        config.setTimeoutMs(5000L);

        var mapper = new ObjectMapper();
        var json = mapper.writeValueAsString(config);
        var deserialized = mapper.readValue(json, McpCallsConfiguration.class);

        assertEquals("http://localhost:7070/mcp", deserialized.getMcpServerUrl());
        assertEquals("http", deserialized.getTransport());
        assertEquals(5000L, deserialized.getTimeoutMs());
    }

    // ==================== McpCall ====================

    @Test
    void mcpCall_defaults() {
        var call = new McpCall();
        assertNull(call.getName());
        assertNull(call.getDescription());
        assertNull(call.getActions());
        assertNull(call.getToolName());
        assertNull(call.getToolArguments());
        assertNull(call.getPreRequest());
        assertTrue(call.getSaveResponse()); // default true
        assertNull(call.getResponseObjectName());
        assertNull(call.getPostResponse());
    }

    @Test
    void mcpCall_settersAndGetters() {
        var call = new McpCall();
        call.setName("List Repos");
        call.setDescription("Lists all GitHub repositories");
        call.setActions(List.of("list_repos", "get_repos"));
        call.setToolName("list_repos");
        call.setToolArguments(Map.of("owner", "{{properties.githubUser}}"));
        call.setSaveResponse(false);
        call.setResponseObjectName("repos");

        assertEquals("List Repos", call.getName());
        assertEquals("Lists all GitHub repositories", call.getDescription());
        assertEquals(2, call.getActions().size());
        assertEquals("list_repos", call.getToolName());
        assertEquals(1, call.getToolArguments().size());
        assertFalse(call.getSaveResponse());
        assertEquals("repos", call.getResponseObjectName());
    }

    @Test
    void mcpCall_jacksonRoundTrip() throws Exception {
        var call = new McpCall();
        call.setName("Get Weather");
        call.setToolName("get_weather");
        call.setActions(List.of("weather_check"));
        call.setToolArguments(Map.of("city", "Berlin"));
        call.setSaveResponse(true);
        call.setResponseObjectName("weatherData");

        var mapper = new ObjectMapper();
        var json = mapper.writeValueAsString(call);
        var deserialized = mapper.readValue(json, McpCall.class);

        assertEquals("Get Weather", deserialized.getName());
        assertEquals("get_weather", deserialized.getToolName());
        assertEquals("Berlin", deserialized.getToolArguments().get("city"));
        assertEquals("weatherData", deserialized.getResponseObjectName());
    }
}
