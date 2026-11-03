/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.smallrye.openapi.runtime.io.Format;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandedOpenApiRouteTest {

    private static final String JSON_DOC = """
            {
              "openapi" : "3.1.0",
              "info" : {
                "title" : "EDDI API",
                "version" : "6.6.0",
                "description" : "Enterprise-grade conversational AI platform.",
                "contact" : { "name" : "LABS.AI", "email" : "contact@labs.ai", "url" : "https://eddi.labs.ai" },
                "license" : { "name" : "Apache 2.0" }
              },
              "paths" : { "/agentstore/agents" : { "get" : { "operationId" : "readAgentDescriptors" } } }
            }
            """;

    private static final String YAML_DOC = """
            ---
            openapi: 3.1.0
            info:
              title: EDDI API
              version: 6.6.0
              contact:
                name: LABS.AI
                email: contact@labs.ai
              license:
                name: Apache 2.0
            paths:
              /agentstore/agents:
                get:
                  operationId: readAgentDescriptors
            """;

    @Test
    void jsonGetsTheProductTitleAndLosesTheContact() throws IOException {
        byte[] out = BrandedOpenApiRoute.rebrand(JSON_DOC.getBytes(StandardCharsets.UTF_8), Format.JSON, "Zebra Labs");
        JsonNode doc = new ObjectMapper().readTree(out);

        assertEquals("Zebra Labs API", doc.at("/info/title").asText());
        assertTrue(doc.at("/info/contact").isMissingNode());
        // Everything else is the document as built.
        assertEquals("6.6.0", doc.at("/info/version").asText());
        assertEquals("Apache 2.0", doc.at("/info/license/name").asText());
        assertEquals("readAgentDescriptors", doc.at("/paths/~1agentstore~1agents/get/operationId").asText());
        assertFalse(new String(out, StandardCharsets.UTF_8).contains("labs.ai"));
    }

    @Test
    void yamlToo() throws IOException {
        byte[] out = BrandedOpenApiRoute.rebrand(YAML_DOC.getBytes(StandardCharsets.UTF_8), Format.YAML, "Zebra \"Q\" Labs");
        JsonNode doc = new ObjectMapper(new YAMLFactory()).readTree(out);

        assertEquals("Zebra \"Q\" Labs API", doc.at("/info/title").asText());
        assertTrue(doc.at("/info/contact").isMissingNode());
        assertEquals("readAgentDescriptors", doc.at("/paths/~1agentstore~1agents/get/operationId").asText());
    }

    @Test
    void aDocumentWithoutInfoIsLeftAlone() throws IOException {
        byte[] out = BrandedOpenApiRoute.rebrand("{\"openapi\":\"3.1.0\"}".getBytes(StandardCharsets.UTF_8), Format.JSON, "Zebra");
        assertTrue(new ObjectMapper().readTree(out).path("info").isMissingNode());
    }

    @Test
    void negotiatesTheFormatLikeTheOpenApiHandler() {
        assertEquals(Format.JSON, BrandedOpenApiRoute.format("/openapi.json", List.of(), "application/yaml"));
        assertEquals(Format.YAML, BrandedOpenApiRoute.format("/openapi.yml", List.of(), "application/json"));
        assertEquals(Format.YAML, BrandedOpenApiRoute.format("/openapi.yaml", List.of("json"), null));
        assertEquals(Format.JSON, BrandedOpenApiRoute.format("/openapi", List.of("JSON"), null));
        assertEquals(Format.YAML, BrandedOpenApiRoute.format("/openapi", List.of("yaml"), "application/json"));
        assertEquals(Format.JSON, BrandedOpenApiRoute.format("/openapi", List.of(), "application/json, text/plain"));
        assertEquals(Format.YAML, BrandedOpenApiRoute.format("/openapi", null, null));
    }
}
