/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A generated tool must be able to SEND every input its operation requires.
 * <p>
 * The incident: {@code PUT /agentstore/agents/{id}/updateResourceUri} consumes
 * a {@code text/plain} body, the generator mapped only {@code application/json}
 * bodies, and the tool the operator received had no field for the URI — so the
 * admin approved a call that could only fail with a 400. Operations whose body
 * the generator cannot map are now refused by name instead of generated broken.
 */
@DisplayName("McpApiToolBuilder — non-JSON request bodies")
class McpApiToolBuilderTextBodyTest {

    private static final String URI_EXAMPLE = "eddi://ai.labs.workflow/workflowstore/workflows/ID?version=3";

    private static String spec(String operations) {
        return """
                {"openapi":"3.0.3","info":{"title":"t","version":"1"},"servers":[{"url":"https://t.example.com"}],
                 "paths":{%s}}
                """.formatted(operations);
    }

    private static final String TEXT_PUT = """
            "/agents/{id}/updateResourceUri":{"put":{"operationId":"updateResourceInAgent","tags":["agents"],
              "parameters":[
                {"name":"id","in":"path","required":true,"schema":{"type":"string"}},
                {"name":"version","in":"query","required":true,"schema":{"type":"integer"}}],
              "requestBody":{"required":true,"description":"The new version of the referenced resource. It MUST carry ?version=N.",
                "content":{"text/plain":{"schema":{"type":"string","format":"uri","example":"%s"}}}},
              "responses":{"200":{"description":"ok"}}}}
            """.formatted(URI_EXAMPLE);

    private static ApiCall onlyCall(McpApiToolBuilder.ApiBuildResult result) {
        var calls = result.configsByGroup().values().iterator().next().getHttpCalls();
        assertEquals(1, calls.size());
        return calls.getFirst();
    }

    @Test
    @DisplayName("a text/plain body becomes one declared parameter, sent verbatim as text/plain")
    void textBodyIsMapped() {
        var result = McpApiToolBuilder.parseAndBuild(spec(TEXT_PUT), null, null, null);
        ApiCall call = onlyCall(result);

        assertEquals("text/plain", call.getRequest().getContentType());
        // The whole body is ONE placeholder: nothing around the value, so nothing
        // wraps, quotes or JSON-escapes it on the way out.
        assertEquals("{requestBody}", call.getRequest().getBody());
        String description = call.getParameters().get("requestBody");
        assertTrue(description != null, "the body must be a declared tool parameter: " + call.getParameters());
        assertTrue(description.contains("plain text"), description);
        assertTrue(description.contains("MUST carry ?version=N"), "the body's own description is kept: " + description);
        assertTrue(description.contains(URI_EXAMPLE), "the spec's example is surfaced: " + description);
        assertTrue(call.getParameters().containsKey("id") && call.getParameters().containsKey("version"));
        assertTrue(result.unusableEndpoints().isEmpty());
    }

    @Test
    @DisplayName("a media type with parameters or different case is still text/plain")
    void textBodyWithCharsetParameter() {
        String spec = spec("""
                "/x":{"post":{"operationId":"say","requestBody":{"content":{"Text/Plain; charset=UTF-8":{"schema":{"type":"string"}}}},
                  "responses":{"200":{"description":"ok"}}}}
                """);
        ApiCall call = onlyCall(McpApiToolBuilder.parseAndBuild(spec, null, null, null));

        assertEquals("text/plain", call.getRequest().getContentType());
        assertEquals("{requestBody}", call.getRequest().getBody());
    }

    @Test
    @DisplayName("an operation offering both JSON and text is sent the JSON form")
    void jsonWinsOverText() {
        String spec = spec("""
                "/x":{"post":{"operationId":"both","requestBody":{"content":{
                  "text/plain":{"schema":{"type":"string"}},
                  "application/json":{"schema":{"type":"object","properties":{"a":{"type":"string"}}}}}},
                  "responses":{"200":{"description":"ok"}}}}
                """);
        ApiCall call = onlyCall(McpApiToolBuilder.parseAndBuild(spec, null, null, null));

        assertEquals("application/json", call.getRequest().getContentType());
        assertTrue(call.getParameters().get("requestBody").contains("JSON"), call.getParameters().toString());
    }

