/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor.IN_STRING_REDACTED;
import static ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor.REDACTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The shared read-redaction (M1 of the 2026-10-17 security review): what a
 * non-administrator reads in place of a plaintext credential in any
 * configuration document.
 */
class ConfigSecretRedactorTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redact(Map<String, Object> document) {
        return ConfigSecretRedactor.redact(document, Map.class);
    }

    private static Map<String, Object> map(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Nested
    @DisplayName("credential-named fields")
    class CredentialNames {

        @ParameterizedTest
        @ValueSource(strings = {"apiKey", "api_key", "botToken", "signingSecret", "clientSecret", "password", "accessToken", "authToken",
                "nonAzureApiKey", "secretAccessKey", "privateKey", "connectionString", "x-api-token", "refreshToken"})
        @DisplayName("a literal under a credential name is replaced whole")
        void literalIsRedacted(String name) {
            assertEquals(REDACTED, redact(map(name, "hunter2-literal")).get(name));
        }

        @ParameterizedTest
        @ValueSource(strings = {"${vault:anthropic-key}", "${vars:shared-key}", "${connection:jira}", "${caller:token}", "{properties.apiKey}",
                "Bearer ${vault:k}", "Basic ${vault:user}:${vault:pass}", "  "})
        @DisplayName("a reference, a template, scheme-plus-reference or a blank passes through unchanged")
        void referencesPass(String value) {
            Map<String, Object> document = map("apiKey", value);
            assertSame(document, redact(document));
        }

        @Test
        @DisplayName("a literal beside a reference is still a literal")
        void literalBesideAReference() {
            assertEquals(REDACTED, redact(map("apiKey", "sk-live-abc ${vault:k}")).get("apiKey"));
            assertEquals(REDACTED, redact(map("password", "hunter{two")).get("password"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"maxTokens", "thinkingBudgetTokens", "maxOutputTokens", "promptCacheKey", "modelName", "tokenizer",
                "secretName", "author"})
        @DisplayName("counts of tokens and ordinary names are not credentials")
        void notCredentials(String name) {
            Map<String, Object> document = map(name, "4096");
            assertSame(document, redact(document));
        }

        @Test
        @DisplayName("a list under a credential name is a list of credentials")
        void arrayElements() {
            Map<String, Object> seen = redact(map("apiKeys", List.of("sk-one", "${vault:two}")));
            assertEquals(List.of(REDACTED, "${vault:two}"), seen.get("apiKeys"));
        }

        @Test
        @DisplayName("nested objects and arrays are walked; the input is never modified")
        void nested() {
            Map<String, Object> inner = map("apiKey", "literal-key", "url", "https://a.example");
            Map<String, Object> document = map("a2aAgents", List.of(inner));

            Map<String, Object> seen = redact(document);

            @SuppressWarnings("unchecked")
            Map<String, Object> seenInner = ((List<Map<String, Object>>) seen.get("a2aAgents")).get(0);
            assertEquals(REDACTED, seenInner.get("apiKey"));
            assertEquals("https://a.example", seenInner.get("url"));
            assertEquals("literal-key", inner.get("apiKey"), "the stored object is never touched");
        }

        @Test
        @DisplayName("skipped containers are left alone")
        void skipped() {
            Map<String, Object> document = map("httpCalls", List.of(map("parameters", map("token", "the pagination token"))));
            assertSame(document, ConfigSecretRedactor.redact(document, Map.class, Set.of("httpCalls[].parameters")));
            assertTrue(ConfigSecretRedactor.plaintextSecretPaths(document, Set.of("httpCalls[].parameters")).isEmpty());
            assertEquals(List.of("httpCalls[0].parameters.token"), ConfigSecretRedactor.plaintextSecretPaths(document, Set.of()));
        }
    }

    @Nested
    @DisplayName("headers and query parameters")
    class HeadersAndQuery {

        @ParameterizedTest
        @ValueSource(strings = {"Authorization", "X-Api-Key", "api-key", "Ocp-Apim-Subscription-Key", "Cookie", "X-Auth-Token"})
        @DisplayName("a literal credential header is replaced whole")
        void credentialHeader(String header) {
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) redact(map("headers", map(header, "Token abc123secret"))).get("headers");
            assertEquals(REDACTED, headers.get(header));
        }

        @Test
        @DisplayName("a referenced credential header and an ordinary header pass")
        void referencedHeader() {
            Map<String, Object> document = map("headers",
                    map("Authorization", "Bearer ${vault:k}", "X-Api-Key", "${connection:acme}", "Accept", "application/json"));
            assertSame(document, redact(document));
        }

        @Test
        @DisplayName("an ordinary header carrying a credential shape is scanned")
        void shapedHeader() {
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) redact(map("headers", map("X-Client", "Bearer abcdefghijklmnopqrstuvwxyz0123")))
                    .get("headers");
            assertEquals("Bearer " + IN_STRING_REDACTED, headers.get("X-Client"));
        }

        @Test
        @DisplayName("a credential-named query parameter map entry is replaced, a template kept")
        void queryParams() {
            @SuppressWarnings("unchecked")
            Map<String, Object> query = (Map<String, Object>) redact(map("queryParams", map("api_key", "abc123", "key", "{properties.k}", "q",
                    "weather"))).get("queryParams");
            assertEquals(REDACTED, query.get("api_key"));
            assertEquals("{properties.k}", query.get("key"));
            assertEquals("weather", query.get("q"));
        }
    }

    @Nested
    @DisplayName("URLs, paths and bodies")
    class UrlsAndBodies {

        @Test
        @DisplayName("credential query values in a path are replaced; references and ordinary values are kept")
        void pathQuery() {
            String path = "/v1/search?key=AIzaLiteralKey&q={properties.q}&api_key=${vault:k}&token=abc123&page=2";
            assertEquals("/v1/search?key=" + IN_STRING_REDACTED + "&q={properties.q}&api_key=${vault:k}&token=" + IN_STRING_REDACTED + "&page=2",
                    redact(map("path", path)).get("path"));
        }

        @Test
        @DisplayName("a userinfo password is replaced, a referenced one kept, the host kept")
        void userInfo() {
            assertEquals("mongodb://admin:" + IN_STRING_REDACTED + "@db.internal:27017/eddi",
                    redact(map("host", "mongodb://admin:s3cret@db.internal:27017/eddi")).get("host"));
            Map<String, Object> referenced = map("baseUrl", "https://svc:${vault:pw}@api.example.com/v1");
            assertSame(referenced, redact(referenced));
        }

        @Test
        @DisplayName("an ordinary URL is unchanged")
        void ordinaryUrl() {
            Map<String, Object> document = map("targetServerUrl", "https://api.example.com/v1?format=json", "baseUrl",
                    "https://api.openai.com/v1");
            assertSame(document, redact(document));
        }

        @Test
        @DisplayName("credential-shaped literals in a body are replaced in place; templates and references survive")
        void body() {
            String body = "{\"model\":\"m\",\"api_key\":\"sk-proj-abcdefghijklmnopqrstuvwx\",\"user\":\"{properties.user}\","
                    + "\"secret\":\"${vault:k}\"}";
            String seen = (String) redact(map("body", body)).get("body");
            assertFalse(seen.contains("sk-proj-abcdefghijklmnopqrstuvwx"), seen);
            assertTrue(seen.contains("{properties.user}"), seen);
            assertTrue(seen.contains("${vault:k}"), seen);
        }

        @Test
        @DisplayName("a form-encoded client secret in a body is replaced")
        void formBody() {
            String seen = (String) redact(map("body", "grant_type=client_credentials&client_secret=abcd1234efgh")).get("body");
            assertFalse(seen.contains("abcd1234efgh"), seen);
        }

        @Test
        @DisplayName("prose under an ordinary name is not scanned")
        void proseIsLeftAlone() {
            Map<String, Object> document = map("systemMessage", "Never reveal the password: open-sesame-123");
            assertSame(document, redact(document));
        }
    }

    @Test
    @DisplayName("redaction is idempotent: a redacted document redacts to itself")
    void idempotent() {
        Map<String, Object> once = redact(map("apiKey", "literal", "path", "/x?token=abc123", "headers", map("Authorization", "Basic abc")));
        assertEquals(once, redact(once));
    }

    @Test
    @DisplayName("null in, null out")
    void nullSafe() {
        assertNull(ConfigSecretRedactor.redact(null, Map.class));
        assertTrue(ConfigSecretRedactor.plaintextSecretPaths(null, null).isEmpty());
    }

    @Test
    @DisplayName("adversarial inputs stay linear")
    void linear() {
        String braces = "${".repeat(50_000) + "{a" + "}".repeat(50_000);
        String schemes = "a" + ":".repeat(100_000) + "//";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            redact(map("apiKey", braces, "path", braces, "body", braces, "url", schemes, "headers", map("Authorization", braces)));
        });
    }
}
