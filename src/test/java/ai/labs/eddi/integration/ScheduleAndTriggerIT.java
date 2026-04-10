package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for Schedule Store and Agent Trigger management.
 * <p>
 * Tests schedule CRUD via {@code /schedulestore/schedules}, trigger CRUD via
 * {@code /AgentTriggerStore/agenttriggers}, and managed conversation flow via
 * {@code /agents/managed}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ScheduleAndTriggerIT extends BaseIntegrationIT {

    private static final String SCHEDULE_BASE = "/schedulestore/schedules/";
    private static final String TRIGGER_BASE = "/AgentTriggerStore/agenttriggers/";

    private static String createdScheduleId;

    // ==================== Schedule CRUD ====================

    @Test
    @Order(1)
    @DisplayName("Create schedule should succeed")
    void createSchedule() {
        String json = """
                {
                  "name": "Test Integration Schedule",
                  "agentId": "test-agent-id",
                  "triggerType": "CRON",
                  "cronExpression": "0 0 * * * ?",
                  "timeZone": "UTC",
                  "enabled": false,
                  "userId": "system:scheduler",
                  "message": "scheduled check-in",
                  "conversationStrategy": "new"
                }
                """;

        var response = given().contentType(ContentType.JSON).body(json)
                .post(SCHEDULE_BASE);

        response.then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(201)));

        // Extract the ID from the response for subsequent tests
        if (response.jsonPath().get("id") != null) {
            createdScheduleId = response.jsonPath().getString("id");
        }
    }

    @Test
    @Order(2)
    @DisplayName("List schedules should return results")
    void listSchedules() {
        given().get(SCHEDULE_BASE)
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(3)
    @DisplayName("Read created schedule should return correct data")
    void readSchedule() {
        Assumptions.assumeTrue(createdScheduleId != null, "Schedule ID must be captured from test 1");

        given().get(SCHEDULE_BASE + createdScheduleId)
                .then().assertThat()
                .statusCode(200)
                .body("name", equalTo("Test Integration Schedule"))
                .body("triggerType", equalTo("CRON"));
    }

    @Test
    @Order(4)
    @DisplayName("Delete schedule should succeed")
    void deleteSchedule() {
        Assumptions.assumeTrue(createdScheduleId != null, "Schedule ID must be captured from test 1");

        given().delete(SCHEDULE_BASE + createdScheduleId)
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
    }

    // ==================== Trigger CRUD ====================

    @Test
    @Order(5)
    @DisplayName("Create trigger should succeed")
    void createTrigger() {
        String intent = "test-trigger-" + System.currentTimeMillis();

        String json = String.format("""
                {
                  "intent": "%s",
                  "agentDeployments": [
                    {
                      "agentId": "test-agent-for-trigger",
                      "agentVersion": 1,
                      "environment": "production"
                    }
                  ]
                }
                """, intent);

        given().contentType(ContentType.JSON).body(json)
                .post(TRIGGER_BASE)
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(201)));

        // Cleanup
        given().delete(TRIGGER_BASE + intent);
    }

    @Test
    @Order(6)
    @DisplayName("Read trigger should return config")
    void readTrigger() {
        String intent = "read-trigger-test-" + System.currentTimeMillis();

        // Create first
        String json = String.format("""
                {
                  "intent": "%s",
                  "agentDeployments": [
                    {
                      "agentId": "test-agent-read",
                      "agentVersion": 1,
                      "environment": "production"
                    }
                  ]
                }
                """, intent);

        given().contentType(ContentType.JSON).body(json)
                .post(TRIGGER_BASE)
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));

        // Read back
        given().get(TRIGGER_BASE + intent)
                .then().assertThat()
                .statusCode(200)
                .body("intent", equalTo(intent));

        // Cleanup
        given().delete(TRIGGER_BASE + intent);
    }

    @Test
    @Order(7)
    @DisplayName("Delete trigger should succeed")
    void deleteTrigger() {
        String intent = "delete-trigger-test-" + System.currentTimeMillis();

        // Create
        String json = String.format("""
                {
                  "intent": "%s",
                  "agentDeployments": [
                    {
                      "agentId": "test-agent-delete",
                      "agentVersion": 1,
                      "environment": "production"
                    }
                  ]
                }
                """, intent);

        given().contentType(ContentType.JSON).body(json)
                .post(TRIGGER_BASE)
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));

        // Delete
        given().delete(TRIGGER_BASE + intent)
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
    }

    @Test
    @Order(8)
    @DisplayName("List all triggers should return list")
    void listTriggers() {
        given().get(TRIGGER_BASE)
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    // ==================== Managed Conversation ====================

    @Test
    @Order(9)
    @DisplayName("Managed conversation via trigger should work end-to-end")
    void managedConversation() throws Exception {
        // Deploy a real agent first
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
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.templating", "config": {}}
                  ]
                }""", locationDictionary, locationBehavior, locationOutput);

        String locationWorkflow = createResource(packageBody, "/workflowstore/workflows");
        String agentBody = String.format("""
                {"packages": ["%s"]}""", locationWorkflow);
        String agentLocation = createResource(agentBody, "/agentstore/agents");
        ResourceId agentId = extractResourceId(agentLocation);
        deployAgent(agentId.id(), agentId.version());

        String intent = "managed-test-" + System.currentTimeMillis();
        String userId = "managed-user-" + System.currentTimeMillis();

        // Create trigger with deployment
        String triggerJson = String.format("""
                {
                  "intent": "%s",
                  "agentDeployments": [
                    {
                      "agentId": "%s",
                      "agentVersion": %d,
                      "environment": "production"
                    }
                  ]
                }
                """, intent, agentId.id(), agentId.version());

        given().contentType(ContentType.JSON).body(triggerJson)
                .post(TRIGGER_BASE)
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));

        // Send input via managed API
        given().contentType(ContentType.JSON)
                .body("{\"input\":\"hello\"}")
                .queryParam("returnCurrentStepOnly", "false")
                .post("/agents/managed/" + intent + "/" + userId)
                .then().assertThat()
                .statusCode(200);

        // End managed conversation
        given().post("/agents/managed/" + intent + "/" + userId + "/endConversation")
                .then().statusCode(anyOf(equalTo(200), equalTo(204)));

        // Cleanup
        undeployAgentQuietly(agentId.id(), agentId.version());
        given().delete(TRIGGER_BASE + intent);
    }
}
