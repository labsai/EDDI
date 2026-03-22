package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Component test for Agent Deployment lifecycle with real MongoDB.
 * <p>
 * Tests deploy, undeploy, status transitions, and error handling
 * for the Agent deployment management system.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class AgentDeploymentComponentIT extends BaseIntegrationIT {

        private static final java.util.List<ResourceId> createdAgents = new java.util.ArrayList<>();

        @AfterAll
        static void cleanup() {
                for (ResourceId agentId : createdAgents) {
                        undeployAgentQuietly(agentId.id(), agentId.version());
                }
        }

        @Test
        @DisplayName("should deploy a Agent and reach READY status")
        void deployAgent_reachesReady() throws Exception {
                ResourceId agentId = createMinimalAgent();

                // Deploy
                given().post(String.format("administration/production/deploy/%s?version=%s&autoDeploy=false",
                                agentId.id(), agentId.version()));

                // Poll for READY
                for (int i = 0; i < 30; i++) {
                        Response response = given()
                                        .get(String.format(
                                                        "administration/production/deploymentstatus/%s?version=%s&format=text",
                                                        agentId.id(), agentId.version()));
                        String status = response.getBody().print().trim();
                        if ("READY".equals(status)) {
                                return; // test passes
                        }
                        Thread.sleep(500);
                }
                Assertions.fail("Agent deployment did not reach READY status within 15 seconds");
        }

        @Test
        @DisplayName("should undeploy a deployed agent")
        void undeployAgent_succeeds() throws Exception {
                ResourceId agentId = createMinimalAgent();
                deployAgent(agentId.id(), agentId.version());

                // Undeploy
                Response response = given()
                                .post(String.format("administration/production/undeploy/%s?version=%s",
                                                agentId.id(), agentId.version()));

                response.then().statusCode(anyOf(equalTo(200), equalTo(202), equalTo(204)));
        }

        @Test
        @DisplayName("should list deployment status")
        void listDeploymentStatus() {
                Response response = given()
                                .get("administration/production/deploymentstatus");

                response.then().assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON);
        }

        @Test
        @DisplayName("should return deployment status for a Agent version")
        void getDeploymentStatus_forAgent() throws Exception {
                ResourceId agentId = createMinimalAgent();
                deployAgent(agentId.id(), agentId.version());

                Response response = given()
                                .get(String.format("administration/production/deploymentstatus/%s?version=%s",
                                                agentId.id(), agentId.version()));

                response.then().assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON)
                                .body("status", equalTo("READY"));
        }

        // ==================== Helpers ====================

        private ResourceId createMinimalAgent() throws Exception {
                String dictionary = load("agentengine/dictionary.json");
                String behavior = load("agentengine/rules.json");
                String output = load("agentengine/output.json");

                String locationDictionary = createResource(dictionary, "/regulardictionarystore/regulardictionaries");
                String locationBehavior = createResource(behavior, "/behaviorstore/behaviorsets");
                String locationOutput = createResource(output, "/outputstore/outputsets");

                String packageBody = String.format(
                                """
                                                {
                                                  "WorkflowSteps": [
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
                                                    {"type": "eddi://ai.labs.behavior", "config": {"uri": "%s"}},
                                                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}}
                                                  ]
                                                }""",
                                locationDictionary, locationBehavior, locationOutput);

                String locationWorkflow = createResource(packageBody, "/WorkflowStore/packages");
                String agentBody = String.format("""
                                {"packages": ["%s"]}""", locationWorkflow);
                String agentLocation = createResource(agentBody, "/AgentStore/agents");
                ResourceId agentId = extractResourceId(agentLocation);
                createdAgents.add(agentId);
                return agentId;
        }
}
