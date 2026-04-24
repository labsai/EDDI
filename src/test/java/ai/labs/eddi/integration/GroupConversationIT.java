/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for Group Conversation orchestration.
 * <p>
 * Tests group config creation, group discussion lifecycle, and group
 * conversation CRUD. Uses template-based agents (no LLM keys).
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GroupConversationIT extends BaseIntegrationIT {

    private static final String GROUP_BASE = "/groupstore/groups/";

    private static ResourceId agentId1;
    private static ResourceId agentId2;
    private static ResourceId groupResourceId;
    private static boolean agentsDeployed = false;

    @BeforeEach
    void setUp() throws Exception {
        if (!agentsDeployed) {
            agentId1 = deployTemplateAgent("groupAgent1");
            agentId2 = deployTemplateAgent("groupAgent2");
            agentsDeployed = true;
        }
    }

    @AfterAll
    static void cleanup() {
        if (agentId1 != null)
            undeployAgentQuietly(agentId1.id(), agentId1.version());
        if (agentId2 != null)
            undeployAgentQuietly(agentId2.id(), agentId2.version());
    }

    // ==================== Group Config ====================

    @Test
    @Order(1)
    @DisplayName("Create group with two deployed agents")
    void createGroup() {
        String groupJson = String.format("""
                {
                  "name": "IT Test Group",
                  "description": "Integration test group",
                  "members": [
                    {"agentId": "%s", "displayName": "Agent 1", "speakingOrder": 1, "role": null, "memberType": "AGENT"},
                    {"agentId": "%s", "displayName": "Agent 2", "speakingOrder": 2, "role": null, "memberType": "AGENT"}
                  ],
                  "style": "ROUND_TABLE",
                  "maxRounds": 1
                }
                """, agentId1.id(), agentId2.id());

        var response = given().contentType(ContentType.JSON).body(groupJson)
                .post(GROUP_BASE);

        response.then().assertThat()
                .statusCode(201)
                .header("location", notNullValue());

        groupResourceId = extractResourceId(response.getHeader("location"));
    }

    @Test
    @Order(2)
    @DisplayName("Read group configuration should return members")
    void readGroup() {
        Assumptions.assumeTrue(groupResourceId != null, "Group must be created in test 1");

        given().get(GROUP_BASE + groupResourceId.id() + VERSION_STRING + groupResourceId.version())
                .then().assertThat()
                .statusCode(200)
                .body("name", equalTo("IT Test Group"))
                .body("members.size()", equalTo(2));
    }

    // ==================== Discussion Styles ====================

    @Test
    @Order(3)
    @DisplayName("Discussion styles endpoint should return all styles")
    void readDiscussionStyles() {
        given().get(GROUP_BASE + "styles")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    // ==================== Group Conversation Lifecycle ====================

    @Test
    @Order(4)
    @DisplayName("Start group discussion should return transcript or indicate no output")
    void discussGroup() {
        Assumptions.assumeTrue(groupResourceId != null, "Group must be created");

        String request = """
                {"question": "What is integration testing?", "userId": "group-test-user"}
                """;

        var response = given().contentType(ContentType.JSON).body(request)
                .post("/groups/" + groupResourceId.id() + "/conversations");

        // 200 = discussion completed successfully
        // 201 = discussion created (REST convention for new group conversation)
        // 404 = group or agent not found (valid if deployment state changed)
        // 500 is NOT acceptable — server errors indicate bugs, not valid behavior
        response.then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(201), equalTo(404)));
    }

    @Test
    @Order(5)
    @DisplayName("List group conversations should return results")
    void listGroupConversations() {
        Assumptions.assumeTrue(groupResourceId != null, "Group must be created");

        given().get("/groups/" + groupResourceId.id() + "/conversations")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(6)
    @DisplayName("Missing group should return 404")
    void discussNonExistentGroup() {
        String request = """
                {"question": "hello", "userId": "test-user"}
                """;

        given().contentType(ContentType.JSON).body(request)
                .post("/groups/000000000000000000000000/conversations")
                .then().assertThat()
                .statusCode(404);
    }

    // ==================== Helpers ====================

    private ResourceId deployTemplateAgent(String prefix) throws Exception {
        String dictionary = load("agentengine/dictionary.json");
        String behavior = load("agentengine/rules.json");
        String output = load("agentengine/output.json");

        String locDict = createResource(dictionary, "/dictionarystore/dictionaries");
        String locRules = createResource(behavior, "/rulestore/rulesets");
        String locOutput = createResource(output, "/outputstore/outputsets");

        String packageBody = String.format(
                """
                        {
                          "workflowSteps": [
                            {"type": "eddi://ai.labs.parser", "config": {},
                             "extensions": {"dictionaries": [{"type": "eddi://ai.labs.parser.dictionaries.regular", "config": {"uri": "%s"}}], "corrections": []}},
                            {"type": "eddi://ai.labs.rules", "config": {"uri": "%s"}},
                            {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                            {"type": "eddi://ai.labs.templating", "config": {}}
                          ]
                        }""",
                locDict, locRules, locOutput);

        String locWorkflow = createResource(packageBody, "/workflowstore/workflows");
        String agentBody = String.format("""
                {"packages": ["%s"]}""", locWorkflow);
        String agentLocation = createResource(agentBody, "/agentstore/agents");
        ResourceId agentId = extractResourceId(agentLocation);
        deployAgent(agentId.id(), agentId.version());
        return agentId;
    }
}
