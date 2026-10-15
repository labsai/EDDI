/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parameters a generated tool maps must be the SAME set the usability check
 * judges: path-item parameters and component {@code $ref}s included. Reading
 * only the operation's own list gave a required query parameter declared at the
 * path level (or by reference) no field on the tool, while the check had
 * already waved the operation through.
 */
@DisplayName("McpApiToolBuilder — path-level and $ref parameters")
class McpApiToolBuilderEffectiveParametersTest {

    private static final String SPEC = """
            {"openapi":"3.0.3","info":{"title":"t","version":"1"},"servers":[{"url":"https://t.example.com"}],
             "paths":{
              "/things/{thing-id}":{
                "parameters":[
                  {"name":"thing-id","in":"path","required":true,"description":"Path-level id","schema":{"type":"string"}},
                  {"name":"region","in":"query","required":true,"description":"Path-level region","schema":{"type":"string"}},
                  {"$ref":"#/components/parameters/Page"}],
                "get":{"operationId":"getThing","responses":{"200":{"description":"ok"}}}},
              "/over/{id}":{
                "parameters":[{"name":"mode","in":"query","required":true,"description":"from the path item","schema":{"type":"string"}}],
                "get":{"operationId":"override",
                  "parameters":[{"name":"mode","in":"query","required":true,"description":"from the operation","schema":{"type":"string"}},
                                {"name":"id","in":"path","required":true,"schema":{"type":"string"}}],
                  "responses":{"200":{"description":"ok"}}}}},
             "components":{"parameters":{"Page":{"name":"page","in":"query","required":true,"description":"A page number","schema":{"type":"integer"}}}}}
            """;

    private static ApiCall call(String operationId) {
        var result = McpApiToolBuilder.parseAndBuild(SPEC, null, null, null);
        return result.configsByGroup().values().stream().flatMap(c -> c.getHttpCalls().stream())
                .filter(c -> operationId.equals(c.getName())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("path-level and $ref'd parameters each get a tool field and a placeholder")
    void pathLevelAndRefParametersAreMapped() {
        ApiCall call = call("getThing");

        assertEquals("Path-level region", call.getParameters().get("region"));
        assertTrue(call.getParameters().get("page").startsWith("A page number"), call.getParameters().toString());
        assertEquals("{region}", call.getRequest().getQueryParams().get("region"));
        assertEquals("{page}", call.getRequest().getQueryParams().get("page"));
        // The path-level path parameter, with its unsafe name reduced to a safe
        // variable.
        assertTrue(call.getParameters().containsKey("thing_id"), call.getParameters().toString());
        assertTrue(call.getRequest().getPath().contains("{thing_id}"), call.getRequest().getPath());
    }

    @Test
    @DisplayName("an operation-level parameter overrides the path-level one of the same name")
    void operationLevelOverridesPathLevel() {
        ApiCall call = call("override");

        assertEquals("from the operation", call.getParameters().get("mode"));
        assertEquals(1, call.getRequest().getQueryParams().size(), call.getRequest().getQueryParams().toString());
    }
}
