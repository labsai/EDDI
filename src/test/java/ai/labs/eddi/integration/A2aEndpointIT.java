package ai.labs.eddi.integration;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for the A2A (Agent-to-Agent) protocol endpoints.
 * <p>
 * Tests Agent Card discovery and JSON-RPC task operations against a <b>running
 * EDDI instance</b>.
 * <p>
 * Endpoints:
 * <ul>
 * <li>{@code GET /.well-known/agent.json} — default Agent Card</li>
 * <li>{@code GET /a2a/agents} — list all A2A-enabled agents</li>
 * <li>{@code GET /a2a/agents/{agentId}/agent.json} — per-agent card</li>
 * <li>{@code POST /a2a/agents/{agentId}} — JSON-RPC 2.0 endpoint</li>
 * </ul>
 * <p>
 * Running:
 *
 * <pre>
 *   mvn verify -Dit.test=A2aEndpointIT -Deddi.base-url=http://localhost:7070
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("running-instance")
public class A2aEndpointIT extends BaseStandaloneIT {

    // ==================== Agent Discovery ====================

    @Test
    @Order(1)
    @DisplayName("List A2A agents should return array")
    void listA2aAgents() {
        given().get("/a2a/agents")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(2)
    @DisplayName("Default agent card should be accessible")
    void defaultAgentCard() {
        Response response = given().get("/.well-known/agent.json");

        // May return 200 (agent found) or 404 (no A2A-enabled agents)
        Assertions.assertTrue(
                response.getStatusCode() == 200 || response.getStatusCode() == 404,
                "Expected 200 or 404, got: " + response.getStatusCode());

        if (response.getStatusCode() == 200) {
            response.then().assertThat()
                    .contentType(ContentType.JSON)
                    .body("name", notNullValue());
        }
    }

    // ==================== JSON-RPC Error Handling ====================

    @Test
    @Order(3)
    @DisplayName("JSON-RPC unknown method should return method-not-found error")
    void jsonRpc_unknownMethod() {
        String request = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "nonexistent/method",
                  "params": {}
                }
                """;

        Response response = given()
                .contentType(ContentType.JSON)
                .body(request)
                .post("/a2a/agents/000000000000000000000000");

        response.then().assertThat()
                .statusCode(200)
                .body("jsonrpc", equalTo("2.0"))
                .body("error", notNullValue())
                .body("error.code", equalTo(-32601)); // ERROR_METHOD_NOT_FOUND
    }

    @Test
    @Order(4)
    @DisplayName("JSON-RPC tasks/get with missing task ID should return error")
    void jsonRpc_tasksGet_missingId() {
        String request = """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tasks/get",
                  "params": {}
                }
                """;

        Response response = given()
                .contentType(ContentType.JSON)
                .body(request)
                .post("/a2a/agents/000000000000000000000000");

        response.then().assertThat()
                .statusCode(200)
                .body("error", notNullValue());
    }

    @Test
    @Order(5)
    @DisplayName("JSON-RPC tasks/cancel with non-existent task should return error")
    void jsonRpc_tasksCancel_notFound() {
        String request = """
                {
                  "jsonrpc": "2.0",
                  "id": 3,
                  "method": "tasks/cancel",
                  "params": {"id": "nonexistent-task-id"}
                }
                """;

        Response response = given()
                .contentType(ContentType.JSON)
                .body(request)
                .post("/a2a/agents/000000000000000000000000");

        response.then().assertThat()
                .statusCode(200)
                .body("error", notNullValue());
    }
}
