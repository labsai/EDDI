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
 * Integration test for RAG (Knowledge Base) Configuration CRUD operations.
 * <p>
 * Tests Create → Read → Update → Delete lifecycle for {@code /ragstore/rags}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RagCrudIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/ragstore/rags/";
    private static final String RESOURCE_URI = "eddi://ai.labs.rag" + ROOT_PATH;

    private static final ResourceId[] resourceId = new ResourceId[1];

    @AfterAll
    static void cleanup() {
        if (resourceId[0] != null) {
            deleteResourceQuietly(ROOT_PATH, resourceId[0].id(), resourceId[0].version());
        }
    }

    private static final String CREATE_JSON = """
            {
              "name": "test-knowledge-base",
              "embeddingProvider": "openai",
              "embeddingParameters": {
                "model": "text-embedding-3-small",
                "apiKey": "sk-test-not-real"
              },
              "storeType": "in-memory",
              "chunkStrategy": "recursive",
              "chunkSize": 512,
              "chunkOverlap": 64,
              "maxResults": 5,
              "minScore": 0.7
            }
            """;

    private static final String UPDATE_JSON = """
            {
              "name": "test-knowledge-base-updated",
              "embeddingProvider": "openai",
              "embeddingParameters": {
                "model": "text-embedding-3-large",
                "apiKey": "sk-test-not-real"
              },
              "storeType": "in-memory",
              "chunkStrategy": "paragraph",
              "chunkSize": 1024,
              "chunkOverlap": 128,
              "maxResults": 10,
              "minScore": 0.5
            }
            """;

    @Test
    @Order(1)
    @DisplayName("Create RAG configuration")
    void createRag() {
        assertCreate(CREATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId);
    }

    @Test
    @Order(2)
    @DisplayName("Read created RAG configuration")
    void readRag() {
        assertRead(ROOT_PATH, resourceId[0]);
    }

    @Test
    @Order(3)
    @DisplayName("Read RAG should have correct structure")
    void readRag_verifyStructure() {
        given().get(ROOT_PATH + resourceId[0].id() + VERSION_STRING + resourceId[0].version())
                .then().assertThat()
                .statusCode(200)
                .body("name", equalTo("test-knowledge-base"))
                .body("embeddingProvider", equalTo("openai"))
                .body("storeType", equalTo("in-memory"))
                .body("maxResults", equalTo(5))
                .body("minScore", equalTo(0.7f));
    }

    @Test
    @Order(4)
    @DisplayName("RAG JSON Schema should return valid schema")
    void readJsonSchema() {
        given().get(ROOT_PATH + "jsonSchema")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(5)
    @DisplayName("RAG descriptors should return list")
    void readDescriptors() {
        given().get(ROOT_PATH + "descriptors")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(6)
    @DisplayName("Update RAG configuration")
    void updateRag() {
        assertUpdate(UPDATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId)
                .then().assertThat()
                .body("name", equalTo("test-knowledge-base-updated"))
                .body("chunkStrategy", equalTo("paragraph"))
                .body("maxResults", equalTo(10));
    }

    @Test
    @Order(7)
    @DisplayName("Delete RAG configuration")
    void deleteRag() {
        assertDelete(ROOT_PATH, resourceId[0]);
    }
}
