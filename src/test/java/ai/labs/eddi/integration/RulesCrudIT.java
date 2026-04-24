/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.*;

import java.io.IOException;

import static org.hamcrest.Matchers.equalTo;

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
        assertUpdate(TEST_JSON2, ROOT_PATH, RESOURCE_URI, resourceId).then().assertThat().body("behaviorGroups[0].rules[0].name",
                equalTo("Welcome_changed"));
    }

    @Test
    @Order(4)
    @DisplayName("Delete behavior rule set")
    void deleteBehavior() {
        assertDelete(ROOT_PATH, resourceId[0]);
    }
}
