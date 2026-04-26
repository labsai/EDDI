/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.model;

import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.apicalls.model.PreRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for McpCall model — defaults and round-trip getters/setters.
 */
class McpCallTest {

    @Test
    @DisplayName("defaults — saveResponse is true, everything else null")
    void defaults() {
        var call = new McpCall();
        assertNull(call.getName());
        assertNull(call.getDescription());
        assertNull(call.getActions());
        assertNull(call.getToolName());
        assertNull(call.getToolArguments());
        assertNull(call.getPreRequest());
        assertTrue(call.getSaveResponse());
        assertNull(call.getResponseObjectName());
        assertNull(call.getPostResponse());
    }

    @Test
    @DisplayName("round-trip all fields")
    void roundTrip() {
        var call = new McpCall();
        call.setName("fetch-repos");
        call.setDescription("Fetches GitHub repos");
        call.setActions(List.of("lookup_repos"));
        call.setToolName("github_list_repos");
        call.setToolArguments(Map.of("org", "labsai"));
        call.setSaveResponse(false);
        call.setResponseObjectName("repos");

        assertEquals("fetch-repos", call.getName());
        assertEquals("Fetches GitHub repos", call.getDescription());
        assertEquals(List.of("lookup_repos"), call.getActions());
        assertEquals("github_list_repos", call.getToolName());
        assertEquals(Map.of("org", "labsai"), call.getToolArguments());
        assertFalse(call.getSaveResponse());
        assertEquals("repos", call.getResponseObjectName());
    }

    @Test
    @DisplayName("preRequest and postResponse setters")
    void prePostSetters() {
        var call = new McpCall();
        var pre = new PreRequest();
        var post = new PostResponse();
        call.setPreRequest(pre);
        call.setPostResponse(post);

        assertSame(pre, call.getPreRequest());
        assertSame(post, call.getPostResponse());
    }
}
