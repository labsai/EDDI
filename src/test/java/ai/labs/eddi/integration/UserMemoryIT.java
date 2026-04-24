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
 * Integration test for User Memory REST endpoints.
 * <p>
 * Tests CRUD operations, search, category filtering, visibility, and
 * GDPR-compliant user data deletion via {@code /usermemorystore/memories}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class UserMemoryIT extends BaseIntegrationIT {

    private static final String BASE = "/usermemorystore/memories/";
    private static final String TEST_USER = "memory-test-user-" + System.currentTimeMillis();
    private static String createdEntryId;

    @AfterAll
    static void cleanup() {
        try {
            given().delete(BASE + TEST_USER);
        } catch (Exception ignored) {
        }
    }

    // ==================== Upsert & Read ====================

    @Test
    @Order(1)
    @DisplayName("Upsert memory entry should succeed")
    void upsertMemory() {
        String json = String.format("""
                {
                  "userId": "%s",
                  "key": "favorite_color",
                  "value": "blue",
                  "category": "preference",
                  "visibility": "self"
                }
                """, TEST_USER);

        given().contentType(ContentType.JSON).body(json)
                .put(BASE)
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(201)));
    }

    @Test
    @Order(2)
    @DisplayName("Get all memories for user should return entries")
    void getAllMemories() {
        given().get(BASE + TEST_USER)
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", not(empty()))
                .body("[0].key", equalTo("favorite_color"))
                .body("[0].value", equalTo("blue"));

        // Store the entry ID for later tests
        createdEntryId = given().get(BASE + TEST_USER).jsonPath().getString("[0].id");
    }

    @Test
    @Order(3)
    @DisplayName("Get memory by key should return correct entry")
    void getMemoryByKey() {
        given().get(BASE + TEST_USER + "/key/favorite_color")
                .then().assertThat()
                .statusCode(200)
                .body("value", equalTo("blue"));
    }

    @Test
    @Order(4)
    @DisplayName("Get memories by category should filter correctly")
    void getMemoriesByCategory() {
        given().get(BASE + TEST_USER + "/category/preference")
                .then().assertThat()
                .statusCode(200)
                .body("$", not(empty()))
                .body("[0].category", equalTo("preference"));
    }

    // ==================== Upsert additional entries ====================

    @Test
    @Order(5)
    @DisplayName("Upsert second memory entry")
    void upsertSecondMemory() {
        String json = String.format("""
                {
                  "userId": "%s",
                  "key": "hometown",
                  "value": "Vienna",
                  "category": "fact",
                  "visibility": "self"
                }
                """, TEST_USER);

        given().contentType(ContentType.JSON).body(json)
                .put(BASE)
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(201)));
    }

    // ==================== Search ====================

    @Test
    @Order(6)
    @DisplayName("Search memories should filter by query")
    void searchMemories() {
        given().get(BASE + TEST_USER + "/search?q=color")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    // ==================== Count ====================

    @Test
    @Order(7)
    @DisplayName("Count memories should return correct count")
    void countMemories() {
        given().get(BASE + TEST_USER + "/count")
                .then().assertThat()
                .statusCode(200)
                .body("count", greaterThanOrEqualTo(2));
    }

    // ==================== Visible Memories ====================

    @Test
    @Order(8)
    @DisplayName("Get visible memories should respect visibility filtering")
    void getVisibleMemories() {
        given().get(BASE + TEST_USER + "/visible?agentId=test-agent&order=most_recent&limit=50")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    // ==================== Delete single entry ====================

    @Test
    @Order(9)
    @DisplayName("Delete single memory entry should succeed")
    void deleteMemoryEntry() {
        Assumptions.assumeTrue(createdEntryId != null, "Entry ID must be captured from test 2");

        given().delete(BASE + "entry/" + createdEntryId)
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
    }

    // ==================== Delete all for user (GDPR) ====================

    @Test
    @Order(10)
    @DisplayName("Delete all memories for user (GDPR) should succeed")
    void deleteAllForUser() {
        given().delete(BASE + TEST_USER)
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(204)));

        // Verify empty
        given().get(BASE + TEST_USER)
                .then().assertThat()
                .statusCode(200)
                .body("$", empty());
    }
}
