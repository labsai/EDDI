package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for API Calls (HTTP Calls) Configuration CRUD operations.
 * <p>
 * Tests Create → Read → Update → Delete lifecycle for
 * {@code /apicallstore/apicalls}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ApiCallsCrudIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/apicallstore/apicalls/";
    private static final String RESOURCE_URI = "eddi://ai.labs.apicalls" + ROOT_PATH;

    private static final ResourceId[] resourceId = new ResourceId[1];

    @AfterAll
    static void cleanup() {
        if (resourceId[0] != null) {
            deleteResourceQuietly(ROOT_PATH, resourceId[0].id(), resourceId[0].version());
        }
    }

    private static final String CREATE_JSON = """
            {
              "targetServerUrl": "https://api.example.com/v1",
              "httpCalls": [
                {
                  "name": "listItems",
                  "description": "List all items",
                  "parameters": {},
                  "actions": ["api_list_items"],
                  "saveResponse": true,
                  "responseObjectName": "listItems_response",
                  "request": {
                    "path": "/items",
                    "method": "get",
                    "headers": { "Authorization": "Bearer test-key" },
                    "queryParams": { "limit": "10" },
                    "contentType": "",
                    "body": ""
                  }
                },
                {
                  "name": "getItem",
                  "description": "Get an item by ID",
                  "parameters": { "itemId": "The item identifier" },
                  "actions": ["api_get_item"],
                  "saveResponse": true,
                  "responseObjectName": "getItem_response",
                  "request": {
                    "path": "/items/{itemId}",
                    "method": "get",
                    "headers": { "Authorization": "Bearer test-key" },
                    "queryParams": {},
                    "contentType": "",
                    "body": ""
                  }
                }
              ]
            }
            """;

    private static final String UPDATE_JSON = """
            {
              "targetServerUrl": "https://api.example.com/v2",
              "httpCalls": [
                {
                  "name": "listItems",
                  "description": "List all items (v2)",
                  "parameters": {},
                  "actions": ["api_list_items"],
                  "saveResponse": true,
                  "responseObjectName": "listItems_response",
                  "request": {
                    "path": "/items",
                    "method": "get",
                    "headers": { "Authorization": "Bearer test-key-v2" },
                    "queryParams": { "limit": "20" },
                    "contentType": "",
                    "body": ""
                  }
                }
              ]
            }
            """;

    @Test
    @Order(1)
    @DisplayName("Create API calls configuration")
    void createApiCalls() {
        assertCreate(CREATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId);
    }

    @Test
    @Order(2)
    @DisplayName("Read created API calls configuration")
    void readApiCalls() {
        assertRead(ROOT_PATH, resourceId[0]);
    }

    @Test
    @Order(3)
    @DisplayName("Read API calls should have correct structure")
    void readApiCalls_verifyStructure() {
        given().get(ROOT_PATH + resourceId[0].id() + VERSION_STRING + resourceId[0].version())
                .then().assertThat()
                .statusCode(200)
                .body("targetServerUrl", equalTo("https://api.example.com/v1"))
                .body("httpCalls.size()", equalTo(2))
                .body("httpCalls[0].name", equalTo("listItems"))
                .body("httpCalls[0].actions[0]", equalTo("api_list_items"))
                .body("httpCalls[1].name", equalTo("getItem"))
                .body("httpCalls[1].request.path", equalTo("/items/{itemId}"));
    }

    @Test
    @Order(4)
    @DisplayName("Update API calls configuration")
    void updateApiCalls() {
        assertUpdate(UPDATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId)
                .then().assertThat()
                .body("targetServerUrl", equalTo("https://api.example.com/v2"))
                .body("httpCalls.size()", equalTo(1))
                .body("httpCalls[0].description", equalTo("List all items (v2)"));
    }

    @Test
    @Order(5)
    @DisplayName("Delete API calls configuration")
    void deleteApiCalls() {
        assertDelete(ROOT_PATH, resourceId[0]);
    }
}
