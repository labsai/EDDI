/*
 * Copyright EDDI contributors
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
 * Integration test for Global Variable CRUD operations.
 * <p>
 * Tests Create → Read → Update → Delete lifecycle for
 * {@code /variablestore/variables/{tenantId}/{key}}. Uses the default tenant
 * ({@code "default"}) for simplicity.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GlobalVariableCrudIT extends BaseIntegrationIT {

    private static final String BASE_PATH = "/variablestore/variables";
    private static final String TENANT = "default";
    private static final String TEST_KEY = "it-test-model";

    @AfterAll
    static void cleanup() {
        try {
            given().delete(BASE_PATH + "/" + TENANT + "/" + TEST_KEY);
        } catch (Exception ignored) {
        }
    }

    @Test
    @Order(1)
    @DisplayName("List variables for default tenant (initially empty or existing)")
    void listVariables() {
        given().get(BASE_PATH + "/" + TENANT)
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    @Order(2)
    @DisplayName("Create a global variable via PUT")
    void createVariable() {
        String body = """
                {
                  "key": "%s",
                  "value": "gpt-4.1",
                  "description": "Integration test model",
                  "exportable": true
                }
                """.formatted(TEST_KEY);

        given().contentType(ContentType.JSON)
                .body(body)
                .put(BASE_PATH + "/" + TENANT + "/" + TEST_KEY)
                .then().assertThat()
                .statusCode(200);
    }

    @Test
    @Order(3)
    @DisplayName("Read created variable by tenant and key")
    void readVariable() {
        given().get(BASE_PATH + "/" + TENANT + "/" + TEST_KEY)
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("key", equalTo(TEST_KEY))
                .body("tenantId", equalTo(TENANT))
                .body("value", equalTo("gpt-4.1"))
                .body("description", equalTo("Integration test model"))
                .body("exportable", equalTo(true));
    }

    @Test
    @Order(4)
    @DisplayName("Update variable value via PUT")
    void updateVariable() {
        String body = """
                {
                  "key": "%s",
                  "value": "gpt-4.1-mini",
                  "description": "Updated integration test model",
                  "exportable": false
                }
                """.formatted(TEST_KEY);

        given().contentType(ContentType.JSON)
                .body(body)
                .put(BASE_PATH + "/" + TENANT + "/" + TEST_KEY)
                .then().assertThat()
                .statusCode(200);

        // Verify update
        given().get(BASE_PATH + "/" + TENANT + "/" + TEST_KEY)
                .then().assertThat()
                .statusCode(200)
                .body("value", equalTo("gpt-4.1-mini"))
                .body("description", equalTo("Updated integration test model"))
                .body("exportable", equalTo(false));
    }

    @Test
    @Order(5)
    @DisplayName("Variable appears in tenant's list")
    void variableInList() {
        given().get(BASE_PATH + "/" + TENANT)
                .then().assertThat()
                .statusCode(200)
                .body("key", hasItem(TEST_KEY));
    }

    @Test
    @Order(6)
    @DisplayName("GET non-existent key returns 404")
    void getNonExistent() {
        given().get(BASE_PATH + "/" + TENANT + "/does-not-exist-" + System.currentTimeMillis())
                .then().assertThat()
                .statusCode(404);
    }

    @Test
    @Order(7)
    @DisplayName("PUT with invalid key pattern returns 400")
    void invalidKey() {
        String body = """
                { "key": "bad key!", "value": "v" }
                """;

        given().contentType(ContentType.JSON)
                .body(body)
                .put(BASE_PATH + "/" + TENANT + "/bad key!")
                .then().assertThat()
                .statusCode(400);
    }

    @Test
    @Order(8)
    @DisplayName("Delete variable")
    void deleteVariable() {
        given().delete(BASE_PATH + "/" + TENANT + "/" + TEST_KEY)
                .then().assertThat()
                .statusCode(204);

        // Verify deletion
        given().get(BASE_PATH + "/" + TENANT + "/" + TEST_KEY)
                .then().assertThat()
                .statusCode(404);
    }
}
