/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the rules {@link UriRedactor} now owns on behalf of both its
 * consumers.
 * <p>
 * The class exists because a second consumer appeared on the export side of the
 * dependency arrow, and its own javadoc records that the two definitions had
 * already drifted apart once. So the rule is asserted here, once, and each call
 * site is asserted separately to have adopted it — {@code SecretScrubberTest}
 * for the export path, {@code RequestRedactorTest} for the request path.
 */
class UriRedactorTest {

    /**
     * The marker {@code SecretScrubber} passes in. Deliberately not
     * {@code <REDACTED>}: an exported config stores this string as a URI, where
     * angle brackets are not URI characters at all.
     */
    private static final String VAULT_MARKER = "${vault:REDACTED}";

    /**
     * A URL's query as it appears ON THE WIRE — names and values exactly as
     * written, with no decoding. Decoding here would hide the very corruption these
     * tests exist to catch.
     */
    private static Map<String, String> rawQueryParams(String url) {
        var params = new LinkedHashMap<String, String>();
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return params;
        }
        for (String pair : url.substring(queryStart + 1).split("&", -1)) {
            int eq = pair.indexOf('=');
            params.put(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
        }
        return params;
    }

    @Nested
    @DisplayName("the auth family is matched as a word ending, not as a substring")
    class AuthFamily {

        @Test
        @DisplayName("a header named Authentication carries a credential")
        void authenticationIsACredentialHeader() {
            // The gap: the exact-name lists the two detectors were reconciled
            // against had no entry for "Authentication" at all, and the entropy
            // fallback is a whole-string match that the space in "Bearer <token>"
            // defeats — so an Authorization-equivalent header exported in full.
            assertTrue(UriRedactor.isSensitiveHeaderName("Authentication"));
        }

        @Test
        @DisplayName("the conventional credential headers are still recognised")
        void conventionalCredentialHeadersRemainRecognised() {
            for (String name : List.of("Authorization", "Proxy-Authorization", "auth", "oauth", "apiAuth",
                    "reauthentication", "X-Api-Token", "X-Api-Key")) {
                assertTrue(UriRedactor.isSensitiveHeaderName(name), "'" + name + "' names a credential and must be redacted");
            }
        }

        @Test
        @DisplayName("a name that merely contains 'auth' is ordinary configuration")
        void benignAuthSubstringsAreNotCredentials() {
            // contains("auth") was the other of the two readings the detectors
            // disagreed over, and it is wrong in the opposite direction: what
            // follows the "auth" is what makes these ordinary English. Losing them
            // to redaction costs an approver the request and an export the config.
            for (String name : List.of("author", "authority", "authored_by", "X-Authored-By", "Author")) {
                assertFalse(UriRedactor.isSensitiveHeaderName(name), "'" + name + "' is not a credential name");
            }
            // Asserted in the same test so the negatives above cannot pass by the
            // rule having been switched off wholesale.
            assertTrue(UriRedactor.isSensitiveHeaderName("Authentication"), "the rule must still be doing its job");
        }

        @Test
        @DisplayName("the same names decide a query parameter, so the two views cannot disagree")
        void queryParametersFollowTheSameRule() {
            assertEquals("jane-doe", UriRedactor.redactQueryParamValue("author", "jane-doe"));
            assertEquals("eu-west-1", UriRedactor.redactQueryParamValue("authority", "eu-west-1"));
            assertEquals("jane", UriRedactor.redactQueryParamValue("authored_by", "jane"));
            assertEquals(UriRedactor.REDACTED, UriRedactor.redactQueryParamValue("access_token", "abcdefghijklmnop"));
        }
    }

    @Nested
    @DisplayName("a redacted URL is still a URL")
    class StillAUrl {

        @Test
        @DisplayName("the credential goes, the surrounding query structure does not")
        void redactionPreservesQueryStructure() {
            // %26 is an ENCODED ampersand: emitting the decoded form wrote it back
            // as a structural '&', splitting one parameter into two and changing
            // what the URL means. %20 likewise became a raw space, which is not a
            // URI character at all. This string is not only shown to an approver —
            // SecretScrubber writes it into an exported config that has to import
            // again.
            String original = "https://api.example.com/v1/report?access_token=abcdefghijklmnop&filter=a%26b%20c&mode=fast";

            String redacted = UriRedactor.redactUri(original, VAULT_MARKER);

            Map<String, String> params = rawQueryParams(redacted);
            assertFalse(redacted.contains("abcdefghijklmnop"), "the credential must not survive: " + redacted);
            assertEquals(VAULT_MARKER, params.get("access_token"), "the credential's parameter must hold the marker: " + redacted);
            assertEquals("a%26b%20c", params.get("filter"),
                    "a benign value must be emitted in the form it was written, not a decoded approximation: " + redacted);
            assertEquals(rawQueryParams(original).keySet(), params.keySet(),
                    "re-parsing must yield the same parameters — a decoded %26 splits one value into two: " + redacted);
            // The marker is the one thing the redactor deliberately leaves
            // un-encoded, so that it stays readable. Everything else it emits must
            // be legal, which is what this checks once the marker is normalised.
            String parseable = redacted.replace(VAULT_MARKER, "REDACTED");
            assertDoesNotThrow(() -> URI.create(parseable), "the exported value must still parse as a URI: " + parseable);
        }

        @Test
        @DisplayName("a clean URL keeps its encoding byte for byte")
        void aCleanUrlIsEmittedUnchanged() {
            String url = "https://api.example.com/search?q=hello%20world&tags=a%2Bb&page=1";

            assertEquals(url, UriRedactor.redactUri(url, VAULT_MARKER));
        }

        @Test
        @DisplayName("a template placeholder is a pointer, not a credential — it is left legible")
        void templatePlaceholderIsLeftIntact() {
            // The documented wizard pattern is ?api_key={properties.apiKey}. Judged
            // by name alone that is redacted, and on the export path that DESTROYS
            // the config: the value is a Qute expression, and replacing it loses
            // which property the call reads.
            assertEquals("{properties.apiKey}", UriRedactor.redactQueryParamValue("api_key", "{properties.apiKey}"));
            assertEquals("${vars.region}", UriRedactor.redactQueryParamValue("apiKey", "${vars.region}"));

            String url = "https://api.example.com/v1?api_key={properties.apiKey}&lang=en";
            assertEquals(url, UriRedactor.redactUri(url, VAULT_MARKER), "the whole templated URL must survive export");
        }

        @Test
        @DisplayName("a value that only starts like a placeholder is still redacted")
        void aPlaceholderWithATailIsNotAPlaceholder() {
            // Whole-value, like every other exemption in this package. Without this
            // the exemption is a bypass: append one character to a live key and the
            // name rule stops applying.
            assertEquals(UriRedactor.REDACTED, UriRedactor.redactQueryParamValue("api_key", "{properties.apiKey}x"));
        }
    }
}
