package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for MCP Calls Configuration CRUD operations.
 * <p>
 * Tests Create → Read → Update → Delete lifecycle for
 * {@code /mcpcallsstore/mcpcalls}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class McpCallsCrudIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/mcpcallsstore/mcpcalls/";
    private static final String RESOURCE_URI = "eddi://ai.labs.mcpcalls" + ROOT_PATH;

    private static final ResourceId[] resourceId = new ResourceId[1];

    private static final String CREATE_JSON = """
            {
              "mcpServerUrl": "http://localhost:3001/mcp",
              "name": "test-mcp-server",
              "transport": "http",
              "toolsWhitelist": ["search_documents", "summarize_text"],
              "mcpCalls": [
                {
                  "name": "searchDocs",
                  "description": "Search documents via MCP",
                  "actions": ["call_external_tool"],
                  "toolName": "search_documents",
                  "toolArguments": {"query": "[[inputNoExpressions]]"}
                }
              ]
            }
            """;

    private static final String UPDATE_JSON = """
            {
              "mcpServerUrl": "http://localhost:3001/mcp",
              "name": "test-mcp-server-updated",
              "transport": "http",
              "toolsWhitelist": ["search_documents", "summarize_text", "translate_text"],
              "mcpCalls": [
                {
                  "name": "searchDocs",
                  "description": "Search documents via MCP (updated)",
                  "actions": ["call_external_tool"],
                  "toolName": "search_documents",
                  "toolArguments": {"query": "[[inputNoExpressions]]", "maxResults": "10"}
                }
              ]
            }
            """;

    @Test
    @Order(1)
    @DisplayName("Create MCP calls configuration")
    void createMcpCalls() {
        assertCreate(CREATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId);
    }

    @Test
    @Order(2)
    @DisplayName("Read created MCP calls configuration")
    void readMcpCalls() {
        assertRead(ROOT_PATH, resourceId[0]);
    }

    @Test
    @Order(3)
    @DisplayName("Read MCP calls should have correct structure")
    void readMcpCalls_verifyStructure() {
        given().get(ROOT_PATH + resourceId[0].id() + VERSION_STRING + resourceId[0].version())
                .then().assertThat()
                .statusCode(200)
                .body("mcpServerUrl", equalTo("http://localhost:3001/mcp"))
                .body("name", equalTo("test-mcp-server"))
                .body("toolsWhitelist.size()", equalTo(2))
                .body("mcpCalls[0].toolName", equalTo("search_documents"));
    }

    @Test
    @Order(4)
    @DisplayName("Update MCP calls configuration")
    void updateMcpCalls() {
        assertUpdate(UPDATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId)
                .then().assertThat()
                .body("name", equalTo("test-mcp-server-updated"))
                .body("toolsWhitelist.size()", equalTo(3));
    }

    @Test
    @Order(5)
    @DisplayName("MCP calls descriptors should return list")
    void readDescriptors() {
        given().get(ROOT_PATH + "descriptors")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(6)
    @DisplayName("MCP calls JSON Schema should return valid schema")
    void readJsonSchema() {
        given().get(ROOT_PATH + "jsonSchema")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(7)
    @DisplayName("Delete MCP calls configuration")
    void deleteMcpCalls() {
        assertDelete(ROOT_PATH, resourceId[0]);
    }
}
