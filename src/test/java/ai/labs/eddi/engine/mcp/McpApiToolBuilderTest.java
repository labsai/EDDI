package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.http.model.HttpCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpApiToolBuilderTest {

    /**
     * Minimal Petstore-style OpenAPI 3.0 spec with two tags (pets, store),
     * path params, query params, and a request body.
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
        var result = McpApiToolBuilder.parseAndBuild(
                PETSTORE_SPEC, null, "https://custom-api.example.com", null);
        for (var config : result.configsByGroup().values()) {
            assertEquals("https://custom-api.example.com", config.getTargetServerUrl());
        }
    }

    @Test
    void parseAndBuild_setsAuthHeader() {
        var result = McpApiToolBuilder.parseAndBuild(
                PETSTORE_SPEC, null, null, "Bearer sk-test-key");

        // Every HttpCall should have the Authorization header
        for (var config : result.configsByGroup().values()) {
            for (HttpCall call : config.getHttpCalls()) {
                assertEquals("Bearer sk-test-key",
                        call.getRequest().getHeaders().get("Authorization"));
            }
        }
    }

    @Test
    void parseAndBuild_filtersEndpoints() {
        var result = McpApiToolBuilder.parseAndBuild(
                PETSTORE_SPEC, "GET /pets,POST /store/order", null, null);

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
        boolean hasDeletePet = petsConfig.getHttpCalls().stream()
                .anyMatch(c -> "deletePet".equals(c.getName()));
        assertFalse(hasDeletePet, "Deprecated deletePet should be skipped");
    }

    @Test
    void parseAndBuild_pathParamsToThymeleaf() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        HttpCall getPet = petsConfig.getHttpCalls().stream()
                .filter(c -> "getPet".equals(c.getName()))
                .findFirst()
                .orElseThrow();

        // Path should be converted: /pets/{petId} → /pets/[[${petId}]]
        assertEquals("/pets/[[${petId}]]", getPet.getRequest().getPath());
    }

    @Test
    void parseAndBuild_queryParams() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        HttpCall listPets = petsConfig.getHttpCalls().stream()
                .filter(c -> "listPets".equals(c.getName()))
                .findFirst()
                .orElseThrow();

        Map<String, String> queryParams = listPets.getRequest().getQueryParams();
        assertEquals("[[${limit}]]", queryParams.get("limit"));
        assertEquals("[[${status}]]", queryParams.get("status"));
    }

    @Test
    void parseAndBuild_requestBody() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        HttpCall createPet = petsConfig.getHttpCalls().stream()
                .filter(c -> "createPet".equals(c.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals("application/json", createPet.getRequest().getContentType());
        String body = createPet.getRequest().getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"name\""), "Body should have 'name' field");
        assertTrue(body.contains("\"age\""), "Body should have 'age' field");
        // String field should be quoted
        assertTrue(body.contains("\"[[${name}]]\""), "String param should be quoted in template");
    }

    @Test
    void parseAndBuild_httpCallHasCorrectAction() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        HttpCall listPets = petsConfig.getHttpCalls().stream()
                .filter(c -> "listPets".equals(c.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of("api_get_pets"), listPets.getActions());
        assertTrue(listPets.getSaveResponse());
        assertEquals("listPets_response", listPets.getResponseObjectName());
    }

    @Test
    void parseAndBuild_httpCallHasDescription() {
        var result = McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, null, null, null);
        var petsConfig = result.configsByGroup().get("pets");

        HttpCall listPets = petsConfig.getHttpCalls().stream()
                .filter(c -> "listPets".equals(c.getName()))
                .findFirst()
                .orElseThrow();

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
        assertThrows(IllegalArgumentException.class, () ->
                McpApiToolBuilder.parseSpec("not valid json or yaml"));
    }

    @Test
    void convertPathParams_basic() {
        assertEquals("/pets/[[${petId}]]", McpApiToolBuilder.convertPathParams("/pets/{petId}"));
        assertEquals("/users/[[${userId}]]/orders/[[${orderId}]]",
                McpApiToolBuilder.convertPathParams("/users/{userId}/orders/{orderId}"));
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
        assertThrows(IllegalArgumentException.class, () ->
                McpApiToolBuilder.parseAndBuild(PETSTORE_SPEC, "DELETE /nonexistent", null, null));
    }

    /**
     * Test with a spec that has untagged operations — they should go to "General" group.
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

        HttpCall getPet = petsConfig.getHttpCalls().stream()
                .filter(c -> "getPet".equals(c.getName()))
                .findFirst()
                .orElseThrow();

        // Path param should be in parameter descriptions
        assertNotNull(getPet.getParameters());
        assertEquals("The pet ID", getPet.getParameters().get("petId"));
    }
}
