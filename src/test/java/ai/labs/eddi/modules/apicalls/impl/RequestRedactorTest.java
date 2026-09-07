/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.net.URLEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Direct tests for the one definition of "redacted request".
 * <p>
 * The class had no dedicated test: header coverage came incidentally through
 * {@code ApiCallExecutor}'s execute-path tests, and the resolve path — the one
 * that feeds the approver's preview and the fingerprint — had none at all. Its
 * own javadoc says the two consumers drifting apart IS the credential leak, so
 * the properties below are asserted on the redactor itself rather than through
 * whichever caller happened to exercise it.
 */
class RequestRedactorTest {

    // Zero-entropy but shape-correct: SecretRedactionFilter matches on shape, and
    // a realistic-looking literal additionally trips the repo's gitleaks scan on a
    // value that never authenticated against anything. Do not "improve" these.
    private static final String KEY = "sk-aaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String BEARER = "Bearer aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private RequestRedactor redactor;

    @BeforeEach
    void setUp() {
        var callerIdentityResolver = mock(CallerIdentityResolver.class);
        // Pass the value through untouched unless a test says otherwise — this
        // resolver only ever redacts the CURRENT caller's live token.
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        redactor = new RequestRedactor(callerIdentityResolver);
    }

    @Nested
    @DisplayName("header values are judged by shape, not only by name")
    class HeaderShape {

        @Test
        void aConventionallyNamedHeaderIsRedacted() {
            assertEquals(RequestRedactor.REDACTED, redactor.redactHeaderValue("Authorization", BEARER));
            assertEquals(RequestRedactor.REDACTED, redactor.redactHeaderValue("X-Api-Key", KEY));
        }

        @Test
        void aSecretUnderAnUnconventionalHeaderNameIsStillRedacted() {
            // The gap this closes. "x-client-auth" contains none of the sensitive
            // name fragments, the value is not a vault reference, and it is not the
            // current caller's token — so before the value-shape scan was wired in,
            // it reached the approver and the conversation document verbatim, while
            // the identical string in the body or a query parameter was caught.
            String redacted = redactor.redactHeaderValue("X-Client-Auth", BEARER);
            assertFalse(redacted.contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), redacted);
        }

        @Test
        void anApiKeyShapeUnderAnyHeaderNameIsRedacted() {
            String redacted = redactor.redactHeaderValue("X-Custom", KEY);
            assertFalse(redacted.contains(KEY), redacted);
        }

        @Test
        void aHeaderNamedPasswordIsRedactedByNameAlone() {
            // "hunter2" alone matches no value shape — SecretRedactionFilter's
            // generic rule needs the credential name INSIDE the value (e.g.
            // "password=hunter2"), not sitting in a separate header name. Only
            // isSensitiveHeaderName can catch this, and until now it didn't
            // recognize "password" despite this class's own javadoc listing it
            // as a recognized credential name (see the generic-rule reference
            // above redactUri).
            assertEquals(RequestRedactor.REDACTED, redactor.redactHeaderValue("X-Password", "hunter2"));
        }

        @Test
        void anOrdinaryHeaderIsLeftIntact() {
            // Over-redaction is its own failure: an approver who cannot read the
            // request cannot meaningfully approve it.
            assertEquals("application/json", redactor.redactHeaderValue("Content-Type", "application/json"));
        }

        @Test
        void anUnresolvedVaultReferenceIsRedacted() {
            assertEquals(RequestRedactor.REDACTED, redactor.redactHeaderValue("X-Custom", "${vault:billing-key}"));
        }

        @Test
        void aNullOrNonStringValueDoesNotThrow() {
            assertNull(redactor.redactHeaderValue("X-Custom", null));
            assertEquals("42", redactor.redactHeaderValue("X-Custom", 42));
        }

        @Test
        @DisplayName("Authentication is a credential header by NAME, not merely a secret-shaped value")
        void anAuthenticationHeaderIsRedactedByNameAlone() {
            // The exact-name lists the two detectors were reconciled against had no
            // entry for "Authentication". Asserting on the exact marker is what
            // separates the name rule from the value-shape fallback: the fallback
            // leaves the scheme in place and yields "Bearer <REDACTED>", so a mere
            // "does not contain the token" assertion would pass without the fix.
            assertEquals(RequestRedactor.REDACTED, redactor.redactHeaderValue("Authentication", BEARER));
            // A value with no recognisable shape at all isolates the name rule
            // completely — nothing else in this class can catch "hunter2".
            assertEquals(RequestRedactor.REDACTED, redactor.redactHeaderValue("Authentication", "hunter2"));
        }