    @Test
    @DisplayName("a name collision still renames the text body variable instead of dropping it")
    void textBodyCollidingWithPathParameter() {
        String spec = spec("""
                "/x/{requestBody}":{"put":{"operationId":"clash",
                  "parameters":[{"name":"requestBody","in":"path","required":true,"schema":{"type":"string"}}],
                  "requestBody":{"content":{"text/plain":{"schema":{"type":"string"}}}},
                  "responses":{"200":{"description":"ok"}}}}
                """);
        ApiCall call = onlyCall(McpApiToolBuilder.parseAndBuild(spec, null, null, null));

        assertEquals("{requestBodyBody}", call.getRequest().getBody());
        assertTrue(call.getParameters().containsKey("requestBodyBody"), call.getParameters().toString());
    }

    @Test
    @DisplayName("a required body of an unsupported type is reported by operation and NOT generated")
    void unsupportedRequiredBodyIsReported() {
        String spec = spec(TEXT_PUT + "," + """
                "/import":{"post":{"operationId":"importAgent","tags":["agents"],
                  "requestBody":{"required":true,"content":{"application/zip":{"schema":{"type":"string","format":"binary"}}}},
                  "responses":{"200":{"description":"ok"}}}}
                """);
        var result = McpApiToolBuilder.parseAndBuild(spec, null, null, null);

        assertEquals(1, result.endpointCount(), "only the mappable operation is generated");
        assertEquals("updateResourceInAgent", onlyCall(result).getName());
        assertEquals(1, result.unusableEndpoints().size());
        String reason = result.unusableEndpoints().getFirst();
        assertTrue(reason.startsWith("POST /import: "), reason);
        assertTrue(reason.contains("application/zip"), reason);
    }

    @Test
    @DisplayName("a body the spec marks optional is generated without it, not reported")
    void optionalUnsupportedBodyIsTolerated() {
        String spec = spec("""
                "/a":{"get":{"operationId":"ok","responses":{"200":{"description":"ok"}}}},
                "/up":{"post":{"operationId":"upload",
                  "requestBody":{"required":false,"content":{"multipart/form-data":{"schema":{"type":"object"}}}},
                  "responses":{"200":{"description":"ok"}}}}
                """);
        var result = McpApiToolBuilder.parseAndBuild(spec, null, null, null);

        assertEquals(2, result.endpointCount());
        assertTrue(result.unusableEndpoints().isEmpty(), result.unusableEndpoints().toString());
        var upload = result.configsByGroup().values().iterator().next().getHttpCalls().stream()
                .filter(c -> "upload".equals(c.getName())).findFirst().orElseThrow();
        assertTrue(upload.getRequest().getBody() == null || upload.getRequest().getBody().isEmpty(), upload.getRequest().getBody());
        assertFalse(upload.getParameters() != null && upload.getParameters().containsKey("requestBody"));
    }

    @Test
    @DisplayName("a required header parameter, which tools cannot send, is reported")
    void requiredHeaderParameterIsReported() {
        String spec = spec("""
                "/a":{"get":{"operationId":"ok","responses":{"200":{"description":"ok"}}}},
                "/t":{"get":{"operationId":"tenant",
                  "parameters":[{"name":"X-Tenant","in":"header","required":true,"schema":{"type":"string"}}],
                  "responses":{"200":{"description":"ok"}}}}
                """);
        var result = McpApiToolBuilder.parseAndBuild(spec, null, null, null);

        assertEquals(List.of("GET /t: requires the header parameter 'X-Tenant', which generated tools cannot send"), result.unusableEndpoints());
        assertEquals(1, result.endpointCount());
    }

    @Test
    @DisplayName("when nothing usable remains the failure names the skipped operations")
    void allUnusableFailsLoudly() {
        String spec = spec("""
                "/import":{"post":{"operationId":"importAgent",
                  "requestBody":{"required":true,"content":{"application/zip":{"schema":{"type":"string"}}}},
                  "responses":{"200":{"description":"ok"}}}}
                """);

        var failure = assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.parseAndBuild(spec, null, null, null));

        assertTrue(failure.getMessage().contains("POST /import"), failure.getMessage());
        assertFalse(failure.getMessage().isBlank());
    }
}
