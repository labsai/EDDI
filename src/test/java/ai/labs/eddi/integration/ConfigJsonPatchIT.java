/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import ai.labs.eddi.engine.mcp.McpApiToolBuilder;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PATCH /llmstore/llms/{id}} over real HTTP: the credential-safe edit.
 * The media type, the body binding and the response filters are the parts a
 * unit test cannot see.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConfigJsonPatchIT extends BaseIntegrationIT {

    private static final String ROOT_PATH = "/llmstore/llms/";
    private static final String PATCH_TYPE = "application/json-patch+json";
    private static final ResourceId[] resourceId = new ResourceId[1];

    private static final String CREATE_JSON = """
            {"tasks":[{"actions":["help"],"type":"anthropic",
             "parameters":{"apiKey":"${vault:anthropic-api-key}","modelName":"claude-a"}}]}
            """;

    @AfterAll
    static void cleanup() {
        if (resourceId[0] != null) {
            deleteResourceQuietly(ROOT_PATH, resourceId[0].id(), resourceId[0].version());
        }
    }

    private Response patch(String body, int version) {
        return given().body(body).contentType(PATCH_TYPE).patch(ROOT_PATH + resourceId[0].id() + VERSION_STRING + version);
    }

    @Test
    @Order(1)
    @DisplayName("create an LLM config whose key is a vault reference")
    void create() {
        assertCreate(CREATE_JSON, ROOT_PATH, "eddi://ai.labs.llm" + ROOT_PATH, resourceId);
    }

    @Test
    @Order(2)
    @DisplayName("PATCH stores a new version, returns it in Location, and leaves the stored reference alone")
    void patchStoresANewVersion() {
        Response response = patch("[{\"op\":\"replace\",\"path\":\"/tasks/0/parameters/modelName\",\"value\":\"claude-b\"}]", 1);

        response.then().statusCode(200).header("location", startsWith("eddi://ai.labs.llm" + ROOT_PATH)).header("location",
                endsWith(VERSION_STRING + "2"));
        resourceId[0] = extractResourceId(response.getHeader("location"));

        given().get(ROOT_PATH + resourceId[0].id() + VERSION_STRING + resourceId[0].version()).then().statusCode(200)
                .body("tasks[0].parameters.modelName", equalTo("claude-b")).body("tasks[0].parameters.apiKey", equalTo("${vault:anthropic-api-key}"));
    }

    @Test
    @Order(3)
    @DisplayName("a stale version is 409 and names the current one")
    void staleVersionIs409() {
        patch("[{\"op\":\"replace\",\"path\":\"/tasks/0/parameters/modelName\",\"value\":\"claude-c\"}]", 1).then().statusCode(409)
                .body(containsString("version=2"));
    }

    @Test
    @Order(4)
    @DisplayName("a failed test operation and a bad path are 400")
    void badPatchIs400() {
        patch("[{\"op\":\"test\",\"path\":\"/tasks/0/parameters/modelName\",\"value\":\"nope\"}]", 2).then().statusCode(400);
        patch("[{\"op\":\"remove\",\"path\":\"/tasks/5\"}]", 2).then().statusCode(400);
        patch("{\"not\":\"an array\"}", 2).then().statusCode(400);
        patch("[{\"op\":\"add\",\"path\":\"/tasks/0/enableBuiltInToolz\",\"value\":true}]", 2).then().statusCode(400)
                .body(containsString("enableBuiltInToolz"));
    }

    @Test
    @Order(5)
    @DisplayName("EDDI's own OpenAPI spec yields a usable operator tool for each PATCH: json-patch body, a body parameter")
    void generatedOperatorToolsCarryTheBody() {
        String spec = given().get("/openapi?format=json").then().statusCode(200).extract().asString();
        for (String path : new String[]{"/llmstore/llms/{id}", "/apicallstore/apicalls/{id}", "/mcpcallsstore/mcpcalls/{id}"}) {
            var result = McpApiToolBuilder.parseAndBuild(spec, "PATCH " + path, "http://localhost", null);
            var calls = result.configsByGroup().values().stream().flatMap(c -> c.getHttpCalls().stream()).toList();
            assertEquals(1, calls.size(), path);
            var request = calls.get(0).getRequest();
            assertEquals("application/json-patch+json", request.getContentType(), path);
            assertTrue(request.getBody().contains("{requestBody}"), path + " has no body variable: " + request.getBody());
            assertTrue(calls.get(0).getParameters().containsKey("requestBody"), path);
            assertTrue(calls.get(0).getParameters().containsKey("version"), path);
        }
    }

    @Test
    @Order(6)
    @DisplayName("the PUT of the same path with plain JSON is unaffected")
    void putStillWorks() {
        given().body(CREATE_JSON).contentType(ContentType.JSON).put(ROOT_PATH + resourceId[0].id() + VERSION_STRING + 2).then().statusCode(200);
        resourceId[0] = new ResourceId(resourceId[0].id(), 3);
    }
}
