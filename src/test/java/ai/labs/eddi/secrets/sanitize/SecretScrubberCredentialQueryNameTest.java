/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The export scrubber hides the query parameters an editor's read hides
 * ({@link ConfigSecretRedactor#isCredentialQueryName}). An opaque signed-URL
 * signature under {@code sig} passed every shape test, so the archive carried
 * the value GET redacted.
 */
@DisplayName("SecretScrubber — credential query parameter names")
class SecretScrubberCredentialQueryNameTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretScrubber scrubber = new SecretScrubber(mapper);

    private String scrubbedField(String json, String pointer) throws Exception {
        return mapper.readTree(scrubber.scrubJson(json)).at(pointer).asText();
    }

    @Test
    @DisplayName("a relative path's sig parameter is redacted")
    void relativePathSignature() throws Exception {
        String path = scrubbedField("""
                {"request":{"path":"/container/blob?sv=2022-11-02&sig=abcdEFGH1234&q=x"}}""", "/request/path");

        assertFalse(path.contains("abcdEFGH1234"), path);
        assertTrue(path.contains("sig=" + SecretScrubber.REDACTED), path);
        assertTrue(path.contains("sv=2022-11-02") && path.contains("q=x"), path);
    }

    @Test
    @DisplayName("an absolute URL's key and signature parameters are redacted")
    void absoluteUrl() throws Exception {
        String url = scrubbedField("""
                {"targetServerUrl":"https://maps.example.com/api?key=plainkey1&signature=s1gn&zoom=3"}""", "/targetServerUrl");

        assertFalse(url.contains("plainkey1"), url);
        assertFalse(url.contains("s1gn"), url);
        assertTrue(url.startsWith("https://maps.example.com/api?") && url.contains("zoom=3"), url);
    }

    @Test
    @DisplayName("a percent-encoded credential parameter name is judged decoded, on both export branches")
    void encodedNames() throws Exception {
        // %6Bey is key to the server that decodes the query; the additional
        // query-name rule (key, signature, accessKey, ...) used to compare the encoded
        // spelling only. (%73ig was already caught: sig is a header word too.)
        String path = scrubbedField("""
                {"request":{"path":"/container/blob?sv=2022-11-02&%6Bey=abcdEFGH1234"}}""", "/request/path");
        String url = scrubbedField("""
                {"targetServerUrl":"https://maps.example.com/api?%6Bey=plainkey1&zoom=3"}""", "/targetServerUrl");

        assertFalse(path.contains("abcdEFGH1234"), path);
        assertTrue(path.contains("%6Bey=" + SecretScrubber.REDACTED), path);
        assertFalse(url.contains("plainkey1"), url);
        assertTrue(url.contains("%6Bey=") && url.contains("zoom=3"), url);
    }

    @Test
    @DisplayName("a pointer under a credential name stays legible")
    void pointerKept() throws Exception {
        String path = scrubbedField("""
                {"request":{"path":"/x?key=${vault:maps-key}&sig={properties.sig}"}}""", "/request/path");

        assertTrue(path.contains("key=${vault:maps-key}"), path);
        assertTrue(path.contains("sig={properties.sig}"), path);
    }

    @Test
    @DisplayName("a form body's key field is data, not a query credential")
    void formBodyUntouched() throws Exception {
        String body = scrubbedField("""
                {"request":{"body":"key=customer-42&action=lookup"}}""", "/request/body");

        assertTrue(body.contains("key=customer-42"), body);
    }
}
