package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * API Contract tests validating REST endpoint response formats,
 * status codes, and content types match expected contracts.
 * <p>
 * Ensures backward compatibility of REST API responses for all consumers.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
class ApiContractIT extends BaseIntegrationIT {

        // ==================== CRUD Endpoint Contracts ====================

        @Test
        @DisplayName("POST create behavior should return 201 with Location header")
        void createBehavior_returns201WithLocation() throws Exception {
                String json = load("behavior/createBehavior.json");

                Response response = given()
                                .contentType(ContentType.JSON)
                                .body(json)
                                .post("/behaviorstore/behaviorsets/");

                response.then().assertThat()
                                .statusCode(201)
                                .header("location", allOf(
                                                containsString("eddi://ai.labs.behavior"),
                                                containsString("?version=1")));
        }

        @Test
        @DisplayName("GET should return JSON with correct content type")
        void readBehavior_returnsJson() throws Exception {
                String json = load("behavior/createBehavior.json");
                String location = createResource(json, "/behaviorstore/behaviorsets/");
                ResourceId id = extractResourceId(location);

                given().get("/behaviorstore/behaviorsets/" + id.id() + "?version=" + id.version())
                                .then().assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON);
        }

        @Test
        @DisplayName("GET non-existent resource should return 404")
        void readNonExistent_returns404() {
                given().get("/behaviorstore/behaviorsets/000000000000000000000000?version=1")
                                .then().assertThat()
                                .statusCode(404);
        }

        @Test
        @DisplayName("POST create output should return 201 with Location header")
        void createOutput_returns201WithLocation() throws Exception {
                String json = load("output/createOutput.json");

                given().contentType(ContentType.JSON)
                                .body(json)
                                .post("/outputstore/outputsets/")
                                .then().assertThat()
                                .statusCode(201)
                                .header("location", allOf(
                                                containsString("eddi://ai.labs.output"),
                                                containsString("?version=1")));
        }

        @Test
        @DisplayName("POST create dictionary should return 201 with Location header")
        void createDictionary_returns201WithLocation() throws Exception {
                String json = load("regularDictionary/createRegularDictionary.json");

                given().contentType(ContentType.JSON)
                                .body(json)
                                .post("/regulardictionarystore/regulardictionaries/")
                                .then().assertThat()
                                .statusCode(201)
                                .header("location", allOf(
                                                containsString("eddi://ai.labs.regulardictionary"),
                                                containsString("?version=1")));
        }

        // ==================== Conversation API Contract ====================

        @Test
        @DisplayName("conversation log should have correct structure")
        void conversationLog_hasCorrectStructure() throws Exception {
                ResourceId botId = createAndDeployBot();
                ResourceId convId = createConversation(botId.id(), "contractTestUser");

                sendUserInput(botId.id(), convId.id(), "hello", false, false);

                Response response = getConversationLog(botId.id(), convId.id(), false);

                response.then().assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON)
                                .body("botId", notNullValue())
                                .body("botVersion", notNullValue())
                                .body("conversationSteps", notNullValue())
                                .body("conversationSteps", not(empty()))
                                .body("conversationState", anyOf(equalTo("READY"), equalTo("IN_PROGRESS")))
                                .body("undoAvailable", notNullValue())
                                .body("redoAvailable", notNullValue());
        }

        @Test
        @DisplayName("detailed conversation log should include conversationOutputs")
        void detailedConversationLog_hasOutputs() throws Exception {
                ResourceId botId = createAndDeployBot();
                ResourceId convId = createConversation(botId.id(), "contractTestUser2");

                Response response = sendUserInput(botId.id(), convId.id(), "hello", true, false);

                response.then().assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON)
                                .body("conversationOutputs", notNullValue())
                                .body("conversationOutputs", not(empty()));
        }

        @Test
        @DisplayName("bot store should return descriptors list")
        void botStore_listDescriptors() {
                given().get("/botstore/bots/descriptors")
                                .then().assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON);
        }

        @Test
        @DisplayName("package store should return descriptors list")
        void packageStore_listDescriptors() {
                given().get("/packagestore/packages/descriptors")
                                .then().assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON);
        }

        @Test
        @DisplayName("deployment status endpoint should return JSON")
        void deploymentStatus_returnsJson() {
                given().get("/administration/unrestricted/deploymentstatus")
                                .then().assertThat()
                                .statusCode(200)
                                .contentType(ContentType.JSON);
        }

        // ==================== Helpers ====================

        private ResourceId createAndDeployBot() throws Exception {
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
                                                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                                                    {"type": "eddi://ai.labs.templating", "config": {}},
                                                    {"type": "eddi://ai.labs.property", "config": {}}
                                                  ]
                                                }""",
                                locationDictionary, locationBehavior, locationOutput);

                String locationPackage = createResource(packageBody, "/packagestore/packages");
                String botBody = String.format("""
                                {"packages": ["%s"]}""", locationPackage);
                String botLocation = createResource(botBody, "/botstore/bots");

                ResourceId botId = extractResourceId(botLocation);
                deployBot(botId.id(), botId.version());
                return botId;
        }
}
