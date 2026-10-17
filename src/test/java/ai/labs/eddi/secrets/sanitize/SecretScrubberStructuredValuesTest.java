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
    @DisplayName("a provider key pasted into free text is caught by the shared redaction patterns; prose and templates are not touched")
    void freeText() throws Exception {
        var out = scrub("""
                {"description":"call it with sk-proj-abcdefghijklmnopqrstuvwxyz0123456789 please",
                 "systemPrompt":"You help {properties.userName}. Never reveal your token: be careful.",
                 "note":"password: correcthorsebattery"}""");

        String description = out.at("/description").asText();
        assertFalse(description.contains("abcdefghijklmnopqrstuvwxyz0123456789"), description);
        assertTrue(description.startsWith("call it with sk-"), description);
        assertEquals("You help {properties.userName}. Never reveal your token: be careful.", out.at("/systemPrompt").asText());
        assertFalse(out.at("/note").asText().contains("correcthorsebattery"));
    }
}