        @Test
        @DisplayName("a header whose name merely contains 'auth' stays readable")
        void benignAuthSubstringHeadersAreNotRedacted() {
            // contains("auth") was one of the two readings the detectors disagreed
            // over, and over-redaction is its own failure: an approver who cannot
            // read the request cannot meaningfully approve it.
            assertEquals("jane-the-author", redactor.redactHeaderValue("Author", "jane-the-author"));
            assertEquals("eu-west-1", redactor.redactHeaderValue("Authority", "eu-west-1"));
            assertEquals("jane", redactor.redactHeaderValue("X-Authored-By", "jane"));
            // In the same test, so the negatives above cannot pass by header
            // redaction having been switched off wholesale.
            assertEquals(RequestRedactor.REDACTED, redactor.redactHeaderValue("Authentication", "hunter2"));
        }
    }

    @Nested
    @DisplayName("redactRequestMap covers every channel a credential can ride")
    class RequestMap {

        @Test
        void uriHeadersQueryAndBodyAreAllRedacted() {
            // The class invariant: one definition, and no channel left out. Each of
            // these four has been the leak at some point.
            var map = new HashMap<String, Object>();
            map.put(IRequest.KEY_URI, "https://x/y?api_key=" + KEY);
            map.put(IRequest.KEY_HEADERS, Map.of("X-Client-Auth", BEARER));
            map.put(IRequest.KEY_QUERY_PARAMS, Map.of("api_key", List.of(KEY)));
            map.put(IRequest.KEY_BODY, "{\"apiKey\":\"" + KEY + "\"}");

            redactor.redactRequestMap(map);

            assertFalse(map.get(IRequest.KEY_URI).toString().contains(KEY), "uri: " + map.get(IRequest.KEY_URI));
            assertFalse(map.get(IRequest.KEY_HEADERS).toString().contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                    "headers: " + map.get(IRequest.KEY_HEADERS));
            assertFalse(map.get(IRequest.KEY_QUERY_PARAMS).toString().contains(KEY),
                    "query: " + map.get(IRequest.KEY_QUERY_PARAMS));
            assertFalse(map.get(IRequest.KEY_BODY).toString().contains(KEY), "body: " + map.get(IRequest.KEY_BODY));
        }

        @Test
        void aHostThatLooksLikeASecretNameKeepsItsUriIntact() {
            // SecretRedactionFilter's generic rule matches name[=:]<8+ chars> and
            // its trailing class does not exclude '/', so scanning a whole URI
            // consumed everything after a host ending in one of those words plus a
            // port. The approver was then shown "https://vault-secret=<REDACTED>"
            // — no host, no path, not a URI. Losing the target of a write is worse
            // than the leak the scan defends against.
            for (String host : List.of("vault-secret", "token", "authorization", "my-password")) {
                String uri = "https://" + host + ":8200/v1/agentstore/agents/a1";
                var map = new HashMap<String, Object>();
                map.put(IRequest.KEY_URI, uri);
                redactor.redactRequestMap(map);
                assertEquals(uri, map.get(IRequest.KEY_URI), "host '" + host + "' must stay legible");
            }
        }

        @Test
        void aSecretInThePathIsStillRedactedDespiteTheAuthorityCarveOut() {
            // The carve-out must not become a bypass: the path is where a
            // templated credential actually lands, and it is still scanned.
            var map = new HashMap<String, Object>();
            map.put(IRequest.KEY_URI, "https://token:8200/v1/keys/" + KEY + "/rotate");
            redactor.redactRequestMap(map);
            String redacted = map.get(IRequest.KEY_URI).toString();
            assertFalse(redacted.contains(KEY), redacted);
            assertTrue(redacted.startsWith("https://token:8200/"), redacted);
        }

        @Test
        void aQueryParamNamedPasswordIsRedactedByNameAlone() {
            // redactQueryParamValue shares isSensitiveHeaderName with header
            // redaction — this is the same name-check gap, on the other channel
            // it feeds.
            var map = new HashMap<String, Object>();
            map.put(IRequest.KEY_URI, "https://x/y?password=hunter2");
            redactor.redactRequestMap(map);
            assertFalse(map.get(IRequest.KEY_URI).toString().contains("hunter2"), map.get(IRequest.KEY_URI).toString());
        }

        @Test
        void aPercentEncodedCredentialInTheUriIsStillRedacted() {
            // The scan must see what the value IS. HttpClientWrapper decodes into
            // queryParamsMap but toMap() hands back the raw uri, so the same
            // credential arrives here encoded — and encoding defeats the shape
            // rules: "Bearer aaa…" becomes "Bearer%20aaa…", which no longer
            // matches Bearer\s+. That produced a value redacted in queryParams and
            // plaintext one field away in uri.
            var map = new HashMap<String, Object>();
            map.put(IRequest.KEY_URI, "https://x/y?auth=" + URLEncoder.encode(BEARER, java.nio.charset.StandardCharsets.UTF_8));
            redactor.redactRequestMap(map);
            String redacted = map.get(IRequest.KEY_URI).toString();
            assertFalse(redacted.contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), redacted);
        }

        @Test
        void anEncodedVaultReferenceIsStillRedacted() {
            var map = new HashMap<String, Object>();
            map.put(IRequest.KEY_URI, "https://x/y?k=" + URLEncoder.encode("${vault:billing}", java.nio.charset.StandardCharsets.UTF_8));
            redactor.redactRequestMap(map);
            assertFalse(map.get(IRequest.KEY_URI).toString().contains("billing"), map.get(IRequest.KEY_URI).toString());
        }

        @Test
        void anOrdinaryEncodedValueKeepsItsONTHEWIREForm() {
            // Only a value the scan actually hit is replaced. Everything else is
            // emitted as-is, so the preview keeps showing what is genuinely sent
            // rather than a decoded approximation of it.
            var map = new HashMap<String, Object>();
            map.put(IRequest.KEY_URI, "https://x/y?q=hello%20world&n=1");
            redactor.redactRequestMap(map);
            assertEquals("https://x/y?q=hello%20world&n=1", map.get(IRequest.KEY_URI));
        }

        @Test
        void aNullMapDoesNotThrow() {
            assertDoesNotThrow(() -> redactor.redactRequestMap(null));
        }

        @Test
        void absentEntriesAreSimplyNotTouched() {
            var map = new HashMap<String, Object>();
            map.put(IRequest.KEY_URI, "https://x/y");
            redactor.redactRequestMap(map);
            assertEquals("https://x/y", map.get(IRequest.KEY_URI));
            assertFalse(map.containsKey(IRequest.KEY_BODY));
        }
    }
}
