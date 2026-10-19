/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security review 2026-10-17, M8: the export/sync scrubber judged every string
 * whole, so credentials inside structured values — a relative path's query
 * string, a form-encoded or JSON-in-a-string body, a Cookie header, a provider
 * key pasted into free text — left in clear. Pointers (vault references,
 * template expressions) must stay legible for the round trip.
 */
@DisplayName("SecretScrubber — structured values and free text")
class SecretScrubberStructuredValuesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretScrubber scrubber = new SecretScrubber(mapper);

    private JsonNode scrub(String json) throws Exception {
        return mapper.readTree(scrubber.scrubJson(json));
    }

    @Test
    @DisplayName("a query string in request.path is judged parameter by parameter")
    void pathQueryString() throws Exception {
        var out = scrub("""
                {"request":{"path":"/v1/search?api_key=plainkey123&q=hello&token=${vault:t}&limit={properties.n}"}}""");
        String path = out.at("/request/path").asText();

        assertFalse(path.contains("plainkey123"), path);
        assertTrue(path.startsWith("/v1/search?api_key=" + SecretScrubber.REDACTED), path);
        assertTrue(path.contains("q=hello"), path);
        assertTrue(path.contains("token=${vault:t}"), "a vault reference stays legible: " + path);
        assertTrue(path.contains("limit={properties.n}"), "a template expression stays legible: " + path);
    }

    @Test
    @DisplayName("a form-encoded body loses its credential values only")
    void formBody() throws Exception {
        var out = scrub("""
                {"request":{"body":"grant_type=client_credentials&client_id=abc&client_secret=hunter2"}}""");
        String body = out.at("/request/body").asText();

        assertFalse(body.contains("hunter2"), body);
        assertTrue(body.contains("client_id=abc"), body);
        assertTrue(body.contains("grant_type=client_credentials"), body);
    }

    @Test
    @DisplayName("a JSON document inside a string is scrubbed like the document around it, pointers kept")
    void jsonInAString() throws Exception {
        var out = scrub("""
                {"request":{"body":"{\\"apiKey\\":\\"literal-key-value\\",\\"q\\":\\"{memory.current.input}\\",\\"token\\":\\"{properties.t}\\"}"},
                 "serviceAccountJson":"{\\"type\\":\\"service_account\\",\\"private_key\\":\\"-----BEGIN PRIVATE KEY-----abc\\"}"}""");
        var body = mapper.readTree(out.at("/request/body").asText());
        var account = mapper.readTree(out.at("/serviceAccountJson").asText());

        assertEquals(SecretScrubber.REDACTED, body.get("apiKey").asText());
        assertEquals("{memory.current.input}", body.get("q").asText());
        assertEquals("{properties.t}", body.get("token").asText(), "a templated token is a pointer, not a credential");
        assertEquals(SecretScrubber.REDACTED, account.get("private_key").asText());
        assertEquals("service_account", account.get("type").asText());
    }

    @Test
    @DisplayName("an unchanged body template keeps its exact text")
    void unchangedBodyKeepsItsFormatting() throws Exception {
        String body = "{ \\\"q\\\" : \\\"{memory.current.input}\\\",\\n  \\\"n\\\": 3 }";
        var out = scrub("{\"request\":{\"body\":\"" + body + "\"}}");

        assertEquals("{ \"q\" : \"{memory.current.input}\",\n  \"n\": 3 }", out.at("/request/body").asText());
    }

    @Test
    @DisplayName("a Cookie header is a credential")
    void cookieHeader() throws Exception {
        var out = scrub("""
                {"request":{"headers":{"Cookie":"session=abc123","Accept":"application/json"}}}""");

        assertEquals(SecretScrubber.REDACTED, out.at("/request/headers/Cookie").asText());
        assertEquals("application/json", out.at("/request/headers/Accept").asText());
    }

    @Test
    @DisplayName("a provider key pasted into free text is caught by its shape alone")
    void freeTextShapes() throws Exception {
        var out = scrub("""
                {"description":"call it with sk-proj-abcdefghijklmnopqrstuvwxyz0123456789 please",
                 "notes":["gh token ghp_abcdefghijklmnopqrstuvwxyz0123456789AB",
                          "aws AKIAIOSFODNN7EXAMPLE", "db mongodb://eddi:hunter2secret@db:27017/eddi"]}""");

        String description = out.at("/description").asText();
        assertFalse(description.contains("abcdefghijklmnopqrstuvwxyz0123456789"), description);
        assertTrue(description.startsWith("call it with sk-"), description);
        assertTrue(description.endsWith(" please"), description);
        assertFalse(out.at("/notes/0").asText().contains("ghp_abcdefghij"), out.at("/notes/0").asText());
        assertFalse(out.at("/notes/1").asText().contains("AKIAIOSFODNN7EXAMPLE"), out.at("/notes/1").asText());
        assertEquals("db mongodb://eddi:" + SecretRedactionFilter.REDACTED + "@db:27017/eddi", out.at("/notes/2").asText());
    }

    @Test
    @DisplayName("name-bound rules apply to request material only")
    void nameBoundRulesOnlyOnRequestMaterial() throws Exception {
        var out = scrub("""
                {"request":{"body":"password: correcthorsebattery"},
                 "note":"password: correcthorsebattery"}""");

        assertFalse(out.at("/request/body").asText().contains("correcthorsebattery"));
        assertEquals("password: correcthorsebattery", out.at("/note").asText(), "prose is never rewritten by a name-bound rule");
    }

    /**
     * Export, instance sync and the upgrade path all run this scrubber; a prompt
     * that merely talks about credentials must come back byte for byte.
     */
    @Test
    @DisplayName("prompts and outputs that talk about credentials round-trip byte-identical")
    void proseRoundTripsUnchanged() throws Exception {
        var config = mapper.createObjectNode();
        config.put("systemPrompt",
                "Bearer token handling: never echo the password: policy. The api key is set in the vault as ${vault:openai}.");
        config.put("description", "You help {properties.userName}. Never reveal your token: be careful.");
        config.putObject("output").putArray("valueAlternatives").addObject().put("type", "text").put("text",
                "token = 12345678 is not a secret");
        config.putArray("behaviorRules").addObject().put("valueString", "password=changeme is the example in the manual");
        String json = mapper.writeValueAsString(config);

        assertEquals(json, scrubber.scrubJson(json));
    }

    @Test
    @DisplayName("a literal provider key in a prompt is the only thing replaced")
    void literalKeyInAPromptIsTheOnlyChange() throws Exception {
        var config = mapper.createObjectNode();
        config.put("systemPrompt", "Use sk-proj-abcdefghijklmnopqrstuvwxyz0123456789 for the password: policy check.");
        String json = mapper.writeValueAsString(config);

        String expected = json.replace("sk-proj-abcdefghijklmnopqrstuvwxyz0123456789", "sk-" + SecretRedactionFilter.REDACTED);
        assertEquals(expected, scrubber.scrubJson(json));
    }

    @Test
    @DisplayName("kebab-case prose containing sk- inside a word round-trips byte-identical")
    void kebabCaseProseIsNotAKey() throws Exception {
        var config = mapper.createObjectNode();
        config.put("description", "the risk-assessment-workflow-handler calls task-dispatch-and-reconcile-loop twice");
        String json = mapper.writeValueAsString(config);

        assertEquals(json, scrubber.scrubJson(json));
        assertFalse(scrubber.containsCredential(json));
    }

    @Test
    @DisplayName("an unchanged embedded document keeps its surrounding whitespace")
    void unchangedEmbeddedDocumentKeepsItsWhitespace() throws Exception {
        var config = mapper.createObjectNode();
        config.putObject("request").put("body", "  {\n  \"q\": \"{memory.current.input}\"\n}\n");
        String json = mapper.writeValueAsString(config);

        assertEquals(json, scrubber.scrubJson(json));
        assertFalse(scrubber.containsCredential(json));
    }
}
