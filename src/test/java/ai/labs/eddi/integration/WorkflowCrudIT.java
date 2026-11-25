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
 * Integration test for Workflow Configuration CRUD operations.
 * <p>
 * Tests Create → Read → Update → Delete lifecycle for
 * {@code /workflowstore/workflows}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WorkflowCrudIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/workflowstore/workflows/";
    private static final String RESOURCE_URI = "eddi://ai.labs.workflow" + ROOT_PATH;

    private static final ResourceId[] resourceId = new ResourceId[1];

    @AfterAll
    static void cleanup() {
        if (resourceId[0] != null) {
            deleteResourceQuietly(ROOT_PATH, resourceId[0].id(), resourceId[0].version());
        }
    }

    private static final String CREATE_JSON = """
            {
              "workflowSteps": [
                {
                  "type": "eddi://ai.labs.parser",
                  "config": {}
                },
                {
                  "type": "eddi://ai.labs.rules",
                  "config": {
                    "uri": "eddi://ai.labs.rules/rulestore/rulesets/0000000000000000000000a1?version=1"
                  }
                },
                {
                  "type": "eddi://ai.labs.templating",
                  "config": {}
                }
              ]
            }
            """;

    private static final String UPDATE_JSON = """
            {
              "workflowSteps": [
                {
                  "type": "eddi://ai.labs.parser",
                  "config": {}
                },
                {
                  "type": "eddi://ai.labs.rules",
                  "config": {
                    "uri": "eddi://ai.labs.rules/rulestore/rulesets/0000000000000000000000a1?version=1"
                  }
                },
                {
                  "type": "eddi://ai.labs.output",
                  "config": {}
                },
                {
                  "type": "eddi://ai.labs.templating",
                  "config": {}
                }
              ]
            }
            """;

    @Test
    @Order(1)
    @DisplayName("Create workflow configuration")
    void createWorkflow() {
        assertCreate(CREATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId);
    }

    @Test
    @Order(2)
    @DisplayName("Read created workflow configuration")
    void readWorkflow() {
        assertRead(ROOT_PATH, resourceId[0]);
    }

    @Test
    @Order(3)
    @DisplayName("Read workflow should have correct step order")
    void readWorkflow_verifyStructure() {
        given().get(ROOT_PATH + resourceId[0].id() + VERSION_STRING + resourceId[0].version())
                .then().assertThat()
                .statusCode(200)
                .body("workflowSteps.size()", equalTo(3))
                .body("workflowSteps[0].type", equalTo("eddi://ai.labs.parser"))
                .body("workflowSteps[1].type", equalTo("eddi://ai.labs.rules"))
                .body("workflowSteps[2].type", equalTo("eddi://ai.labs.templating"));
    }

    @Test
    @Order(4)
    @DisplayName("Update workflow with added step")
    void updateWorkflow() {
        assertUpdate(UPDATE_JSON, ROOT_PATH, RESOURCE_URI, resourceId)
                .then().assertThat()
                .body("workflowSteps.size()", equalTo(4))
                .body("workflowSteps[2].type", equalTo("eddi://ai.labs.output"));
    }

    @Test
    @Order(5)
    @DisplayName("Workflow descriptors should return list")
    void readDescriptors() {
        given().get(ROOT_PATH + "descriptors")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(6)
    @DisplayName("Workflow JSON Schema should return valid schema")
    void readJsonSchema() {
        given().get(ROOT_PATH + "jsonSchema")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    /**
     * Save-time validation runs with the real step registry: an unknown step type
     * and a rules step without a ruleset are refused with 400, not saved and left
     * to fail at deployment. The unknown type also checks that the registry is read
     * through the CDI client proxy (a field read on the proxy is null and used to
     * skip this check silently).
     */
    @Test
    @Order(8)
    @DisplayName("A workflow that cannot deploy is refused with 400")
    void invalidWorkflowIsRefused() {
        given().body("""
                {"workflowSteps": [{"type": "eddi://ai.labs.nope", "config": {}}]}
                """).contentType(ContentType.JSON).post(ROOT_PATH)
                .then().assertThat().statusCode(400);
        given().body("""
                {"workflowSteps": [{"type": "eddi://ai.labs.rules", "config": {}}]}
                """).contentType(ContentType.JSON).post(ROOT_PATH)
                .then().assertThat().statusCode(400);
    }

    @Test
    @Order(7)
    @DisplayName("Delete workflow configuration")
    void deleteWorkflow() {
        assertDelete(ROOT_PATH, resourceId[0]);
    }
}
