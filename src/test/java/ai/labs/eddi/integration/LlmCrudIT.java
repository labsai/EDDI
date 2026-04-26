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
 * Integration test for LLM Configuration CRUD operations.
 * <p>
 * Tests Create → Read → Update → Delete lifecycle for {@code /llmstore/llms}
 * plus JSON Schema and descriptor endpoints.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LlmCrudIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/llmstore/llms/";
    private static final String RESOURCE_URI = "eddi://ai.labs.llm" + ROOT_PATH;

    private static final ResourceId[] resourceId = new ResourceId[1];

    @AfterAll
    static void cleanup() {
        if (resourceId[0] != null) {
            deleteResourceQuietly(ROOT_PATH, resourceId[0].id(), resourceId[0].version());
        }
    }

    private static final String CREATE_JSON = """
            {
              "tasks": [
                {
                  "id": "test-llm",
                  "type": "openai",
                  "description": "Test LLM configuration",
                  "actions": ["send_message"],
                  "parameters": {
                    "systemMessage": "You are a test assistant.",
                    "addToOutput": "true",
                    "apiKey": "sk-test-not-real",
                    "modelName": "gpt-4o-mini",
                    "timeout": "60",
                    "temperature": "0.3"
                  },
                  "conversationHistoryLimit": 10
                }
              ]
            }
            """;

    private static final String UPDATE_JSON = """
            {
              "tasks": [
                {
                  "id": "test-llm-updated",
                  "type": "openai",
                  "description": "Updated LLM configuration",
                  "actions": ["send_message"],
                  "parameters": {
                    "systemMessage": "You are an updated test assistant.",
                    "addToOutput": "true",
                    "apiKey": "sk-test-not-real",
                    "modelName": "gpt-4o",
                    "timeout": "120",
                    "temperature": "0.7"
                  },
                  "conversationHistoryLimit": 20
                }
              ]
            }
            """;

    @Test
    @Order(1)
    @DisplayName("Create LLM configuration")
    void createLlm() {
        assertCreate(CREATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId);
    }

    @Test
    @Order(2)
    @DisplayName("Read created LLM configuration")
    void readLlm() {
        assertRead(ROOT_PATH, resourceId[0]);
    }

    @Test
    @Order(3)
    @DisplayName("Read LLM configuration should have correct structure")
    void readLlm_verifyStructure() {
        given().get(ROOT_PATH + resourceId[0].id() + VERSION_STRING + resourceId[0].version())
                .then().assertThat()
                .statusCode(200)
                .body("tasks[0].id", equalTo("test-llm"))
                .body("tasks[0].type", equalTo("openai"))
                .body("tasks[0].parameters.modelName", equalTo("gpt-4o-mini"))
                .body("tasks[0].conversationHistoryLimit", equalTo(10));
    }

    @Test
    @Order(4)
    @DisplayName("Update LLM configuration")
    void updateLlm() {
        assertUpdate(UPDATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId)
                .then().assertThat()
                .body("tasks[0].id", equalTo("test-llm-updated"))
                .body("tasks[0].parameters.modelName", equalTo("gpt-4o"))
                .body("tasks[0].conversationHistoryLimit", equalTo(20));
    }

    @Test
    @Order(5)
    @DisplayName("LLM descriptors should return list")
    void readDescriptors() {
        given().get(ROOT_PATH + "descriptors")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(6)
    @DisplayName("LLM JSON Schema should return valid schema")
    void readJsonSchema() {
        given().get(ROOT_PATH + "jsonSchema")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(7)
    @DisplayName("Delete LLM configuration")
    void deleteLlm() {
        assertDelete(ROOT_PATH, resourceId[0]);
    }
}
