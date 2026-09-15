/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.*;

import java.io.IOException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration test for Behavior Rules CRUD operations.
 * <p>
 * Ported from {@code RestBehaviorTest} in EDDI-integration-tests.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RulesCrudIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/rulestore/rulesets/";
    private static final String RESOURCE_URI = "eddi://ai.labs.rules" + ROOT_PATH;

    private static String TEST_JSON;
    private static String TEST_JSON2;
    private static final ResourceId[] resourceId = new ResourceId[1];

    @AfterAll
    static void cleanup() {
        if (resourceId[0] != null) {
            deleteResourceQuietly(ROOT_PATH, resourceId[0].id(), resourceId[0].version());
        }
    }

    @BeforeAll
    static void loadResources() throws IOException {
        TEST_JSON = load("rules/createRules.json");
        TEST_JSON2 = load("rules/updateRules.json");
    }

    @Test
    @Order(1)
    @DisplayName("Create behavior rule set")
    void createBehavior() {
        assertCreate(TEST_JSON, ROOT_PATH, RESOURCE_URI, resourceId);
    }

    @Test
    @Order(2)
    @DisplayName("Read created behavior rule set")
    void readBehavior() {
        assertRead(ROOT_PATH, resourceId[0]);
    }

    @Test
    @Order(3)
    @DisplayName("Update behavior rule set")
    void updateBehavior() {
        // behaviorRules, not rules. The fixtures — like every other authored
        // artefact — have always written behaviorRules, while the response came
        // back saying rules, because RuleGroupConfiguration's getRules/setRules
        // pair gave Jackson that implicit name. This assertion was the only place
        // in the repository that agreed with the response instead of the request,
        // which is why nothing caught the Manager rendering every rule group as
        // empty.
        assertUpdate(TEST_JSON2, ROOT_PATH, RESOURCE_URI, resourceId).then().assertThat()
                .body("behaviorGroups[0].behaviorRules[0].name", equalTo("Welcome_changed"))
                .body("behaviorGroups[0].rules", nullValue());
    }

    @Test
    @Order(4)
    @DisplayName("Delete behavior rule set")
    void deleteBehavior() {
        assertDelete(ROOT_PATH, resourceId[0]);
    }

    /**
     * The compatibility half of the same change, over HTTP rather than through the
     * mapper: a client that learned the old spelling must keep working, and must
     * get the canonical one back.
     * <p>
     * Ordered last and cleaning up after itself so it cannot disturb the CRUD
     * sequence above, which shares {@code resourceId} across ordered methods.
     */
    @Test
    @Order(5)
    @DisplayName("A rule set posted with the legacy 'rules' key is accepted, and reads back as 'behaviorRules'")
    void legacyRulesKeyIsStillAccepted() {
        String legacyBody = """
                {
                  "behaviorGroups": [
                    {
                      "name": "Smalltalk",
                      "rules": [ { "name": "Welcome", "actions": ["welcome"], "conditions": [] } ]
                    }
                  ]
                }
                """;

        var created = new ResourceId[1];
        assertCreate(legacyBody, ROOT_PATH, RESOURCE_URI, created);
        try {
            given().get(ROOT_PATH + created[0].id() + VERSION_STRING + created[0].version())
                    .then().assertThat().statusCode(200)
                    .body("behaviorGroups[0].behaviorRules[0].name", equalTo("Welcome"))
                    .body("behaviorGroups[0].rules", nullValue());
        } finally {
            deleteResourceQuietly(ROOT_PATH, created[0].id(), created[0].version());
        }
    }
}
