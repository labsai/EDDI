/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.modules.llm.tools.spi.ToolGroup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grouped form of {@code discover_tools} that LAZY uses for http/mcp/a2a
 * tools: groups in the spec's description, a group or keywords narrowing the
 * result, and {@code maxToolsInContext} capping it.
 */
@DisplayName("DiscoverToolsTool — grouped discovery")
class DiscoverToolsToolGroupedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ToolGroup USERS = new ToolGroup("g-users", "Users: find, invite and deactivate user accounts");
    private static final ToolGroup ORDERS = new ToolGroup("g-orders", null);

    private final List<ToolSpecification> specs = new ArrayList<>();
    private final Map<String, ToolGroup> groups = new LinkedHashMap<>();

    private void tool(String name, String description, ToolGroup group) {
        specs.add(ToolSpecification.builder().name(name).description(description).build());
        groups.put(name, group);
    }

    private DiscoverToolsTool catalog(int max) {
        tool("findUsers", "Find users by name or email", USERS);
        tool("inviteUser", "Invite a new user by email", USERS);
        tool("deactivateUser", "Deactivate a user account", USERS);
        tool("listOrders", "List orders of a customer", ORDERS);
        tool("cancelOrder", "Cancel an order", ORDERS);
        return new DiscoverToolsTool(specs, max, groups);
    }

    private static List<String> names(String result) throws Exception {
        List<String> names = new ArrayList<>();
        for (JsonNode tool : MAPPER.readTree(result).get("tools")) {
            names.add(tool.get("name").asText());
        }
        return names;
    }

    @Test
    @DisplayName("the spec's description lists every group with its summary, or sample tool names without one")
    void specListsGroups() {
        var spec = catalog(20).groupedSpec();

        assertEquals("discover_tools", spec.name());
        assertTrue(spec.description().contains("g-users (3 tools): Users: find, invite and deactivate user accounts"), spec.description());
        assertTrue(spec.description().contains("g-orders (2 tools): tools such as listOrders, cancelOrder"), spec.description());
        assertFalse(spec.description().contains("Find users by name"), "tool descriptions stay out of the spec");
        assertTrue(spec.parameters().properties().containsKey("group"));
        assertTrue(spec.parameters().properties().containsKey("keywords"));
    }

    @Test
    @DisplayName("a group returns its tools only — by id, or by a word of its description")
    void groupNarrows() throws Exception {
        var tool = catalog(20);

        assertEquals(List.of("findUsers", "inviteUser", "deactivateUser"), names(tool.discoverGrouped("g-users", null)));
        assertEquals(List.of("findUsers", "inviteUser", "deactivateUser"), names(tool.discoverGrouped("users", null)));
        assertEquals(List.of("listOrders", "cancelOrder"), names(tool.discoverGrouped("G-ORDERS", null)));
    }

    @Test
    @DisplayName("keywords rank by matches, name before description; a group and keywords combine")
    void keywordsRank() throws Exception {
        var tool = catalog(20);

        assertEquals(List.of("cancelOrder", "listOrders"), names(tool.discoverGrouped(null, "cancel order")).subList(0, 2));
        assertEquals(List.of("inviteUser"), names(tool.discoverGrouped("g-users", "invite")));
    }

    @Test
    @DisplayName("an unknown group, or no argument at all, lists the groups and activates nothing")
    void unknownGroupListsGroups() throws Exception {
        var tool = catalog(20);

        JsonNode unknown = MAPPER.readTree(tool.discoverGrouped("billing", null));
        assertEquals(0, unknown.get("tools").size());
        assertEquals(2, unknown.get("groups").size());
        assertEquals("g-users", unknown.get("groups").get(0).get("group").asText());

        JsonNode empty = MAPPER.readTree(tool.discoverGrouped(null, " "));
        assertEquals(0, empty.get("tools").size());
        assertEquals(2, empty.get("groups").size());
    }

    @Test
    @DisplayName("maxToolsInContext caps one call, and the model is told how to reach the rest")
    void capApplies() throws Exception {
        var tool = catalog(2);

        JsonNode result = MAPPER.readTree(tool.discoverGrouped("g-users", null));
        assertEquals(2, result.get("tools").size());
        assertTrue(result.get("message").asText().contains("3 tools matched"), result.toString());
    }

    @Test
    @DisplayName("the executor reads group and keywords from the call's JSON arguments")
    void executorParsesArguments() throws Exception {
        var executor = catalog(20).groupedExecutor();

        String result = executor.execute(ToolExecutionRequest.builder().name("discover_tools")
                .arguments("{\"group\":\"g-orders\",\"keywords\":\"cancel\"}").build(), null);
        assertEquals(List.of("cancelOrder"), names(result));

        String garbage = executor.execute(ToolExecutionRequest.builder().name("discover_tools").arguments("not json").build(), null);
        assertEquals(2, MAPPER.readTree(garbage).get("groups").size(), "unparseable arguments list the groups");
    }
}
