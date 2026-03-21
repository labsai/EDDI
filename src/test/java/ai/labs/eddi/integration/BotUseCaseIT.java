package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for Agent use cases: import, deploy, multi-turn conversation.
 * <p>
 * Ported from {@code RestUseCaseTest} in EDDI-integration-tests.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class BotUseCaseIT extends BaseIntegrationIT {

    private static ResourceId weatherBotId;
    private static boolean botImported = false;
    private static final List<String> conversationIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        if (!botImported) {
            weatherBotId = importBot("weather_bot_v1");
            botImported = true;
        }
    }

    @AfterAll
    static void cleanup() {
        if (weatherBotId != null) {
            undeployBotQuietly(weatherBotId.id(), weatherBotId.version());
        }
    }

    // ==================== Weather Agent Tests ====================

    @Test
    @DisplayName("should handle multi-turn weather conversation with property extraction")
    void weatherBot() {
        String testUserId = "testUser" + System.currentTimeMillis();
        ResourceId conversationId = createConversation(weatherBotId.id(), testUserId);
        conversationIds.add(conversationId.id());

        // Ask about weather
        sendUserInput(weatherBotId.id(), conversationId.id(), "weather", true, true);

        // Provide city
        Response response = sendUserInput(weatherBotId.id(), conversationId.id(), "Vienna", true, false);

        response.then().assertThat()
                .body("agentId", equalTo(weatherBotId.id()))
                .body("agentVersion", equalTo(weatherBotId.version()))
                .body("conversationOutputs[1].input", equalTo("weather"))
                .body("conversationOutputs[1].actions[0]", equalTo("ask_for_city"))
                .body("conversationOutputs[2].input", equalTo("Vienna"))
                .body("conversationOutputs[2].actions[0]", equalTo("current_weather_in_city"))
                .body("conversationProperties.chosenCity.valueString", equalTo("Vienna"))
                .body("conversationProperties.chosenCity.scope", equalTo("conversation"));
    }

    @Test
    @DisplayName("should support managed Agent API with Agent triggers")
    void useBotManagement() throws IOException {
        String intent = "weather-bot";
        String userId = "12345";

        // Delete any stale trigger from a previous test run
        given().delete("/AgentTriggerStore/bottriggers/" + intent);

        // Register Agent trigger (create via POST)
        given().contentType(ContentType.JSON)
                .body(String.format(load("useCases/AgentDeployment.json"), weatherBotId.id()))
                .post("/AgentTriggerStore/bottriggers")
                .then().statusCode(200);

        // End any existing conversation
        given().post("/managedbots/" + intent + "/" + userId + "/endConversation");

        // Send input via managed Agent API
        Response response = given().contentType(ContentType.JSON)
                .body("{\"input\":\"weather\"}")
                .queryParam("returnCurrentStepOnly", "false")
                .post("/managedbots/" + intent + "/" + userId);

        response.then().assertThat()
                .statusCode(200)
                .body("agentId", equalTo(weatherBotId.id()))
                .body("agentVersion", equalTo(weatherBotId.version()))
                .body("conversationSteps[1].conversationStep[1].key", equalTo("actions"))
                .body("conversationSteps[1].conversationStep[1].value[0]", equalTo("ask_for_city"));
    }

    // ==================== Helpers ====================

    private ResourceId importBot(String filename) throws Exception {
        URL resource = Thread.currentThread().getContextClassLoader()
                .getResource("tests/useCases/" + filename + ".zip");
        if (resource == null) {
            throw new IOException("Test resource not found: tests/useCases/" + filename + ".zip");
        }
        File file = new File(resource.getFile().replaceFirst("^/([A-Z]:)", "$1"));

        Response response = given()
                .contentType("application/zip")
                .body(file)
                .post("/backup/import");

        response.then().statusCode(200);

        String location = response.getHeader("location");
        if (location == null) {
            throw new RuntimeException("Import response did not contain a location header. " +
                    "Status: " + response.getStatusCode() + ", Body: " + response.getBody().asString());
        }

        ResourceId resourceId = extractResourceId(location);
        deployAgent(resourceId.id(), resourceId.version());
        return resourceId;
    }

    private void deployAgent(String id, int version) throws InterruptedException {
        given().post(String.format("administration/production/deploy/%s?version=%s&autoDeploy=false", id, version));
        for (int i = 0; i < 60; i++) {
            Response response = given()
                    .get(String.format("administration/production/deploymentstatus/%s?version=%s&format=text", id,
                            version));
            String status = response.getBody().print().trim();
            if ("READY".equals(status))
                return;
            if ("ERROR".equals(status))
                throw new RuntimeException("Bot deployment failed");
            Thread.sleep(500);
        }
        throw new RuntimeException("Bot deployment timed out");
    }
}
