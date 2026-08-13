/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class McpApiToolBuilderTest {

    /**
     * Minimal Petstore-style OpenAPI 3.0 spec with two tags (pets, store), path
     * params, query params, and a request body.
     */
    private static final String PETSTORE_SPEC = """
            {
              "openapi": "3.0.3",
              "info": { "title": "Petstore", "version": "1.0.0" },
              "servers": [{ "url": "https://petstore.example.com/v1" }],
              "paths": {
                "/pets": {
                  "get": {
                    "operationId": "listPets",
                    "summary": "List all pets",
                    "tags": ["pets"],
                    "parameters": [
                      { "name": "limit", "in": "query", "description": "Max items to return", "schema": { "type": "integer" } },
                      { "name": "status", "in": "query", "description": "Filter by status", "schema": { "type": "string" } }
                    ]
                  },
                  "post": {
                    "operationId": "createPet",
                    "summary": "Create a pet",
                    "tags": ["pets"],
                    "requestBody": {
                      "content": {
                        "application/json": {
                          "schema": {
                            "type": "object",
                            "properties": {
                              "name": { "type": "string" },
                              "age": { "type": "integer" }
                            }
                          }
                        }
                      }
                    }
                  }
                },
                "/pets/{petId}": {
                  "get": {
                    "operationId": "getPet",
                    "summary": "Get a pet by ID",
                    "tags": ["pets"],
                    "parameters": [
                      { "name": "petId", "in": "path", "required": true, "description": "The pet ID", "schema": { "type": "string" } }
                    ]
                  },
                  "delete": {
                    "operationId": "deletePet",
                    "summary": "Delete a pet",
                    "tags": ["pets"],
                    "deprecated": true,
                    "parameters": [
                      { "name": "petId", "in": "path", "required": true, "schema": { "type": "string" } }
                    ]
                  }
                },
                "/store/inventory": {
                  "get": {
                    "operationId": "getInventory",
                    "summary": "Returns pet inventories",
                    "tags": ["store"]
                  }
                },
                "/store/order": {
                  "post": {
                    "operationId": "placeOrder",
                    "summary": "Place an order",
                    "tags": ["store"],
                    "requestBody": {
                      "content": {
                        "application/json": {
                          "schema": {
                            "type": "object",
                            "properties": {
                              "petId": { "type": "string" },
                              "quantity": { "type": "integer" }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    @Test
    void parseSpec_validJson_returnsOpenAPI() {
        var openAPI = McpApiToolBuilder.parseSpec(PETSTORE_SPEC);
        assertNotNull(openAPI);
        assertNotNull(openAPI.getPaths());
        assertEquals("Petstore", openAPI.getInfo().getTitle());
    }

    @Test
    void parseAndBuild_petStore_groupsByTag() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);

        // Should have 2 groups: "pets" and "store"
        assertEquals(2, result.configsByGroup().size());
        assertTrue(result.configsByGroup().containsKey("pets"));
        assertTrue(result.configsByGroup().containsKey("store"));

        // pets group: listPets, createPet, getPet (deletePet is deprecated → skipped)
        var petsConfig = result.configsByGroup().get("pets");
        assertEquals(3, petsConfig.getHttpCalls().size());

        // store group: getInventory, placeOrder
        var storeConfig = result.configsByGroup().get("store");
        assertEquals(2, storeConfig.getHttpCalls().size());

        // Total endpoints = 5 (deprecated deletePet skipped)
        assertEquals(5, result.endpointCount());
    }

    @Test
    void parseAndBuild_setsBaseUrlFromSpec() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        for (var config : result.configsByGroup().values()) {
            assertEquals("https://petstore.example.com/v1", config.getTargetServerUrl());
        }
    }

    @Test
    void parseAndBuild_overridesBaseUrl() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, "https://custom-api.example.com", null);
        for (var config : result.configsByGroup().values()) {
            assertEquals("https://custom-api.example.com", config.getTargetServerUrl());
        }
    }

    @Test
    void parseAndBuild_setsAuthHeader() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, "Bearer sk-test-key");

        // Every ApiCall should have the Authorization header
        for (var config : result.configsByGroup().values()) {
            for (ApiCall call : config.getHttpCalls()) {
                assertEquals("Bearer sk-test-key", call.getRequest().getHeaders().get("Authorization"));
            }
        }
    }

    @Test
    void parseAndBuild_filtersEndpoints() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, "GET /pets,POST /store/order", null, null);

        // Only 2 endpoints should be included
        assertEquals(2, result.endpointCount());

        // "pets" group should only have listPets (GET /pets)
        var petsConfig = result.configsByGroup().get("pets");
        assertNotNull(petsConfig);
        assertEquals(1, petsConfig.getHttpCalls().size());
        assertEquals("listPets", petsConfig.getHttpCalls().get(0).getName());

        // "store" group should only have placeOrder (POST /store/order)
        var storeConfig = result.configsByGroup().get("store");
        assertNotNull(storeConfig);
        assertEquals(1, storeConfig.getHttpCalls().size());
        assertEquals("placeOrder", storeConfig.getHttpCalls().get(0).getName());
    }

    @Test
    void parseAndBuild_skipsDeprecated() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        // deletePet is deprecated and should be skipped
        boolean hasDeletePet = petsConfig.getHttpCalls().stream().anyMatch(c -> "deletePet".equals(c.getName()));
        assertFalse(hasDeletePet, "Deprecated deletePet should be skipped");
    }

    @Test
    void parseAndBuild_pathParamsToQute() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        ApiCall getPet = petsConfig.getHttpCalls().stream().filter(c -> "getPet".equals(c.getName())).findFirst().orElseThrow();

        // Path should be converted: /pets/{petId} → /pets/{petId} (Qute-compatible)
        assertEquals("/pets/{petId}", getPet.getRequest().getPath());
    }

    @Test
    void parseAndBuild_queryParams() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        ApiCall listPets = petsConfig.getHttpCalls().stream().filter(c -> "listPets".equals(c.getName())).findFirst().orElseThrow();

        Map<String, String> queryParams = listPets.getRequest().getQueryParams();
        assertEquals("{limit}", queryParams.get("limit"));
        assertEquals("{status}", queryParams.get("status"));
    }

    @Test
    void parseAndBuild_requestBody() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        ApiCall createPet = petsConfig.getHttpCalls().stream().filter(c -> "createPet".equals(c.getName())).findFirst().orElseThrow();

        assertEquals("application/json", createPet.getRequest().getContentType());
        String body = createPet.getRequest().getBody();
        assertNotNull(body);
        // One variable for the whole body: the model writes the JSON itself, so
        // there is no unescaped substitution into a per-property template.
        assertEquals("{" + McpApiToolBuilder.WHOLE_BODY_VARIABLE + "}", body);
        // The shape a per-property template would have implied is carried in the
        // parameter description instead, so the model still knows what to write.
        String bodyDescription = createPet.getParameters().get(McpApiToolBuilder.WHOLE_BODY_VARIABLE);
        assertTrue(bodyDescription.contains("name"), bodyDescription);
        assertTrue(bodyDescription.contains("age"), bodyDescription);
        assertTrue(bodyDescription.contains("integer"), "the model must know age is not a quoted string: " + bodyDescription);
    }

    @Test
    @DisplayName("a parameter named requestBody does not silently drop the body variable")
    void parseAndBuild_bodyVariableIsRenamedOnCollision() {
        // putIfAbsent would skip the body variable here, leaving the template
        // referencing something undeclared — the empty-body bug again, for a spec
        // that happens to name a parameter "requestBody".
        String spec = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},"paths":{"/things/{requestBody}":{"post":{
                "operationId":"createThing","tags":["things"],
                "parameters":[{"name":"requestBody","in":"path","required":true,"description":"A path id","schema":{"type":"string"}}],
                "requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/T"}}}},
                "responses":{"200":{"description":"ok"}}}}},"components":{"schemas":{"T":{}}}}
                """;
        var result = McpApiToolBuilder.parseAndBuild(spec, null, null, null);
        ApiCall call = result.configsByGroup().get("things").getHttpCalls().get(0);

        assertEquals("A path id", call.getParameters().get("requestBody"), "the path parameter keeps the name");
        // Every variable the body template references must still be declared.
        var matcher = Pattern.compile("\\{([A-Za-z0-9_]+)}").matcher(call.getRequest().getBody());
        assertTrue(matcher.find());
        assertTrue(call.getParameters().containsKey(matcher.group(1)),
                "the body variable was renamed to " + matcher.group(1) + " and must be declared");
        assertNotEquals("requestBody", matcher.group(1), "it cannot keep the colliding name");
    }

    @Test
    @DisplayName("a declared body with no schema still gets a variable")
    void parseAndBuild_bodyWithNoSchemaStillDeclaresAVariable() {
        // Returning "{}" and declaring nothing recreates the original bug for this
        // spec shape: the model cannot fill a body and every write goes out empty.
        String spec = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},"paths":{"/things":{"post":{
                "operationId":"createThing","tags":["things"],
                "requestBody":{"content":{"application/json":{}}},
                "responses":{"200":{"description":"ok"}}}}}}
                """;
        var result = McpApiToolBuilder.parseAndBuild(spec, null, null, null);
        ApiCall createThing = result.configsByGroup().get("things").getHttpCalls().get(0);

        assertEquals("{" + McpApiToolBuilder.WHOLE_BODY_VARIABLE + "}", createThing.getRequest().getBody());
        assertTrue(createThing.getParameters().containsKey(McpApiToolBuilder.WHOLE_BODY_VARIABLE));
    }

    @Test
    @DisplayName("the body description names the container the schema declares")
    void parseAndBuild_bodyDescriptionNamesTheRealContainer() {
        // Telling the model "a single JSON object" for an array body makes it wrap the
        // payload in braces, and the API rejects a request the config cannot explain.
        String spec = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},"paths":{"/bulk":{"post":{
                "operationId":"createMany","tags":["bulk"],
                "requestBody":{"content":{"application/json":{"schema":{"type":"array","items":{"type":"string"}}}}},
                "responses":{"200":{"description":"ok"}}}}}}
                """;
        var result = McpApiToolBuilder.parseAndBuild(spec, null, null, null);
        ApiCall createMany = result.configsByGroup().get("bulk").getHttpCalls().get(0);

        String description = createMany.getParameters().get(McpApiToolBuilder.WHOLE_BODY_VARIABLE);
        assertTrue(description.contains("JSON array"), description);
        assertFalse(description.contains("single JSON object"), description);
    }

    @Test
    @DisplayName("required properties are marked so, and optional ones are not forced")
    void parseAndBuild_bodyDescriptionMarksRequiredProperties() {
        // Every declared parameter becomes a REQUIRED tool parameter, so optionality
        // has to live in the description or a PATCH of one field would force the
        // model to restate all the others.
        String spec = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},"paths":{"/things":{"post":{
                "operationId":"createThing","tags":["things"],
                "requestBody":{"content":{"application/json":{"schema":{"type":"object",
                "required":["name"],
                "properties":{"name":{"type":"string"},"nickname":{"type":"string"}}}}}},
                "responses":{"200":{"description":"ok"}}}}}}
                """;
        var result = McpApiToolBuilder.parseAndBuild(spec, null, null, null);
        ApiCall createThing = result.configsByGroup().get("things").getHttpCalls().get(0);

        assertEquals(1, createThing.getParameters().size(), "a body contributes exactly one parameter");
        String description = createThing.getParameters().get(McpApiToolBuilder.WHOLE_BODY_VARIABLE);
        assertTrue(description.contains("name (string, required)"), description);
        assertTrue(description.contains("nickname (string)") && !description.contains("nickname (string, required)"), description);
    }

    @Test
    @DisplayName("body template variables are declared as parameters, or the model cannot fill them")
    void parseAndBuild_requestBodyVariablesAreDeclaredAsParameters() {
        // The tool schema handed to the LLM is built from getParameters() alone. An
        // undeclared body variable is invisible to the model and — with strict
        // rendering off — renders empty, so the call succeeds with an empty body.
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        ApiCall createPet = petsConfig.getHttpCalls().stream().filter(c -> "createPet".equals(c.getName())).findFirst().orElseThrow();

        assertNotNull(createPet.getParameters(), "a call with a body must declare parameters");
        // Every variable the template references must be declared — that is the
        // invariant, whatever shape the template takes.
        var matcher = Pattern.compile("\\{([A-Za-z0-9_]+)}").matcher(createPet.getRequest().getBody());
        int found = 0;
        while (matcher.find()) {
            found++;
            assertTrue(createPet.getParameters().containsKey(matcher.group(1)),
                    matcher.group(1) + " is in the body template but not declared as a parameter");
        }
        assertTrue(found > 0, "a call with a request body must reference at least one variable");
    }

    @Test
    @DisplayName("a body with no decomposable properties is declared as one whole-body parameter")
    void parseAndBuild_wholeBodyVariableIsDeclared() {
        // The common case for this API: an unresolved $ref collapses to a single
        // variable carrying the entire JSON body.
        String spec = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},"paths":{"/things":{"post":{
                "operationId":"createThing","tags":["things"],
                "requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Thing"}}}},
                "responses":{"200":{"description":"ok"}}}}},
                "components":{"schemas":{"Thing":{}}}}
                """;
        var result = McpApiToolBuilder.parseAndBuild(spec, null, null, null);
        ApiCall createThing = result.configsByGroup().get("things").getHttpCalls().get(0);

        assertEquals("{" + McpApiToolBuilder.WHOLE_BODY_VARIABLE + "}", createThing.getRequest().getBody());
        assertNotNull(createThing.getParameters());
        assertTrue(createThing.getParameters().containsKey(McpApiToolBuilder.WHOLE_BODY_VARIABLE),
                "the whole-body variable must be declared, otherwise every POST sends an empty body");
    }

    @Test
    @DisplayName("a path parameter and the body no longer share a variable")
    void parseAndBuild_pathParameterAndBodyDoNotCollide() {
        String spec = """
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},"paths":{"/things/{id}":{"put":{
                "operationId":"updateThing","tags":["things"],
                "parameters":[{"name":"id","in":"path","required":true,"description":"The path id","schema":{"type":"string"}}],
                "requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{
                "id":{"type":"string","description":"A body id"},"label":{"type":"string"}}}}}},
                "responses":{"200":{"description":"ok"}}}}}}
                """;
        var result = McpApiToolBuilder.parseAndBuild(spec, null, null, null);
        ApiCall updateThing = result.configsByGroup().get("things").getHttpCalls().get(0);

        // With the whole body in one variable there is no collision left to resolve:
        // the path keeps {id}, and the body's own id is the model's to write.
        assertEquals("The path id", updateThing.getParameters().get("id"));
        assertTrue(updateThing.getParameters().containsKey(McpApiToolBuilder.WHOLE_BODY_VARIABLE));
        assertFalse(updateThing.getParameters().containsKey("label"), "body properties are no longer separate parameters");
        assertTrue(updateThing.getParameters().get(McpApiToolBuilder.WHOLE_BODY_VARIABLE).contains("label"));
    }

    @Test
    void parseAndBuild_httpCallHasCorrectAction() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        ApiCall listPets = petsConfig.getHttpCalls().stream().filter(c -> "listPets".equals(c.getName())).findFirst().orElseThrow();

        assertEquals(List.of("api_get_pets"), listPets.getActions());
        assertTrue(listPets.getSaveResponse());
        assertEquals("listPets_response", listPets.getResponseObjectName());
    }

    @Test
    void parseAndBuild_httpCallHasDescription() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        ApiCall listPets = petsConfig.getHttpCalls().stream().filter(c -> "listPets".equals(c.getName())).findFirst().orElseThrow();

        assertEquals("List all pets", listPets.getDescription());
    }

    @Test
    void parseAndBuild_apiSummaryIncludesEndpoints() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        assertNotNull(result.apiSummary());
        assertTrue(result.apiSummary().contains("5 total"));
        assertTrue(result.apiSummary().contains("2 groups"));
        assertTrue(result.apiSummary().contains("GET /pets"));
        assertTrue(result.apiSummary().contains("POST /store/order"));
    }

    @Test
    void parseSpec_invalidJson_throws() {
        assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.parseSpec("not valid json or yaml"));
    }

    @Test
    void convertPathParams_basic() {
        assertEquals("/pets/{petId}", McpApiToolBuilder.convertPathParams("/pets/{petId}"));
        assertEquals("/users/{userId}/orders/{orderId}", McpApiToolBuilder.convertPathParams("/users/{userId}/orders/{orderId}"));
        assertEquals("/simple", McpApiToolBuilder.convertPathParams("/simple"));
    }

    @Test
    void generateSlug_basic() {
        assertEquals("get_pets", McpApiToolBuilder.generateSlug("GET", "/pets"));
        assertEquals("post_pets_petid", McpApiToolBuilder.generateSlug("POST", "/pets/{petId}"));
        assertEquals("get_store_inventory", McpApiToolBuilder.generateSlug("GET", "/store/inventory"));
    }

    @Test
    void parseAndBuild_noEndpointsMatchFilter_throws() {
        assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, "DELETE /nonexistent", null, null));
    }

    /**
     * Test with a spec that has untagged operations — they should go to "General"
     * group.
     */
    @Test
    void parseAndBuild_untaggedOperations_goToGeneral() {
        String untaggedSpec = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Simple API", "version": "1.0.0" },
                  "servers": [{ "url": "https://api.example.com" }],
                  "paths": {
                    "/health": {
                      "get": {
                        "operationId": "healthCheck",
                        "summary": "Health check"
                      }
                    }
                  }
                }
                """;
        var result = McpApiToolBuilder.parseAndBuild(untaggedSpec, null, null, null);
        assertEquals(1, result.configsByGroup().size());
        assertTrue(result.configsByGroup().containsKey("General"));
    }

    @Test
    void parseAndBuild_httpCallParameterDescriptions() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        ApiCall getPet = petsConfig.getHttpCalls().stream().filter(c -> "getPet".equals(c.getName())).findFirst().orElseThrow();

        // Path param should be in parameter descriptions
        assertNotNull(getPet.getParameters());
        assertEquals("The pet ID", getPet.getParameters().get("petId"));
    }

    // === Security: spec-location validation (SSRF + local file read) ===

    @Test
    void parseSpec_rejectsFileScheme() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.parseSpec("file:///etc/passwd"));
        assertTrue(ex.getMessage().toLowerCase().contains("http"), "Expected scheme rejection, got: " + ex.getMessage());
    }

    @Test
    void parseSpec_rejectsNonHttpScheme() {
        assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.parseSpec("classpath:/internal-spec.yaml"));
        assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.parseSpec("not-a-valid-url"));
    }

    @Test
    void parseSpec_allowsInternalHttpHostAtSchemeGate() {
        // Scheme-only policy: private/internal hosts are intentionally NOT rejected
        // (internal OpenAPI discovery must keep working). The scheme gate accepts
        // them — only a subsequent fetch/parse can fail, never the URL check itself.
        assertTrue(McpApiToolBuilder.looksLikeInlineSpec("http://10.0.0.5/openapi.json") == false);
        assertTrue(UrlValidationUtils.isValidHttpUrl("http://169.254.169.254/latest/meta-data/"));
        assertTrue(UrlValidationUtils.isValidHttpUrl("http://internal-svc.cluster.local/spec.json"));
    }

    @Test
    void parseSpec_acceptsInlineContentWithoutNetworkAccess() {
        assertNotNull(McpApiToolBuilder.parseSpec(PETSTORE_SPEC));
    }

    @Test
    void looksLikeInlineSpec_classifiesContentVsLocation() {
        assertTrue(McpApiToolBuilder.looksLikeInlineSpec("{\"openapi\":\"3.0.0\"}"));
        assertTrue(McpApiToolBuilder.looksLikeInlineSpec("openapi: 3.0.0\ninfo:\n  title: x"));
        assertTrue(McpApiToolBuilder.looksLikeInlineSpec("swagger: \"2.0\"\ninfo: {}"));
        assertFalse(McpApiToolBuilder.looksLikeInlineSpec("https://petstore.example.com/openapi.json"));
        assertFalse(McpApiToolBuilder.looksLikeInlineSpec("file:///etc/passwd"));
    }
}
