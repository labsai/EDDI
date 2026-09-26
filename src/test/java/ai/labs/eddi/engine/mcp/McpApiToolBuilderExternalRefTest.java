/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An inline OpenAPI spec may only reference itself. swagger-parser, asked to
 * resolve, fetched any other {@code $ref} on its own — local files relative to
 * the working directory or by absolute path, and http URLs including loopback —
 * and what it read surfaced in the generated httpcalls config.
 */
@DisplayName("McpApiToolBuilder — external $ref in inline specs")
class McpApiToolBuilderExternalRefTest {

    private static String specReferencing(String ref) {
        return """
                {"openapi":"3.0.0","info":{"title":"T","version":"1"},
                 "paths":{"/items":{"post":{"operationId":"create",
                   "requestBody":{"content":{"application/json":{"schema":{"$ref":"%s"}}}},
                   "responses":{"200":{"description":"ok"}}}}}}
                """.formatted(ref);
    }

    @Test
    @DisplayName("a reference to a file on the server is refused, and the file is never read")
    void localFileReferenceRefused(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.yaml");
        Files.writeString(secret, "type: object\ndescription: SERVER-FILE-CONTENT\n");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> McpApiToolBuilder.parseSpec(specReferencing(secret.toAbsolutePath().toString())));
        assertTrue(e.getMessage().contains("local references"), e.getMessage());
    }

    @Test
    @DisplayName("relative, file: and http references are refused too")
    void otherExternalFormsRefused() {
        assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.parseSpec(specReferencing("./secret.yaml")));
        assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.parseSpec(specReferencing("file:///etc/passwd")));
        assertThrows(IllegalArgumentException.class,
                () -> McpApiToolBuilder.parseSpec(specReferencing("http://127.0.0.1:1/internal.yaml#/Thing")));
    }

    @Test
    @DisplayName("YAML specs are checked the same way")
    void yamlSpecChecked() {
        String yaml = """
                openapi: 3.0.0
                info: {title: T, version: '1'}
                paths:
                  /items:
                    get:
                      parameters:
                        - $ref: 'other.yaml#/components/parameters/Limit'
                      responses:
                        '200': {description: ok}
                """;
        assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.parseSpec(yaml));
    }

    @Test
    @DisplayName("local references still resolve — a component parameter keeps its name")
    void localReferencesStillWork() {
        String spec = """
                {"openapi":"3.0.0","info":{"title":"T","version":"1"},
                 "paths":{"/items":{"get":{"operationId":"list",
                   "parameters":[{"$ref":"#/components/parameters/Limit"}],
                   "responses":{"200":{"description":"ok"}}}}},
                 "components":{"parameters":{"Limit":{"name":"limit","in":"query","schema":{"type":"integer"}}}}}
                """;

        var openAPI = assertDoesNotThrow(() -> McpApiToolBuilder.parseSpec(spec));
        assertEquals("limit", openAPI.getPaths().get("/items").getGet().getParameters().get(0).getName());
    }

    @Test
    @DisplayName("a property that merely happens to be called $ref (an object, not a string) is not a reference")
    void nonTextualRefIgnored() {
        assertDoesNotThrow(() -> McpApiToolBuilder.rejectExternalRefs("""
                {"components":{"schemas":{"T":{"properties":{"$ref":{"type":"string"}}}}}}
                """));
    }
}
