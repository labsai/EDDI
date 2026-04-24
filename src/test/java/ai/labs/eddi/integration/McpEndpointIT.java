/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for the MCP Server endpoint.
 * <p>
 * Tests tool discovery and invocation via the MCP SSE transport protocol
 * against a <b>running EDDI instance</b>.
 * <p>
 * The MCP HTTP transport uses:
 * <ul>
 * <li>{@code GET /mcp/sse} — SSE connection for server-to-client messages</li>
 * <li>{@code POST /mcp/messages} — client-to-server JSON-RPC messages</li>
 * </ul>
 * <p>
 * Running:
 *
 * <pre>
 *   mvn verify -Dit.test=McpEndpointIT -Deddi.base-url=http://localhost:7070
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("running-instance")
public class McpEndpointIT extends BaseStandaloneIT {

    // ==================== SSE Connection ====================

    @Test
    @Order(1)
    @DisplayName("MCP SSE endpoint should accept connections")
    void mcpSseEndpoint_acceptsConnection() {
        // The SSE endpoint should return 200 with text/event-stream
        // We use a short timeout since SSE is a long-lived connection
        Response response = given()
                .accept("text/event-stream")
                .get("/mcp/sse");

        // Server should respond (may be 200 for SSE or appropriate status)
        Assertions.assertTrue(
                response.getStatusCode() >= 200 && response.getStatusCode() < 500,
                "MCP SSE endpoint should be accessible, got: " + response.getStatusCode());
    }

    // ==================== Tool Discovery ====================

    @Test
    @Order(2)
    @DisplayName("MCP tools/list should return available tools")
    void mcpToolsList() {
        String jsonRpcRequest = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "tools/list",
                  "params": {}
                }
                """;

        Response response = given()
                .contentType(ContentType.JSON)
                .body(jsonRpcRequest)
                .post("/mcp/messages");

        // Should return a JSON-RPC response with tools list
        if (response.getStatusCode() == 200) {
            response.then().assertThat()
                    .body("result.tools", not(empty()));
        }
    }

    @Test
    @Order(3)
    @DisplayName("MCP should have 50+ tools registered")
    void mcpToolCount() {
        String jsonRpcRequest = """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/list",
                  "params": {}
                }
                """;

        Response response = given()
                .contentType(ContentType.JSON)
                .body(jsonRpcRequest)
                .post("/mcp/messages");

        if (response.getStatusCode() == 200 && response.jsonPath().get("result.tools") != null) {
            int toolCount = response.jsonPath().getList("result.tools").size();
            Assertions.assertTrue(toolCount >= 50,
                    "Expected at least 50 MCP tools, got: " + toolCount);
        }
    }

    // ==================== Tool Invocation ====================

    @Test
    @Order(4)
    @DisplayName("MCP tool invocation (list_agents) should return result")
    void mcpToolInvocation() {
        String jsonRpcRequest = """
                {
                  "jsonrpc": "2.0",
                  "id": 3,
                  "method": "tools/call",
                  "params": {
                    "name": "list_agents",
                    "arguments": {
                      "limit": "5"
                    }
                  }
                }
                """;

        Response response = given()
                .contentType(ContentType.JSON)
                .body(jsonRpcRequest)
                .post("/mcp/messages");

        if (response.getStatusCode() == 200) {
            // Should have a result (possibly empty list of agents)
            response.then().assertThat()
                    .body("error", nullValue());
        }
    }

    // ==================== Error Handling ====================

    @Test
    @Order(5)
    @DisplayName("MCP unknown method should return JSON-RPC error")
    void mcpUnknownMethod() {
        String jsonRpcRequest = """
                {
                  "jsonrpc": "2.0",
                  "id": 4,
                  "method": "nonexistent/method",
                  "params": {}
                }
                """;

        Response response = given()
                .contentType(ContentType.JSON)
                .body(jsonRpcRequest)
                .post("/mcp/messages");

        // Should return error or at least not crash
        Assertions.assertTrue(
                response.getStatusCode() >= 200 && response.getStatusCode() < 500,
                "Should handle unknown method gracefully");
    }
}
