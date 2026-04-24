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
 * Integration test for the create_api_agent workflow.
 * <p>
 * Uses Testcontainers to run EDDI + MongoDB in Docker containers, providing
 * true black-box E2E testing that works on all platforms and avoids MCP CDI
 * augmentation issues.
 * <p>
 * Validates the resource creation workflow:
 * <ol>
 * <li>Create 2 ApiCallsConfiguration resources (grouped by tag: users,
 * orders)</li>
 * <li>Create Behavior Rules and LangChain configuration</li>
 * <li>Create Workflow with 5 extensions</li>
 * <li>Verify all resources are persisted and readable</li>
 * <li>Create Agent referencing the workflow</li>
 * <li>Deploy the Agent and verify READY status</li>
 * <li>Start a conversation to verify the workflow is wired correctly</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CreateApiAgentIT extends ContainerBaseIT {

    // Shared state across ordered test methods
    private static String httpCallsUsersLocation;
    private static String httpCallsOrdersLocation;
    private static String behaviorLocation;
    private static String langchainLocation;
    private static String packageLocation;
    private static String agentId;
    private static int agentVersion;

    // ==================== Test Data ====================

    /**
     * Users API group — 2 endpoints: GET /users, GET /users/{userId}
     */
    private static final String HTTPCALLS_USERS = """
            {
              "targetServerUrl": "https://api.example.com/v1",
              "httpCalls": [
                {
                  "name": "listUsers",
                  "description": "List all users",
                  "parameters": {},
                  "actions": ["api_get_users"],
                  "saveResponse": true,
                  "responseObjectName": "listUsers_response",
                  "request": {
                    "path": "/users",
                    "method": "get",
                    "headers": { "Authorization": "Bearer test-key" },
                    "queryParams": { "limit": "{limit}" },
                    "contentType": "",
                    "body": ""
                  }
                },
                {
                  "name": "getUser",
                  "description": "Get a user by ID",
                  "parameters": { "userId": "The user identifier" },
                  "actions": ["api_get_users_userid"],
                  "saveResponse": true,
                  "responseObjectName": "getUser_response",
                  "request": {
                    "path": "/users/{userId}",
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

    /**
     * Orders API group — 1 endpoint: POST /orders
     */
    private static final String HTTPCALLS_ORDERS = """
            {
              "targetServerUrl": "https://api.example.com/v1",
              "httpCalls": [
                {
                  "name": "createOrder",
                  "description": "Create a new order",
                  "parameters": {},
                  "actions": ["api_post_orders"],
                  "saveResponse": true,
                  "responseObjectName": "createOrder_response",
                  "request": {
                    "path": "/orders",
                    "method": "post",
                    "headers": { "Authorization": "Bearer test-key" },
                    "queryParams": {},
                    "contentType": "application/json",
                    "body": "{\\n  \\"productId\\": \\"{productId}\\",\\n  \\"quantity\\": {quantity}\\n}"
                  }
                }
              ]
            }
            """;

    private static final String BEHAVIOR_CONFIG = """
            {
              "expressionsAsActions": true,
              "behaviorGroups": [
                {
                  "name": "",
                  "behaviorRules": [
                    {
                      "name": "Send Message to LLM",
                      "actions": ["send_message"],
                      "conditions": [
                        {
                          "type": "inputmatcher",
                          "configs": { "expressions": "*" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """;

    private static final String LANGCHAIN_CONFIG = """
            {
              "tasks": [
                {
                  "id": "anthropic",
                  "type": "anthropic",
                  "description": "LLM integration via anthropic",
                  "actions": ["send_message"],
                  "parameters": {
                    "systemMessage": "You are an API assistant.\\n\\nAvailable API endpoints (3 total, 2 groups):\\n- GET /users — List all users\\n- GET /users/{userId} — Get a user by ID\\n- POST /orders — Create a new order",
                    "addToOutput": "true",
                    "apiKey": "sk-test-not-real",
                    "modelName": "claude-sonnet-4-6",
                    "timeout": "60",
                    "temperature": "0.3",
                    "logRequests": "true",
                    "logResponses": "true"
                  },
                  "conversationHistoryLimit": 10
                }
              ]
            }
            """;

    // ResourceId and extractResourceId inherited from BaseIntegrationIT

    // ==================== Step 1: Create ApiCalls resources ====================

    @Test
    @Order(1)
    @DisplayName("should create ApiCalls config for 'users' tag group")
    void createApiCallsUsers() {
        Response response = given().contentType(ContentType.JSON).body(HTTPCALLS_USERS).post("/apicallstore/apicalls");

        response.then().assertThat().statusCode(201).header("location", containsString("/apicallstore/apicalls/"));

        httpCallsUsersLocation = response.getHeader("location");
    }

    @Test
    @Order(2)
    @DisplayName("should create ApiCalls config for 'orders' tag group")
    void createApiCallsOrders() {
        Response response = given().contentType(ContentType.JSON).body(HTTPCALLS_ORDERS).post("/apicallstore/apicalls");

        response.then().assertThat().statusCode(201).header("location", containsString("/apicallstore/apicalls/"));

        httpCallsOrdersLocation = response.getHeader("location");
    }

    @Test
    @Order(3)
    @DisplayName("should read back ApiCalls config with correct structure")
    void readApiCallsUsers() {
        ResourceId res = extractResourceId(httpCallsUsersLocation);
        given().get("/apicallstore/apicalls/" + res.id() + "?version=" + res.version()).then().assertThat().statusCode(200)
                .body("targetServerUrl", equalTo("https://api.example.com/v1")).body("httpCalls.size()", equalTo(2))
                .body("httpCalls[0].name", equalTo("listUsers")).body("httpCalls[0].description", equalTo("List all users"))
                .body("httpCalls[0].actions[0]", equalTo("api_get_users")).body("httpCalls[0].request.path", equalTo("/users"))
                .body("httpCalls[0].request.queryParams.limit", equalTo("{limit}")).body("httpCalls[1].name", equalTo("getUser"))
                .body("httpCalls[1].request.path", equalTo("/users/{userId}"));
    }

    // ==================== Step 2: Create Behavior Rules ====================

    @Test
    @Order(4)
    @DisplayName("should create behavior rules")
    void createBehavior() {
        Response response = given().contentType(ContentType.JSON).body(BEHAVIOR_CONFIG).post("/rulestore/rulesets");

        response.then().assertThat().statusCode(201);
        behaviorLocation = response.getHeader("location");
    }

    // ==================== Step 3: Create LangChain ====================

    @Test
    @Order(5)
    @DisplayName("should create LangChain configuration with enriched prompt")
    void createLangchain() {
        Response response = given().contentType(ContentType.JSON).body(LANGCHAIN_CONFIG).post("/llmstore/llms");

        response.then().assertThat().statusCode(201);
        langchainLocation = response.getHeader("location");

        // Verify enriched prompt includes API summary
        ResourceId res = extractResourceId(langchainLocation);
        given().get("/llmstore/llms/" + res.id() + "?version=" + res.version()).then().assertThat().statusCode(200)
                .body("tasks[0].parameters.systemMessage", containsString("Available API endpoints"));
    }

    // ==================== Step 4: Create Workflow ====================

    @Test
    @Order(6)
    @DisplayName("should create package with parser + behavior + 2 httpcalls groups + langchain")
    void createWorkflow() {
        String packageJson = String.format("""
                {
                  "workflowSteps": [
                    { "type": "eddi://ai.labs.parser", "config": {} },
                    { "type": "eddi://ai.labs.rules", "config": { "uri": "%s" } },
                    { "type": "eddi://ai.labs.apicalls", "config": { "uri": "%s" } },
                    { "type": "eddi://ai.labs.apicalls", "config": { "uri": "%s" } },
                    { "type": "eddi://ai.labs.llm", "config": { "uri": "%s" } }
                  ]
                }
                """, behaviorLocation, httpCallsUsersLocation, httpCallsOrdersLocation, langchainLocation);

        Response response = given().contentType(ContentType.JSON).body(packageJson).post("/workflowstore/workflows");

        response.then().assertThat().statusCode(201).header("location", containsString("/workflowstore/workflows/"));

        packageLocation = response.getHeader("location");
    }

    @Test
    @Order(7)
    @DisplayName("should read back package with 5 extensions in correct order")
    void readWorkflow() {
        ResourceId res = extractResourceId(packageLocation);
        given().get("/workflowstore/workflows/" + res.id() + "?version=" + res.version()).then().assertThat().statusCode(200)
                .body("workflowSteps.size()", equalTo(5)).body("workflowSteps[0].type", equalTo("eddi://ai.labs.parser"))
                .body("workflowSteps[1].type", equalTo("eddi://ai.labs.rules")).body("workflowSteps[2].type", equalTo("eddi://ai.labs.apicalls"))
                .body("workflowSteps[3].type", equalTo("eddi://ai.labs.apicalls")).body("workflowSteps[4].type", equalTo("eddi://ai.labs.llm"));
    }

    // ==================== Step 5: Create Agent ====================

    @Test
    @Order(8)
    @DisplayName("should create Agent referencing the workflow")
    void createAgent() {
        String agentJson = String.format("""
                {
                  "packages": ["%s"]
                }
                """, packageLocation);

        Response response = given().contentType(ContentType.JSON).body(agentJson).post("/agentstore/agents");

        response.then().assertThat().statusCode(201).header("location", containsString("/agentstore/agents/"));

        ResourceId res = extractResourceId(response.getHeader("location"));
        agentId = res.id();
        agentVersion = res.version();
    }

    // ==================== Step 6: Deploy ====================

    @Test
    @Order(9)
    @DisplayName("should deploy the API Agent successfully")
    void deployApIAgent() throws InterruptedException {
        given().post(String.format("administration/production/deploy/%s?version=%s&autoDeploy=false", agentId, agentVersion));

        // Poll until READY (max 30s)
        for (int i = 0; i < 60; i++) {
            Response response = given()
                    .get(String.format("administration/production/deploymentstatus/%s?version=%s&format=text", agentId, agentVersion));
            String status = response.getBody().print().trim();
            if ("READY".equals(status))
                return;
            if ("ERROR".equals(status)) {
                throw new RuntimeException("Agent deployment failed (id=" + agentId + ", version=" + agentVersion + ")");
            }
            Thread.sleep(500);
        }
        throw new RuntimeException("Agent deployment timed out");
    }

    // ==================== Step 7: Conversation ====================

    @Test
    @Order(10)
    @DisplayName("should start a conversation with the API agent")
    void startConversation() {
        Response response = given().post("agents/" + agentId + "/start?environment=production&userId=apIAgent-test-user");
        response.then().statusCode(201);
        String location = response.getHeader("location");

        Assertions.assertNotNull(location, "Conversation location should be returned");
        // Verify we get a valid URI pointing to the new conversation resource instead
        // of looking for the agentId,
        // which is no longer embedded directly in the conversation resource URI.
        Assertions.assertTrue(location.contains("conversationstore/conversations"), "Conversation should reference the correct store");
    }

    // ==================== Cleanup ====================

    @AfterAll
    static void cleanup() {
        if (agentId != null) {
            try {
                given().post(String.format("administration/production/undeploy/%s?version=%s", agentId, agentVersion));
            } catch (Exception ignored) {
            }
        }
    }
}
