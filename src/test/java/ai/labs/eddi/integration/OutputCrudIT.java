/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.*;

import java.io.IOException;

import static org.hamcrest.Matchers.endsWith;

/**
 * Integration test for Output Sets CRUD operations.
 * <p>
 * Ported from {@code RestOutputTest} in EDDI-integration-tests.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OutputCrudIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/outputstore/outputsets/";
    private static final String RESOURCE_URI = "eddi://ai.labs.output" + ROOT_PATH;

    private static String TEST_JSON;
    private static String TEST_JSON2;
    private static String PATCH_JSON;
    private static final ResourceId[] resourceId = new ResourceId[1];

    @AfterAll
    static void cleanup() {
        if (resourceId[0] != null) {
            deleteResourceQuietly(ROOT_PATH, resourceId[0].id(), resourceId[0].version());
        }
    }

    @BeforeAll
    static void loadResources() throws IOException {
        TEST_JSON = load("output/createOutput.json");
        TEST_JSON2 = load("output/updateOutput.json");
        PATCH_JSON = load("output/patchOutput.json");
    }

    @Test
    @Order(1)
    @DisplayName("Create output set")
    void createOutput() {
        assertCreate(TEST_JSON, ROOT_PATH, RESOURCE_URI, resourceId);
    }

    @Test
    @Order(2)
    @DisplayName("Read created output set")
    void readOutput() {
        assertRead(ROOT_PATH, resourceId[0]);
    }

    @Test
    @Order(3)
    @DisplayName("Update output set")
    void updateOutput() {
        assertUpdate(TEST_JSON2, ROOT_PATH, RESOURCE_URI, resourceId).then().assertThat().body("outputSet[0].outputs[0].valueAlternatives[0].text",
                endsWith("--changed!"));
    }

    @Test
    @Order(4)
    @DisplayName("Patch output set")
    void patchOutput() {
        assertPatch(PATCH_JSON, ROOT_PATH, RESOURCE_URI, resourceId).then().assertThat().body("outputSet[5].outputs[0].valueAlternatives[0].text",
                endsWith("--changed-again!"));
    }

    @Test
    @Order(5)
    @DisplayName("Delete output set")
    void deleteOutput() {
        assertDelete(ROOT_PATH, resourceId[0]);
    }
}
