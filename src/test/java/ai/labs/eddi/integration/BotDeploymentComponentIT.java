package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Component test for Bot Deployment lifecycle with real MongoDB.
 * <p>
 * Tests deploy, undeploy, status transitions, and error handling
 * for the bot deployment management system.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class BotDeploymentComponentIT extends BaseIntegrationIT {

        private static final java.util.List<ResourceId> createdBots = new java.util.ArrayList<>();

        @AfterAll
        static void cleanup() {
                for (ResourceId botId : createdBots) {
                        undeployBotQuietly(botId.id(), botId.version());
                }
        }

        @Test
        @DisplayName("should deploy a bot and reach READY status")
        void deployBot_reachesReady() throws Exception {
                ResourceId botId = createMinimalBot();

                // Deploy
                given().post(String.format("administration/unrestricted/deploy/%s?version=%s&autoDeploy=false",
                                botId.id(), botId.version()));

                // Poll for READY
                for (int i = 0; i < 30; i++) {
                        Response response = given()
                                        .get(String.format(
                                                        "administration/unrestricted/deploymentstatus/%s?version=%s&format=text",
                                                        botId.id(), botId.version()));
                        String status = response.getBody().print().trim();
                        if ("READY".equals(status)) {
                                return; // test passes
                        }
                        Thread.sleep(500);
                }
                Assertions.fail("Bot deployment did not reach READY status within 15 seconds");
        }

        @Test
        @DisplayName("should undeploy a deployed bot")
        void undeployBot_succeeds() throws Exception {
                ResourceId botId = createMinimalBot();
                deployBot(botId.id(), botId.version());

                // Undeploy
                Response response = given()
                                .post(String.format("administration/unrestricted/undeploy/%s?version=%s",
                                                botId.id(), botId.version()));

                response.then().statusCode(anyOf(equalTo(200), equalTo(202), equalTo(204)));
        }

        @Test
        @DisplayName("should list deployment status")
        void listDeploymentStatus() {
                Response response = given()
                                .get("administration/unrestricted/deploymentstatus");

                response.then().assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON);
        }

        @Test
        @DisplayName("should return deployment status for a bot version")
        void getDeploymentStatus_forBot() throws Exception {
                ResourceId botId = createMinimalBot();
                deployBot(botId.id(), botId.version());

                Response response = given()
                                .get(String.format("administration/unrestricted/deploymentstatus/%s?version=%s",
                                                botId.id(), botId.version()));

                response.then().assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON)
                                .body("status", equalTo("READY"));
        }

        // ==================== Helpers ====================

        private ResourceId createMinimalBot() throws Exception {
                String dictionary = load("botengine/regularDictionary.json");
                String behavior = load("botengine/behavior.json");
                String output = load("botengine/output.json");

                String locationDictionary = createResource(dictionary, "/regulardictionarystore/regulardictionaries");
                String locationBehavior = createResource(behavior, "/behaviorstore/behaviorsets");
                String locationOutput = createResource(output, "/outputstore/outputsets");

                String packageBody = String.format(
                                """
                                                {
                                                  "packageExtensions": [
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

                String locationPackage = createResource(packageBody, "/packagestore/packages");
                String botBody = String.format("""
                                {"packages": ["%s"]}""", locationPackage);
                String botLocation = createResource(botBody, "/botstore/bots");
                ResourceId botId = extractResourceId(botLocation);
                createdBots.add(botId);
                return botId;
        }
}
