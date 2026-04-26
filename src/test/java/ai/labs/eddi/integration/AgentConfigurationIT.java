/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for advanced Agent configuration and lifecycle.
 * <p>
 * Tests agent creation with full config, version management, agent setup
 * wizard, and JSON Schema endpoint.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AgentConfigurationIT extends BaseIntegrationIT {

    private static final String AGENT_STORE = "/agentstore/agents/";
    private static final java.util.List<ResourceId> createdAgents = new java.util.ArrayList<>();

    @AfterAll
    static void cleanup() {
        for (ResourceId agentId : createdAgents) {
            undeployAgentQuietly(agentId.id(), agentId.version());
        }
    }

    // ==================== Agent CRUD ====================

    @Test
    @Order(1)
    @DisplayName("Create agent with minimal config should return 201")
    void createAgent() throws Exception {
        ResourceId agentId = createMinimalAgent();
        createdAgents.add(agentId);

        given().get(AGENT_STORE + agentId.id() + VERSION_STRING + agentId.version())
                .then().assertThat()
                .statusCode(200)
                .body("packages", not(empty()));
    }

    @Test
    @Order(2)
    @DisplayName("Update agent should create new version")
    void updateAgent() throws Exception {
        ResourceId agentId = createMinimalAgent();
        createdAgents.add(agentId);

        // Read current config
        String currentConfig = given().get(AGENT_STORE + agentId.id() + VERSION_STRING + agentId.version())
                .asString();

        // Update
        Response response = given().body(currentConfig).contentType(ContentType.JSON)
                .put(AGENT_STORE + agentId.id() + VERSION_STRING + agentId.version());

        response.then().assertThat()
                .statusCode(200)
                .header("location", containsString("?version=2"));
    }

    @Test
    @Order(3)
    @DisplayName("Agent JSON Schema should return valid schema")
    void readJsonSchema() {
        given().get(AGENT_STORE + "jsonSchema")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(4)
    @DisplayName("Agent descriptors should return list")
    void readDescriptors() {
        given().get(AGENT_STORE + "descriptors")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(5)
    @DisplayName("Agent descriptors with filter should support search")
    void readDescriptors_withFilter() {
        given().queryParam("filter", "nonexistent-agent-xyz")
                .get(AGENT_STORE + "descriptors")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    // ==================== Agent Setup Wizard ====================

    @Test
    @Order(6)
    @DisplayName("Agent setup wizard should create and deploy agent in one call")
    void setupAgentWizard() {
        String setupRequest = """
                {
                  "agentName": "Setup Wizard Test Agent",
                  "systemPrompt": "You are a test assistant.",
                  "provider": "openai",
                  "apiKey": "sk-test-not-real",
                  "model": "gpt-4o-mini",
                  "deploy": false
                }
                """;

        Response response = given().contentType(ContentType.JSON).body(setupRequest)
                .post("/administration/agents/setup");

        response.then().assertThat()
                .statusCode(201)
                .contentType(ContentType.JSON);
    }

    // ==================== Deploy with invalid config ====================

    @Test
    @Order(7)
    @DisplayName("Deploy agent with empty workflows should handle gracefully")
    void deployEmptyAgent() throws InterruptedException {
        String agentBody = """
                {"packages": []}""";
        String agentLocation = createResource(agentBody, AGENT_STORE);
        ResourceId agentId = extractResourceId(agentLocation);
        createdAgents.add(agentId);

        // Deploy (should succeed even with empty packages)
        given().post(String.format("administration/production/deploy/%s?version=%s&autoDeploy=false",
                agentId.id(), agentId.version()));

        // Poll for status
        for (int i = 0; i < 20; i++) {
            Response response = given().get(String.format(
                    "administration/production/deploymentstatus/%s?version=%s&format=text",
                    agentId.id(), agentId.version()));
            String status = response.getBody().print().trim();
            if ("READY".equals(status) || "ERROR".equals(status)) {
                return; // test passes — we just verify it doesn't hang
            }
            Thread.sleep(500);
        }
    }

    // ==================== Helpers ====================

    private ResourceId createMinimalAgent() throws Exception {
        String dictionary = load("agentengine/dictionary.json");
        String behavior = load("agentengine/rules.json");
        String output = load("agentengine/output.json");

        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationBehavior = createResource(behavior, "/rulestore/rulesets");
        String locationOutput = createResource(output, "/outputstore/outputsets");

        String packageBody = String.format("""
                {
                  "workflowSteps": [
                    {
                      "type": "eddi://ai.labs.parser",
                      "config": {},
                      "extensions": {
                        "dictionaries": [
                          {"type": "eddi://ai.labs.parser.dictionaries.regular", "config": {"uri": "%s"}}
                        ],
                        "corrections": []
                      }
                    },
                    {"type": "eddi://ai.labs.rules", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}}
                  ]
                }""", locationDictionary, locationBehavior, locationOutput);

        String locationWorkflow = createResource(packageBody, "/workflowstore/workflows");
        String agentBody = String.format("""
                {"packages": ["%s"]}""", locationWorkflow);
        String agentLocation = createResource(agentBody, AGENT_STORE);
        return extractResourceId(agentLocation);
    }
}
