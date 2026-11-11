/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * swagger-parser must never fetch a {@code $ref} itself: its resolver's
 * deny-list misses CGNAT and two cloud metadata services, ignores the
 * deployment's SSRF settings, follows redirects unchecked and reads without a
 * limit. {@link OpenApiRefBundler} fetches every referenced document through
 * {@link OpenApiSpecFetcher} and inlines it first.
 */
@DisplayName("OpenApiRefBundler — external $refs are fetched by EDDI, never by swagger-parser")
class OpenApiRefBundlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String specReferencing(String ref) {
        return """
                {"openapi":"3.0.0","info":{"title":"T","version":"1"},
                 "paths":{"/items":{"post":{"operationId":"create",
                   "requestBody":{"content":{"application/json":{"schema":{"$ref":"%s"}}}},
                   "responses":{"200":{"description":"ok"}}}}}}
                """.formatted(ref);
    }

    /**
     * A fetcher answering each URL from {@code documents}, as if read from that
     * URL.
     */
    private static OpenApiSpecFetcher fetcherServing(Map<String, String> documents) {
        OpenApiSpecFetcher fetcher = mock(OpenApiSpecFetcher.class);
        when(fetcher.fetchSpec(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            String content = documents.get(url);
            if (content == null) {
                throw new IllegalArgumentException("OpenAPI spec location " + url + " answered HTTP 404");
            }
            return new OpenApiSpecFetcher.FetchedSpec(content, url);
        });
        return fetcher;
    }

    private static Schema<?> requestSchema(OpenAPI openAPI) {
        return openAPI.getPaths().get("/items").getPost().getRequestBody().getContent().get("application/json").getSchema();
    }

    @Test
    @DisplayName("a remote $ref is fetched through EDDI's fetcher, and the server it names is never contacted by the parser")
    void parserPerformsNoNetworkIo() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{\"Pet\":{\"type\":\"object\",\"description\":\"FROM-THE-NETWORK\"}}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String defs = "http://127.0.0.1:" + server.getAddress().getPort() + "/defs.json";
            OpenApiSpecFetcher fetcher = fetcherServing(Map.of(defs, "{\"Pet\":{\"type\":\"object\",\"description\":\"FROM-THE-FETCHER\"}}"));

            OpenAPI openAPI = McpApiToolBuilder.parseSpec(specReferencing(defs + "#/Pet"), fetcher);

            assertEquals("FROM-THE-FETCHER", requestSchema(openAPI).getDescription());
            verify(fetcher, times(1)).fetchSpec(defs);
            assertEquals(0, hits.get(), "swagger-parser opened a connection of its own");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a $ref at Alibaba's metadata service (CGNAT, missed by swagger-parser's deny-list) is refused by EDDI's policy")
    void metadataRefRefusedByEddiPolicy() {
        // The permissive fetcher's policy refuses the metadata service before any
        // connection is opened; swagger-parser's own checker let 100.64.0.0/10 through.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> McpApiToolBuilder.parseSpec(specReferencing("http://100.100.100.200/latest/meta-data#/x")));
        assertTrue(e.getMessage().contains("$ref") && e.getMessage().contains("refused"), e.getMessage());
    }

    @Test
    @DisplayName("Azure WireServer and the OCI metadata address are refused as $ref targets too")
    void otherMetadataRefsRefused() {
        assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.parseSpec(specReferencing("http://168.63.129.16/machine")));
        assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.parseSpec(specReferencing("http://192.0.0.192/opc/v2/")));
    }

    @Test
    @DisplayName("a $ref the fetcher refuses fails the parse with the fetcher's reason")
    void refusedRefFailsClosed() {
        OpenApiSpecFetcher fetcher = mock(OpenApiSpecFetcher.class);
        when(fetcher.fetchSpec(anyString())).thenThrow(new IllegalArgumentException("Access to private network addresses is not allowed"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> McpApiToolBuilder.parseSpec(specReferencing("http://10.0.0.5/defs.json#/Pet"), fetcher));
        assertTrue(e.getMessage().contains("refused: Access to private network addresses"), e.getMessage());
    }

    @Test
    @DisplayName("a downloaded spec's relative $ref resolves beside it and is fetched by EDDI")
    void relativeRefResolvesAgainstLocation() throws Exception {
        OpenApiSpecFetcher fetcher = fetcherServing(Map.of("https://api.example.com/v1/schemas/pet.json", "{\"type\":\"string\"}"));

        String bundled = OpenApiRefBundler.bundle(specReferencing("schemas/pet.json"), "https://api.example.com/v1/openapi.json", fetcher);

        JsonNode schema = JSON.readTree(bundled).at("/paths/~1items/post/requestBody/content/application~1json/schema");
        assertEquals("string", schema.get("type").asText());
    }

    @Test
    @DisplayName("a remote document's own #/ refs are inlined from that document, and recursion stops")
    void remoteInternalRefsAndRecursion() throws Exception {
        String defs = """
                {"Pet":{"type":"object","properties":{"owner":{"$ref":"#/Owner"},"parent":{"$ref":"#/Pet"}}},
                 "Owner":{"type":"object","description":"OWNER"}}
                """;
        OpenApiSpecFetcher fetcher = fetcherServing(Map.of("https://example.com/defs.json", defs));

        String bundled = OpenApiRefBundler.bundle(specReferencing("https://example.com/defs.json#/Pet"), null, fetcher);

        JsonNode schema = JSON.readTree(bundled).at("/paths/~1items/post/requestBody/content/application~1json/schema");
        assertEquals("OWNER", schema.at("/properties/owner/description").asText());
        assertTrue(schema.at("/properties/parent").isObject() && schema.at("/properties/parent").isEmpty(), bundled);
        assertTrue(!bundled.contains("\"$ref\":\"#/Owner\"") && !bundled.contains("defs.json"), bundled);
    }

    @Test
    @DisplayName("a reference back into the downloaded root document stays an internal reference")
    void refIntoRootStaysInternal() throws Exception {
        String root = "https://example.com/openapi.json";
        OpenApiSpecFetcher fetcher = mock(OpenApiSpecFetcher.class);

        String bundled = OpenApiRefBundler.bundle(specReferencing(root + "#/components/schemas/Pet"), root, fetcher);

        assertTrue(bundled.contains("\"$ref\":\"#/components/schemas/Pet\""), bundled);
        verify(fetcher, never()).fetchSpec(anyString());
    }

    @Test
    @DisplayName("a spec without external refs is handed on unchanged")
    void noExternalRefsUnchanged() {
        String spec = specReferencing("#/components/schemas/Pet");
        assertSame(spec, OpenApiRefBundler.bundle(spec, null, mock(OpenApiSpecFetcher.class)));
    }

    @Test
    @DisplayName("a chain of remote documents deeper than the cap is refused")
    void depthCapped() {
        OpenApiSpecFetcher fetcher = mock(OpenApiSpecFetcher.class);
        when(fetcher.fetchSpec(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            int n = Integer.parseInt(url.substring(url.lastIndexOf('/') + 2, url.indexOf(".json")));
            return new OpenApiSpecFetcher.FetchedSpec("{\"$ref\":\"https://example.com/d" + (n + 1) + ".json\"}", url);
        });

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OpenApiRefBundler.bundle(specReferencing("https://example.com/d0.json"), null, fetcher));
        assertTrue(e.getMessage().contains("deeper than"), e.getMessage());
    }

    @Test
    @DisplayName("more distinct remote documents than the cap are refused")
    void documentCountCapped() {
        StringBuilder props = new StringBuilder();
        for (int i = 0; i <= OpenApiRefBundler.MAX_REMOTE_DOCUMENTS; i++) {
            props.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":{\"$ref\":\"https://example.com/s").append(i).append(".json\"}");
        }
        String spec = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"paths\":{},"
                + "\"components\":{\"schemas\":{\"S\":{\"type\":\"object\",\"properties\":{" + props + "}}}}}";
        OpenApiSpecFetcher fetcher = mock(OpenApiSpecFetcher.class);
        when(fetcher.fetchSpec(anyString()))
                .thenAnswer(invocation -> new OpenApiSpecFetcher.FetchedSpec("{\"type\":\"string\"}", invocation.getArgument(0)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> OpenApiRefBundler.bundle(spec, null, fetcher));
        assertTrue(e.getMessage().contains("more than " + OpenApiRefBundler.MAX_REMOTE_DOCUMENTS), e.getMessage());
    }

    @Test
    @DisplayName("a referenced document with a file: $ref is refused like a downloaded spec")
    void referencedDocumentHeldToDownloadedRules() {
        OpenApiSpecFetcher fetcher = fetcherServing(Map.of("https://example.com/defs.json", "{\"Pet\":{\"$ref\":\"file:///etc/passwd\"}}"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OpenApiRefBundler.bundle(specReferencing("https://example.com/defs.json#/Pet"), null, fetcher));
        assertTrue(e.getMessage().contains("external $ref"), e.getMessage());
    }

    @Test
    @DisplayName("a fragment that points at nothing is refused")
    void danglingFragmentRefused() {
        OpenApiSpecFetcher fetcher = fetcherServing(Map.of("https://example.com/defs.json", "{\"Pet\":{}}"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OpenApiRefBundler.bundle(specReferencing("https://example.com/defs.json#/Missing"), null, fetcher));
        assertTrue(e.getMessage().contains("points at nothing"), e.getMessage());
    }

    @Test
    @DisplayName("a textual $id is removed, so no schema base can send the parser to the network")
    void schemaIdRemoved() {
        String spec = """
                {"openapi":"3.1.0","info":{"title":"T","version":"1"},"paths":{},
                 "components":{"schemas":{"S":{"$id":"https://example.com/s/","type":"object"}}}}
                """;
        String bundled = OpenApiRefBundler.bundle(spec, null, mock(OpenApiSpecFetcher.class));
        assertTrue(!bundled.contains("$id") && bundled.contains("\"type\":\"object\""), bundled);
    }
}
