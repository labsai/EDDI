/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parameter names come from a third-party OpenAPI spec and are copied into the
 * {@code {...}} placeholders of the generated httpcall, which EDDI renders as a
 * template on every call. They must arrive there as plain identifiers.
 */
@DisplayName("McpApiToolBuilder reduces spec parameter names to safe template variables")
class McpApiToolBuilderVariableNameTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]*)}");
    private static final Pattern SAFE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final String SPEC = """
            {
              "openapi": "3.0.3",
              "info": { "title": "Odd names", "version": "1.0.0" },
              "servers": [{ "url": "https://odd.example.com" }],
              "paths": {
                "/items/{config:PATH}/{item-id}": {
                  "get": {
                    "operationId": "getItem",
                    "tags": ["items"],
                    "parameters": [
                      { "name": "config:PATH", "in": "path", "required": true, "schema": { "type": "string" } },
                      { "name": "item-id", "in": "path", "required": true, "schema": { "type": "string" } },
                      { "name": "item_id", "in": "query", "schema": { "type": "string" } },
                      { "name": "q} {#for i in 5}x{/for", "in": "query", "schema": { "type": "string" } },
                      { "name": "9lives", "in": "query", "schema": { "type": "string" } },
                      { "name": "pageSize", "in": "query", "schema": { "type": "string" } }
                    ]
                  }
                }
              }
            }
            """;

    private static ApiCall onlyCall() {
        var result = McpApiToolBuilder.parseAndBuild(SPEC, null, null, null);
        return result.configsByGroup().get("items").getHttpCalls().getFirst();
    }

    @Test
    @DisplayName("every placeholder in path and query is a plain identifier")
    void placeholdersAreSafe() {
        var request = onlyCall().getRequest();

        var matcher = PLACEHOLDER.matcher(request.getPath());
        int count = 0;
        while (matcher.find()) {
            count++;
            assertTrue(SAFE.matcher(matcher.group(1)).matches(), "unsafe path placeholder: " + matcher.group());
        }
        assertEquals(2, count, request.getPath());

        for (var value : request.getQueryParams().values()) {
            var m = PLACEHOLDER.matcher(value);
            assertTrue(m.matches() && SAFE.matcher(m.group(1)).matches(), "unsafe query placeholder: " + value);
        }
    }

    @Test
    @DisplayName("placeholders and tool parameters agree, and colliding names stay distinct")
    void namesAreConsistentAndDistinct() {
        var call = onlyCall();
        var request = call.getRequest();

        assertEquals("/items/{config_PATH}/{item_id}", request.getPath());
        // the query KEY is the spec's own name; only the variable is renamed
        assertEquals("{item_id_2}", request.getQueryParams().get("item_id"));
        assertEquals("{p_9lives}", request.getQueryParams().get("9lives"));
        assertEquals("{pageSize}", request.getQueryParams().get("pageSize"));

        for (var value : request.getQueryParams().values()) {
            String variable = value.substring(1, value.length() - 1);
            assertTrue(call.getParameters().containsKey(variable), "tool parameter missing for " + value);
        }
        assertTrue(call.getParameters().containsKey("config_PATH"));
        assertTrue(call.getParameters().containsKey("item_id"));
        call.getParameters().keySet().forEach(name -> assertTrue(SAFE.matcher(name).matches(), "unsafe tool parameter: " + name));
    }

    @Test
    @DisplayName("an undeclared path placeholder never takes a declared parameter's variable")
    void undeclaredPlaceholderDoesNotReuseDeclaredVariable() {
        var declared = Map.of("item_id", "item_id");

        assertEquals("/items/{item_id_2}/{item_id_2}/{item_id}",
                McpApiToolBuilder.convertPathParams("/items/{item-id}/{item-id}/{item_id}", declared));
        // two undeclared placeholders that reduce to the same name stay apart too
        assertEquals("/a/{x_y}/{x_y_2}", McpApiToolBuilder.convertPathParams("/a/{x-y}/{x.y}", Map.of()));
    }

    @Test
    void safeVariableName() {
        assertEquals("petId", McpApiToolBuilder.safeVariableName("petId"));
        assertEquals("pet_id", McpApiToolBuilder.safeVariableName("pet-id"));
        assertEquals("p_1st", McpApiToolBuilder.safeVariableName("1st"));
        assertEquals("p_", McpApiToolBuilder.safeVariableName(""));
        assertEquals("config_x", McpApiToolBuilder.safeVariableName("config:x"));
    }
}
