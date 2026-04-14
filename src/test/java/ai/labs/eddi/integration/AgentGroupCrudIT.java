package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for Agent Group Configuration CRUD operations.
 * <p>
 * Tests Create → Read → Update → Delete lifecycle for
 * {@code /groupstore/groups}, plus discussion styles endpoint.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AgentGroupCrudIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/groupstore/groups/";
    private static final String RESOURCE_URI = "eddi://ai.labs.group" + ROOT_PATH;

    private static final ResourceId[] resourceId = new ResourceId[1];

    @AfterAll
    static void cleanup() {
        if (resourceId[0] != null) {
            deleteResourceQuietly(ROOT_PATH, resourceId[0].id(), resourceId[0].version());
        }
    }

    private static final String CREATE_JSON = """
            {
              "name": "Test Discussion Group",
              "description": "A test group for integration testing",
              "members": [
                {
                  "agentId": "agent-1",
                  "displayName": "Agent Alpha",
                  "speakingOrder": 1,
                  "role": null,
                  "memberType": "AGENT"
                },
                {
                  "agentId": "agent-2",
                  "displayName": "Agent Beta",
                  "speakingOrder": 2,
                  "role": null,
                  "memberType": "AGENT"
                }
              ],
              "style": "ROUND_TABLE",
              "maxRounds": 2
            }
            """;

    private static final String UPDATE_JSON = """
            {
              "name": "Updated Discussion Group",
              "description": "An updated test group",
              "members": [
                {
                  "agentId": "agent-1",
                  "displayName": "Agent Alpha",
                  "speakingOrder": 1,
                  "role": null,
                  "memberType": "AGENT"
                },
                {
                  "agentId": "agent-2",
                  "displayName": "Agent Beta",
                  "speakingOrder": 2,
                  "role": null,
                  "memberType": "AGENT"
                },
                {
                  "agentId": "agent-3",
                  "displayName": "Agent Gamma",
                  "speakingOrder": 3,
                  "role": "DEVIL_ADVOCATE",
                  "memberType": "AGENT"
                }
              ],
              "style": "DEVIL_ADVOCATE",
              "maxRounds": 3
            }
            """;

    @Test
    @Order(1)
    @DisplayName("Create agent group configuration")
    void createGroup() {
        assertCreate(CREATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId);
    }

    @Test
    @Order(2)
    @DisplayName("Read created agent group configuration")
    void readGroup() {
        assertRead(ROOT_PATH, resourceId[0]);
    }

    @Test
    @Order(3)
    @DisplayName("Read group should have correct structure")
    void readGroup_verifyStructure() {
        given().get(ROOT_PATH + resourceId[0].id() + VERSION_STRING + resourceId[0].version())
                .then().assertThat()
                .statusCode(200)
                .body("name", equalTo("Test Discussion Group"))
                .body("members.size()", equalTo(2))
                .body("members[0].displayName", equalTo("Agent Alpha"))
                .body("style", equalTo("ROUND_TABLE"))
                .body("maxRounds", equalTo(2));
    }

    @Test
    @Order(4)
    @DisplayName("Update agent group configuration")
    void updateGroup() {
        assertUpdate(UPDATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId)
                .then().assertThat()
                .body("name", equalTo("Updated Discussion Group"))
                .body("members.size()", equalTo(3))
                .body("members[2].role", equalTo("DEVIL_ADVOCATE"))
                .body("style", equalTo("DEVIL_ADVOCATE"))
                .body("maxRounds", equalTo(3));
    }

    @Test
    @Order(5)
    @DisplayName("Discussion styles should return available styles")
    void readDiscussionStyles() {
        given().get(ROOT_PATH + "styles")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(6)
    @DisplayName("Group descriptors should return list")
    void readDescriptors() {
        given().get(ROOT_PATH + "descriptors")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(7)
    @DisplayName("Delete agent group configuration")
    void deleteGroup() {
        assertDelete(ROOT_PATH, resourceId[0]);
    }
}
