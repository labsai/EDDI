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
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
            int dot = url.indexOf(".json");
            String token = dot > url.lastIndexOf('/') + 2 ? url.substring(url.lastIndexOf('/') + 2, dot) : "";
            int n;
            try {
                n = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("unexpected document URL " + url + ": '" + token + "' is not a number", e);
            }
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

    private static String specWithMapping(String mappingValue) {
        return """
                {"openapi":"3.0.0","info":{"title":"T","version":"1"},"paths":{},
                 "components":{"schemas":{"Pet":{"type":"object","properties":{"kind":{"type":"string"}},
                   "discriminator":{"propertyName":"kind","mapping":{"dog":"%s"}}},
                   "Dog":{"type":"object"}}}}
                """.formatted(mappingValue);
    }

    @Test
    @DisplayName("a discriminator mapping to a remote schema is refused before parsing: it is not a $ref, but the parser fetches it")
    void remoteDiscriminatorMappingRefused() {
        // SchemaProcessor.processDiscriminatorSchema turns each mapping value into a
        // schema $ref and resolves it - a fetch the $ref walk alone did not see.
        OpenApiSpecFetcher fetcher = mock(OpenApiSpecFetcher.class);
        for (String value : new String[]{"http://169.254.169.254/latest/dog.json", "https://evil.example/dog.json#/Dog", "dog.yaml"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> McpApiToolBuilder.parseSpec(specWithMapping(value), fetcher), value);
            assertTrue(e.getMessage().contains("discriminator mapping"), e.getMessage());
        }
        verify(fetcher, never()).fetchSpec(anyString());
    }

    @Test
    @DisplayName("an internal discriminator mapping - a #/ reference or a bare component name - still parses")
    void internalDiscriminatorMappingKept() {
        OpenApiSpecFetcher fetcher = mock(OpenApiSpecFetcher.class);
        assertEquals("#/components/schemas/Dog", McpApiToolBuilder.parseSpec(specWithMapping("#/components/schemas/Dog"), fetcher)
                .getComponents().getSchemas().get("Pet").getDiscriminator().getMapping().get("dog"));
        assertEquals("Dog", McpApiToolBuilder.parseSpec(specWithMapping("Dog"), fetcher)
                .getComponents().getSchemas().get("Pet").getDiscriminator().getMapping().get("dog"));
        verify(fetcher, never()).fetchSpec(anyString());
    }

    @Test
    @DisplayName("backstop: handed a remote $ref directly, the parser refuses it from its block list, before any DNS lookup")
    void parserBlockListRefusesEveryHost() {
        // Bypasses the bundler on purpose: if a reference-bearing field were ever
        // missed, this is what the parser does with it. The .invalid host cannot
        // resolve, so a DNS failure would read "Failed to resolve IP"; the deny list
        // is checked first and says so instead.
        String spec = specReferencing("https://refs.example.invalid/defs.json#/Pet");
        for (String version : new String[]{"3.0.0", "3.1.0"}) {
            SwaggerParseResult result = new OpenAPIV3Parser().readContents(spec.replace("3.0.0", version), null,
                    McpApiToolBuilder.parseOptions());
            String messages = String.valueOf(result.getMessages());
            assertTrue(messages.contains("explicit denylist"), version + ": " + messages);
            assertFalse(messages.contains("Failed to resolve IP"), version + ": " + messages);
        }
    }

    @Test
    @DisplayName("the bundled output is capped while it is written: a large value referenced many times is refused")
    void serializedOutputCapped() {
        // Inlining shares the referenced node, so the node cap does not see this; the
        // serialized size does.
        String defs = "{\"Big\":{\"type\":\"string\",\"description\":\"" + "x".repeat(20_000) + "\"}}";
        OpenApiSpecFetcher fetcher = fetcherServing(Map.of("https://example.com/defs.json", defs));
        StringBuilder props = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            props.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":{\"$ref\":\"https://example.com/defs.json#/Big\"}");
        }
        String spec = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"paths\":{},"
                + "\"components\":{\"schemas\":{\"S\":{\"type\":\"object\",\"properties\":{" + props + "}}}}}";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> OpenApiRefBundler.bundle(spec, null, fetcher, 100_000));
        assertTrue(e.getMessage().contains("larger than 100000 characters"), e.getMessage());
        assertTrue(OpenApiRefBundler.bundle(spec, null, fetcher).length() > 200_000, "the default cap admits it");
    }

    @Test
    @DisplayName("a relative or # reference under an $id that changes the base is refused, not resolved against the wrong document")
    void relativeRefUnderForeignIdRefused() {
        OpenApiSpecFetcher fetcher = fetcherServing(Map.of("https://schemas.example/v2/Pet.json", "{\"type\":\"string\"}"));
        String template = """
                {"openapi":"3.1.0","info":{"title":"T","version":"1"},"paths":{},
                 "components":{"schemas":{"S":{"$id":"%s","properties":{"p":{"$ref":"%s"}}}}}}
                """;

        for (String ref : new String[]{"Pet.json", "#/components/schemas/X"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> OpenApiRefBundler.bundle(template.formatted("https://schemas.example/v2/", ref),
                            "https://docs.example/v1/openapi.json", fetcher),
                    ref);
            assertTrue(e.getMessage().contains("$id changes the base"), e.getMessage());
        }
        // an absolute reference needs no base
        assertTrue(OpenApiRefBundler.bundle(template.formatted("https://schemas.example/v2/", "https://schemas.example/v2/Pet.json"),
                "https://docs.example/v1/openapi.json", fetcher).contains("\"type\":\"string\""));
        // an $id naming the document itself, or a fragment, changes nothing
        assertTrue(OpenApiRefBundler.bundle(template.formatted("https://docs.example/v1/openapi.json", "#/components/schemas/X"),
                "https://docs.example/v1/openapi.json", fetcher).contains("#/components/schemas/X"));
        assertTrue(OpenApiRefBundler.bundle(template.formatted("#anchor", "#/components/schemas/X"), null, fetcher)
                .contains("#/components/schemas/X"));
    }

    @Test
    @DisplayName("bounds beside a $ref and on its target both apply: the stricter value is kept")
    void siblingConstraintsKeepTheStricterBound() throws Exception {
        String defs = "{\"Name\":{\"type\":\"string\",\"maxLength\":100,\"minLength\":1,\"description\":\"target\","
                + "\"enum\":[\"a\",\"b\",\"c\"],\"required\":[\"x\"]}}";
        OpenApiSpecFetcher fetcher = fetcherServing(Map.of("https://example.com/defs.json", defs));
        String spec = """
                {"openapi":"3.1.0","info":{"title":"T","version":"1"},"paths":{},
                 "components":{"schemas":{"S":{"type":"object","properties":{
                   "tight":{"$ref":"https://example.com/defs.json#/Name","maxLength":10,"minLength":0,"description":"holder",
                            "enum":["b","c","d"],"required":["y"]},
                   "loose":{"$ref":"https://example.com/defs.json#/Name","maxLength":200}}}}}}
                """;

        JsonNode properties = JSON.readTree(OpenApiRefBundler.bundle(spec, null, fetcher)).at("/components/schemas/S/properties");

        JsonNode tight = properties.get("tight");
        assertEquals(10, tight.get("maxLength").asInt(), "the holder's smaller maximum");
        assertEquals(1, tight.get("minLength").asInt(), "the target's larger minimum");
        assertEquals("holder", tight.get("description").asText());
        assertEquals("[\"b\",\"c\"]", tight.get("enum").toString());
        assertEquals("[\"x\",\"y\"]", tight.get("required").toString());
        assertEquals(100, properties.get("loose").get("maxLength").asInt(), "a looser holder bound does not widen the target's");
    }

    @Test
    @DisplayName("a $ref inside example, default or extension data is a value: kept as written, never fetched")
    void refInsideLiteralDataIsNotFetched() throws Exception {
        OpenApiSpecFetcher fetcher = fetcherServing(Map.of("https://example.com/defs.json", "{\"Pet\":{\"type\":\"string\"}}"));
        String spec = """
                {"openapi":"3.1.0","info":{"title":"T","version":"1"},"paths":{},
                 "components":{"schemas":{"S":{"type":"object",
                   "example":{"$ref":"https://example.com/value.json"},
                   "default":{"$ref":"https://example.com/default.json"},
                   "examples":[{"$ref":"https://example.com/listed.json"}],
                   "x-meta":{"$ref":"https://example.com/extension.json"},
                   "properties":{"example":{"$ref":"https://example.com/defs.json#/Pet"}}}}}}
                """;

        JsonNode schema = JSON.readTree(OpenApiRefBundler.bundle(spec, null, fetcher)).at("/components/schemas/S");

        assertEquals("https://example.com/value.json", schema.at("/example/$ref").asText(), "the example payload is preserved");
        assertEquals("https://example.com/extension.json", schema.at("/x-meta/$ref").asText());
        assertEquals("string", schema.at("/properties/example/type").asText(), "a PROPERTY named example is still a schema");
        verify(fetcher, times(1)).fetchSpec(anyString());
        verify(fetcher).fetchSpec("https://example.com/defs.json");

        // with nothing else to inline, the literal ref neither triggers a fetch nor a
        // refusal
        String onlyLiteral = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"paths\":{},"
                + "\"components\":{\"schemas\":{\"S\":{\"type\":\"object\",\"example\":{\"$ref\":\"https://example.com/value.json\"}}}}}";
        OpenApiSpecFetcher untouched = mock(OpenApiSpecFetcher.class);
        assertSame(onlyLiteral, OpenApiRefBundler.bundle(onlyLiteral, null, untouched));
        verify(untouched, never()).fetchSpec(anyString());
    }

    @Test
    @DisplayName("an x- extension of the Paths or Responses Object is data; an x- NAME in components.schemas is still resolved")
    void extensionsInMapsWithExtensionsAreData() throws Exception {
        OpenApiSpecFetcher fetcher = fetcherServing(Map.of("https://example.com/defs.json", "{\"Pet\":{\"type\":\"string\"}}"));
        String spec = """
                {"openapi":"3.1.0","info":{"title":"T","version":"1"},
                 "paths":{"x-note":{"$ref":"https://example.com/value.json"},
                   "/pets":{"get":{"responses":{"x-meta":{"$ref":"https://example.com/meta.json"},
                     "200":{"description":"ok"}}}}},
                 "components":{"schemas":{"x-foo":{"$ref":"https://example.com/defs.json#/Pet"}}}}
                """;

        JsonNode bundled = JSON.readTree(OpenApiRefBundler.bundle(spec, null, fetcher));

        assertEquals("https://example.com/value.json", bundled.at("/paths/x-note/$ref").asText(), "a Paths Object extension is kept");
        assertEquals("https://example.com/meta.json", bundled.at("/paths/~1pets/get/responses/x-meta/$ref").asText());
        assertEquals("string", bundled.at("/components/schemas/x-foo/type").asText(), "a schema NAMED x-foo is a schema");
        verify(fetcher, times(1)).fetchSpec(anyString());
        verify(fetcher).fetchSpec("https://example.com/defs.json");
    }

    @Test
    @DisplayName("a discriminator mapping in a referenced document is moved into the root and rewritten to the moved schema")
    void remoteDiscriminatorMappingRelocated() throws Exception {
        String defs = """
                {"components":{"schemas":{
                  "Base":{"oneOf":[{"$ref":"#/components/schemas/Dog"}],
                          "discriminator":{"propertyName":"kind","mapping":{"dog":"#/components/schemas/Dog","cat":"Cat"}}},
                  "Dog":{"type":"object","properties":{"kind":{"type":"string"},"bark":{"type":"boolean"}}},
                  "Cat":{"type":"object","properties":{"kind":{"type":"string"}}}}}}
                """;
        OpenApiSpecFetcher fetcher = fetcherServing(Map.of("https://example.com/defs.json", defs));
        String spec = """
                {"openapi":"3.0.0","info":{"title":"T","version":"1"},"paths":{},
                 "components":{"schemas":{"Dog":{"type":"string"},
                   "Pet":{"$ref":"https://example.com/defs.json#/components/schemas/Base"}}}}
                """;

        JsonNode schemas = JSON.readTree(OpenApiRefBundler.bundle(spec, null, fetcher)).at("/components/schemas");

        JsonNode mapping = schemas.at("/Pet/discriminator/mapping");
        assertEquals("#/components/schemas/Dog_2", mapping.get("dog").asText(), "the root's own Dog keeps its name");
        assertEquals("#/components/schemas/Cat", mapping.get("cat").asText());
        assertEquals("boolean", schemas.at("/Dog_2/properties/bark/type").asText());
        assertEquals("string", schemas.at("/Cat/properties/kind/type").asText());
        assertEquals("string", schemas.at("/Dog/type").asText());
    }

    @Test
    @DisplayName("intersecting two large enums is linear")
    void largeEnumIntersectionIsFast() throws Exception {
        StringBuilder target = new StringBuilder();
        StringBuilder holder = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            target.append(i == 0 ? "" : ",").append("\"t").append(i).append('"');
            holder.append(i == 0 ? "" : ",").append("\"h").append(i).append('"');
        }
        OpenApiSpecFetcher fetcher = fetcherServing(
                Map.of("https://example.com/defs.json", "{\"E\":{\"type\":\"string\",\"enum\":[" + target + "]}}"));
        String spec = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"paths\":{},\"components\":{\"schemas\":{\"S\":"
                + "{\"$ref\":\"https://example.com/defs.json#/E\",\"enum\":[" + holder + ",\"t7\"]}}}}";

        String bundled = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> OpenApiRefBundler.bundle(spec, null, fetcher));

        assertEquals("[\"t7\"]", JSON.readTree(bundled).at("/components/schemas/S/enum").toString());
    }

    @Test
    @DisplayName("a redirected document counts once toward the document cap")
    void redirectAliasesCountOnce() {
        OpenApiSpecFetcher fetcher = mock(OpenApiSpecFetcher.class);
        when(fetcher.fetchSpec(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            return new OpenApiSpecFetcher.FetchedSpec("{\"type\":\"string\"}", url.replace("/s", "/moved/s"));
        });
        StringBuilder props = new StringBuilder();
        int documents = OpenApiRefBundler.MAX_REMOTE_DOCUMENTS - 2;
        for (int i = 0; i < documents; i++) {
            props.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":{\"$ref\":\"https://example.com/s").append(i).append(".json\"}");
        }
        String spec = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"paths\":{},"
                + "\"components\":{\"schemas\":{\"S\":{\"type\":\"object\",\"properties\":{" + props + "}}}}}";

        // 30 documents, each stored under its requested and its final URL: within the
        // cap.
        assertTrue(OpenApiRefBundler.bundle(spec, null, fetcher).contains("\"p29\""));
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
