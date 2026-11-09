/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.apicalls.impl.RequestRedactor.REDACTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * B11 — a server that echoes a substituted secret back ENCODED. Redaction by
 * value used to replace the verbatim plaintext only, so a base64 echo of the
 * header was stored in conversation memory as it came.
 */
@DisplayName("ApiCallExecutor — encoded echoes of a substituted secret in a success body")
class ApiCallExecutorEncodedEchoRedactionTest {

    /** The review's secret, and its echoes. */
    private static final String SECRET = "deadbeefcafe1234";
    private static final String SECRET_BASE64 = "ZGVhZGJlZWZjYWZlMTIzNA==";

    /** A secret with characters every encoding changes. */
    private static final String SPECIAL = "p@ss word/123~?>>";

    @Nested
    @DisplayName("text body")
    class TextBody {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "ZGVhZGJlZWZjYWZlMTIzNA==", // base64, padded
                "ZGVhZGJlZWZjYWZlMTIzNA" // base64, unpadded
        })
        @DisplayName("the review's base64 echo")
        void base64(String echo) {
            String redacted = ApiCallExecutor.redactSuccessText("x-api-key: " + echo + "\n", Set.of(SECRET));
            assertEquals("x-api-key: " + REDACTED + "\n", redacted);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "cEBzcyB3b3JkLzEyM34/Pj4=", // base64
                "cEBzcyB3b3JkLzEyM34_Pj4", // base64url, unpadded
                "p%40ss+word%2F123%7E%3F%3E%3E", // form encoding
                "p%40ss%20word%2F123~%3F%3E%3E", // RFC 3986 spaces, literal tilde
                "p%40ss%20word%2f123~%3f%3e%3e", // lower-case hex
                "p@ss word\\/123~?>>", // JSON-escaped slash
                "p@ss word/123~?&gt;&gt;" // HTML
        })
        @DisplayName("each encoding of a secret with special characters")
        void eachEncoding(String echo) {
            String redacted = ApiCallExecutor.redactSuccessText("echo=" + echo + ";", Set.of(SPECIAL));
            assertEquals("echo=" + REDACTED + ";", redacted);
        }

        @Test
        @DisplayName("a JSON-escaped secret in a body that is text, not parsed JSON")
        void jsonEscaped() {
            String secret = "ab\"cd\\ef-1234";
            String body = "{not json \"ab\\\"cd\\\\ef-1234\"";
            assertEquals("{not json \"" + REDACTED + "\"", ApiCallExecutor.redactSuccessText(body, Set.of(secret)));
        }

        @Test
        @DisplayName("a value below the minimum length keeps its encodings in the text")
        void shortSecretUntouched() {
            // base64("alice12"), and the value is 7 characters
            String body = "user YWxpY2UxMg== logged in";
            assertEquals(body, ApiCallExecutor.redactSuccessText(body, Set.of("alice12")));
        }

        @Test
        @DisplayName("an unrelated body is unchanged")
        void unrelated() {
            String body = "status=ok; token=ZmFrZS10b2tlbg==; path=/a%20b";
            assertEquals(body, ApiCallExecutor.redactSuccessText(body, Set.of(SECRET, SPECIAL)));
        }

        @Test
        @DisplayName("a reversed secret is out of scope and stays")
        void reversedIsNotCovered() {
            String reversed = new StringBuilder(SECRET).reverse().toString();
            assertEquals(reversed, ApiCallExecutor.redactSuccessText(reversed, Set.of(SECRET)));
        }

        @Test
        @DisplayName("a Basic credential: the decoded password is redacted, the username stays")
        void basicCredentialPassword() {
            // base64("svc-user:Sup3rS3cretPw")
            Set<String> secrets = Set.of("Basic c3ZjLXVzZXI6U3VwM3JTM2NyZXRQdw==", "c3ZjLXVzZXI6U3VwM3JTM2NyZXRQdw==");
            String redacted = ApiCallExecutor.redactSuccessText("{user: svc-user, pass: Sup3rS3cretPw}", secrets);
            assertEquals("{user: svc-user, pass: " + REDACTED + "}", redacted);
        }
    }

    @Nested
    @DisplayName("parsed JSON body")
    class TreeBody {

        @Test
        @DisplayName("encodings of a long secret are removed from string values, also inside longer text")
        void longSecretEncodings() {
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("headers", Map.of("X-Api-Key", SECRET_BASE64));
            tree.put("args", "q=" + "p%40ss+word%2F123%7E%3F%3E%3E" + "&page=2");
            tree.put("html", "<td>p@ss word/123~?&gt;&gt;</td>");
            tree.put("count", 3);

            @SuppressWarnings("unchecked")
            var redacted = (Map<String, Object>) ApiCallExecutor.redactSuccessTree(tree, Set.of(SECRET, SPECIAL));

            assertEquals(Map.of("X-Api-Key", REDACTED), redacted.get("headers"));
            assertEquals("q=" + REDACTED + "&page=2", redacted.get("args"));
            assertEquals("<td>" + REDACTED + "</td>", redacted.get("html"));
            assertEquals(3, redacted.get("count"));
        }

        @Test
        @DisplayName("a short secret's base64 is replaced where a value IS it, not inside other text")
        void shortSecretExactOnly() {
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("user", "YWxpY2UxMg==");
            tree.put("note", "prefix YWxpY2UxMg== suffix");
            tree.put("list", List.of("YWxpY2UxMg"));

            @SuppressWarnings("unchecked")
            var redacted = (Map<String, Object>) ApiCallExecutor.redactSuccessTree(tree, Set.of("alice12"));

            assertEquals(REDACTED, redacted.get("user"));
            assertEquals("prefix YWxpY2UxMg== suffix", redacted.get("note"));
            assertEquals(List.of(REDACTED), redacted.get("list"));
        }

        @Test
        @DisplayName("below four characters a short secret is matched only as itself, not by encoding")
        void tinySecretNoEncodings() {
            // base64("abc")
            Map<String, Object> tree = Map.of("v", "YWJj", "w", "abc");

            @SuppressWarnings("unchecked")
            var redacted = (Map<String, Object>) ApiCallExecutor.redactSuccessTree(tree, Set.of("abc"));

            assertEquals("YWJj", redacted.get("v"));
            assertEquals(REDACTED, redacted.get("w"));
        }

        @Test
        @DisplayName("an unrelated body comes back as it was")
        void unrelated() {
            Map<String, Object> tree = Map.of("token", "ZmFrZS10b2tlbg==", "path", "/a%20b", "n", 12345678);
            assertSame(tree, ApiCallExecutor.redactSuccessTree(tree, Set.of(SECRET, SPECIAL, "alice12")));
        }

        @Test
        @DisplayName("the verbatim secret is still removed")
        void verbatimStillRemoved() {
            Map<String, Object> tree = Map.of("echo", "key " + SECRET);
            @SuppressWarnings("unchecked")
            var redacted = (Map<String, Object>) ApiCallExecutor.redactSuccessTree(tree, Set.of(SECRET));
            assertFalse(String.valueOf(redacted).contains(SECRET), redacted.toString());
        }
    }
}
