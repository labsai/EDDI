/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import com.sun.net.httpserver.HttpServer;
import io.swagger.v3.core.util.Yaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("a JSON spec too large for the YAML loader is still scanned, and its file reference refused")
    void oversizedJsonSpecIsStillScanned(@TempDir Path dir) throws Exception {
        // snakeyaml refuses documents over 3,145,728 code points. The scan used to
        // read everything as YAML and wave a spec through when that failed, while
        // swagger-parser reads {-prefixed input with its unlimited JSON mapper and
        // then resolved the reference: the file's content landed in the model.
        Path secret = dir.resolve("secret.yaml");
        Files.writeString(secret, "type: object\ndescription: SERVER-FILE-CONTENT\n");
        String small = specReferencing(secret.toAbsolutePath().toString()).trim();
        String big = small.substring(0, small.length() - 1) + ", \"x-pad\": \"" + "a".repeat(3_300_000) + "\"}";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.parseSpec(big));
        assertTrue(e.getMessage().contains("local references"), e.getMessage());
    }

    @Test
    @DisplayName("a spec the scan cannot read is refused, not waved through")
    void unreadableSpecFailsClosed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> McpApiToolBuilder.rejectExternalRefs("{\"openapi\": \"3.0.0\", \"paths\": {"));
        assertTrue(e.getMessage().contains("could not be read"), e.getMessage());
    }

    @Test
    @DisplayName("a REMOTE spec's file: references are not resolved by swagger-parser (pins the library's behaviour)")
    void remoteSpecDoesNotResolveFileRefs(@TempDir Path dir) throws Exception {
        // Remote specs keep ref resolution (multi-file specs are legitimate there),
        // so this guards the assumption that makes that safe: swagger-parser
        // resolves a remote spec's refs against its http base and never opens a
        // file: URL. A library upgrade that changed this must fail here.
        Path secret = dir.resolve("secret.yaml");
        Files.writeString(secret, "type: object\ndescription: SERVER-FILE-CONTENT\n");
        String spec = specReferencing(secret.toUri().toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openapi.json", exchange -> {
            byte[] body = spec.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/openapi.json";
            String rendered;
            try {
                rendered = Yaml.pretty(McpApiToolBuilder.parseSpec(url));
            } catch (IllegalArgumentException refused) {
                rendered = refused.getMessage();
            }
            assertFalse(rendered.contains("SERVER-FILE-CONTENT"), rendered);
        } finally {
            server.stop(0);
        }
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
