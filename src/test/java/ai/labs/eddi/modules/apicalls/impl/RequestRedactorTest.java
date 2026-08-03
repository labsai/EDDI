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
