/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fingerprint is what makes approval bind to a request rather than a tool
 * name, so these tests are about one question: can two requests that would do
 * <em>different</em> things share a fingerprint?
 */
class ResolvedRequestTest {

    /**
     * Single-valued query params, spelled the way callers usually think of them.
     */
    private static ResolvedRequest request(String method, String uri, Map<String, String> query, Map<String, String> headers, String body) {
        var multi = new LinkedHashMap<String, List<String>>();
        query.forEach((key, value) -> multi.put(key, List.of(value)));
        return ResolvedRequest.of(method, uri, multi, headers, body, true);
    }

    private static ResolvedRequest baseline() {
        return request("POST", "https://eddi.example/agentstore/agents", Map.of("version", "1"), Map.of("Content-Type", "application/json"),
                "{\"name\":\"ops\"}");
    }

    @Nested
    @DisplayName("what must NOT change the fingerprint")
    class Stable {

        @Test
        void identicalRequestsAgree() {
            assertEquals(baseline().fingerprint(), baseline().fingerprint());
        }

        @Test
        void headerOrderDoesNotMatter() {
            var first = new LinkedHashMap<String, String>();
            first.put("Accept", "application/json");
            first.put("Content-Type", "application/json");
            var second = new LinkedHashMap<String, String>();
            second.put("Content-Type", "application/json");
            second.put("Accept", "application/json");

            assertEquals(request("GET", "https://x/y", Map.of(), first, null).fingerprint(),
                    request("GET", "https://x/y", Map.of(), second, null).fingerprint());
        }

        @Test
        void headerNameCasingDoesNotMatter() {
            // HTTP header names are case-insensitive, so casing cannot change what
            // the request does and must not change the hash.
            assertEquals(request("GET", "https://x/y", Map.of(), Map.of("Content-Type", "application/json"), null).fingerprint(),
                    request("GET", "https://x/y", Map.of(), Map.of("content-type", "application/json"), null).fingerprint());
        }

        @Test
        void methodCasingDoesNotMatter() {
            assertEquals(request("post", "https://x/y", Map.of(), Map.of(), null).fingerprint(),
                    request("POST", "https://x/y", Map.of(), Map.of(), null).fingerprint());
        }

        @Test
        void credentialValuesCannotParticipateBecauseTheyAreAlreadyRedacted() {
            // The property the whole design rests on: an approver is routinely not
            // the requester, so the resolved Authorization header differs between
            // gate time and execution time. Both arrive here redacted to the same
            // marker, so the fingerprint is stable across approvers — and a guard
            // that fired on every cross-user approval would simply be switched off.
            var atGateTime = Map.of("Authorization", RequestRedactor.REDACTED);
            var atExecutionTime = Map.of("Authorization", RequestRedactor.REDACTED);
            assertEquals(request("POST", "https://x/y", Map.of(), atGateTime, "{}").fingerprint(),
                    request("POST", "https://x/y", Map.of(), atExecutionTime, "{}").fingerprint());
        }
    }

    @Nested
    @DisplayName("what MUST change the fingerprint")
    class Discriminating {

        @Test
        void method() {
            assertNotEquals(baseline().fingerprint(),
                    request("DELETE", "https://eddi.example/agentstore/agents", Map.of("version", "1"),
                            Map.of("Content-Type", "application/json"), "{\"name\":\"ops\"}").fingerprint());
        }

        @Test
        void targetUri() {
            assertNotEquals(baseline().fingerprint(),
                    request("POST", "https://eddi.example/agentstore/agents/OTHER", Map.of("version", "1"),
                            Map.of("Content-Type", "application/json"), "{\"name\":\"ops\"}").fingerprint());
        }

        @Test
        void queryParameterValue() {
            assertNotEquals(baseline().fingerprint(),
                    request("POST", "https://eddi.example/agentstore/agents", Map.of("version", "99"),
                            Map.of("Content-Type", "application/json"), "{\"name\":\"ops\"}").fingerprint());
        }

        @Test
        void anAddedQueryParameter() {
            assertNotEquals(baseline().fingerprint(),
                    request("POST", "https://eddi.example/agentstore/agents", Map.of("version", "1", "force", "true"),
                            Map.of("Content-Type", "application/json"), "{\"name\":\"ops\"}").fingerprint());
        }

        @Test
        void body() {
            assertNotEquals(baseline().fingerprint(),
                    request("POST", "https://eddi.example/agentstore/agents", Map.of("version", "1"),
                            Map.of("Content-Type", "application/json"), "{\"name\":\"attacker\"}").fingerprint());
        }

        @Test
        void aNonCredentialHeader() {
            // Headers are not excluded wholesale — only credential VALUES are
            // redacted. A changed X-Forwarded-Host still changes the request.
            assertNotEquals(request("POST", "https://x/y", Map.of(), Map.of("X-Tenant", "acme"), "{}").fingerprint(),
                    request("POST", "https://x/y", Map.of(), Map.of("X-Tenant", "evil"), "{}").fingerprint());
        }

        @Test
        void anAddedHeader() {
            assertNotEquals(request("POST", "https://x/y", Map.of(), Map.of("X-Tenant", "acme"), "{}").fingerprint(),
                    request("POST", "https://x/y", Map.of(), Map.of("X-Tenant", "acme", "X-Override", "1"), "{}").fingerprint());
        }
    }

    @Nested
    @DisplayName("field boundaries cannot be forged")
    class Injection {

        @Test
        void bodyContentCannotImpersonateAHeaderField() {
            // Without length prefixes, a canonical form of "name:value\n" lets a body
            // containing a newline plus "header.x:..." produce the same byte stream
            // as a genuine extra header — two different requests, one fingerprint.
            var withHeader = request("POST", "https://x/y", Map.of(), Map.of("x", "1"), "");
            var withBodyPretendingToBeAHeader = request("POST", "https://x/y", Map.of(), Map.of(), "\nheader.x:1:1\n");
            assertNotEquals(withHeader.fingerprint(), withBodyPretendingToBeAHeader.fingerprint());
        }

        @Test
        void movingContentBetweenAdjacentFieldsChangesIt() {
            assertNotEquals(request("POST", "https://x/ab", Map.of(), Map.of(), "").fingerprint(),
                    request("POST", "https://x/a", Map.of(), Map.of(), "b").fingerprint());
        }

        @Test
        void aRepeatedQueryParameterCannotBeForgedByOneValueContainingTheSeparator() {
            // The display form joins repeats with ", ". If the fingerprint were
            // computed over THAT, then ?tag=a&tag=b and a single tag whose value is
            // literally "a, b" would hash identically — two different requests, one
            // fingerprint. The canonical form emits one length-prefixed field per
            // value instead, so the two stay distinct.
            var repeated = new LinkedHashMap<String, List<String>>();
            repeated.put("tag", List.of("a", "b"));
            var singleJoined = new LinkedHashMap<String, List<String>>();
            singleJoined.put("tag", List.of("a, b"));

            assertNotEquals(ResolvedRequest.of("GET", "https://x/y", repeated, Map.of(), null, true).fingerprint(),
                    ResolvedRequest.of("GET", "https://x/y", singleJoined, Map.of(), null, true).fingerprint());
        }

        @Test
        void reorderingRepeatedValuesChangesIt() {
            // ?tag=a&tag=b and ?tag=b&tag=a are different requests to any server
            // that reads the first value, so order within a name is preserved.
            var forward = new LinkedHashMap<String, List<String>>();
            forward.put("tag", List.of("a", "b"));
            var reversed = new LinkedHashMap<String, List<String>>();
            reversed.put("tag", List.of("b", "a"));

            assertNotEquals(ResolvedRequest.of("GET", "https://x/y", forward, Map.of(), null, true).fingerprint(),
                    ResolvedRequest.of("GET", "https://x/y", reversed, Map.of(), null, true).fingerprint());
        }

        @Test
        void anEmptyValueIsDistinctFromAnAbsentOne() {
            assertNotEquals(request("POST", "https://x/y", Map.of("a", ""), Map.of(), null).fingerprint(),
                    request("POST", "https://x/y", Map.of(), Map.of(), null).fingerprint());
        }
    }

    @Nested
    @DisplayName("unpinnable calls")
    class Unpinned {

        @Test
        void produceNoFingerprintButStillPreview() {
            var resolved = ResolvedRequest.of("POST", "https://x/y", Map.of(), Map.of("Accept", "*/*"), "{}", false);
            assertNull(resolved.fingerprint());
            assertFalse(resolved.isPinned());
            // The preview is the point of resolving at all — it survives.
            assertEquals("https://x/y", resolved.uri());
            assertEquals("{}", resolved.body());
            assertEquals(Map.of("accept", "*/*"), resolved.headers());
        }

        @Test
        void pinnedOnesReportSo() {
            assertTrue(baseline().isPinned());
        }
    }

    @Nested
    @DisplayName("the stored body is redacted, the fingerprinted one is not")
    class BodyRedaction {

        // Deliberately zero-entropy. These have to carry the `sk-` + 20 chars
        // shape, because that shape is exactly what SecretRedactionFilter's
        // OpenAI rule matches and what these tests assert on — but a realistic
        // random-looking value additionally trips the repo's gitleaks scan, which
        // then fails CI on a literal that never authenticated against anything.
        // Repeated characters keep the shape and drop the entropy. Do not
        // "improve" these into realistic keys.
        private static final String KEY = "sk-aaaaaaaaaaaaaaaaaaaaaaaaaa";
        private static final String OTHER_KEY = "sk-bbbbbbbbbbbbbbbbbbbbbbbbbb";

        @Test
        void aSecretInTheBodyNeverReachesTheStoredCopy() {
            // The approver is routinely not the requester, so anything kept here is
            // shown to someone who was never entrusted with it.
            var resolved = request("POST", "https://x/y", Map.of(), Map.of(), "{\"apiKey\":\"" + KEY + "\"}");
            assertFalse(resolved.body().contains(KEY), resolved.body());
            assertTrue(resolved.body().contains("REDACTED"), resolved.body());
        }

        @Test
        void twoDifferentSecretsDoNotShareAFingerprint() {
            // The reason the body is hashed RAW. Redacting first collapses both of
            // these to "sk-<REDACTED>", so a swapped credential would sail through
            // the pre-execution re-check as an unchanged request.
            assertNotEquals(request("POST", "https://x/y", Map.of(), Map.of(), "{\"apiKey\":\"" + KEY + "\"}").fingerprint(),
                    request("POST", "https://x/y", Map.of(), Map.of(), "{\"apiKey\":\"" + OTHER_KEY + "\"}").fingerprint());
        }

        @Test
        void theSameSecretStillAgreesWithItself() {
            // Redaction must not make the fingerprint unstable either — the whole
            // guard is useless if an unchanged request fails its own re-check.
            assertEquals(request("POST", "https://x/y", Map.of(), Map.of(), "{\"apiKey\":\"" + KEY + "\"}").fingerprint(),
                    request("POST", "https://x/y", Map.of(), Map.of(), "{\"apiKey\":\"" + KEY + "\"}").fingerprint());
        }

        @Test
        void nonSecretBodyContentIsLeftAlone() {
            // Over-redaction is its own failure: an approver who cannot read the
            // request cannot meaningfully approve it.
            var resolved = request("POST", "https://x/y", Map.of(), Map.of(), "{\"name\":\"billing-agent\",\"maxTurns\":5}");
            assertEquals("{\"name\":\"billing-agent\",\"maxTurns\":5}", resolved.body());
        }

        /**
         * A vault reference stays LEGIBLE. It is a pointer to a secret, not a secret —
         * and the correct alternative to writing one down. The approver has to see
         * WHICH credential a request will use ("is this about to touch the production
         * key?"), and masking it also made every correct, vault-referencing request
         * display a redaction marker, training approvers to read that marker as normal
         * when it is precisely the signal that a secret LITERAL was embedded. See
         * {@code SecretRedactionFilter}.
         */
        @Test
        void aVaultReferenceIsLeftLegible() {
            var resolved = request("POST", "https://x/y", Map.of(), Map.of(), "{\"apiKey\":\"${vault:openai-key}\"}");
            assertTrue(resolved.body().contains("${vault:openai-key}"), resolved.body());
            assertFalse(resolved.body().contains("<REDACTED>"), resolved.body());
        }

        @Test
        void aCredentialInAQueryParameterIsRedactedToo() {
            // ?api_key=… is a conventional way to pass a credential, and the query
            // string is shown to the approver exactly like the body is.
            var query = new LinkedHashMap<String, List<String>>();
            query.put("api_key", List.of(KEY));
            var resolved = ResolvedRequest.of("GET", "https://x/y", query, Map.of(), null, true);

            assertFalse(resolved.queryParams().get("api_key").contains(KEY), resolved.queryParams().toString());
            assertEquals(RequestRedactor.REDACTED, resolved.queryParams().get("api_key"));
        }

        @Test
        void twoDifferentQueryCredentialsDoNotShareAFingerprint() {
            // Same reason the body is hashed raw: redacting first would collapse
            // both to one marker and let a swapped key pass the re-check.
            var first = new LinkedHashMap<String, List<String>>();
            first.put("api_key", List.of(KEY));
            var second = new LinkedHashMap<String, List<String>>();
            second.put("api_key", List.of(OTHER_KEY));

            assertNotEquals(ResolvedRequest.of("GET", "https://x/y", first, Map.of(), null, true).fingerprint(),
                    ResolvedRequest.of("GET", "https://x/y", second, Map.of(), null, true).fingerprint());
        }

        @Test
        void aCredentialInTheURIItselfIsRedacted() {
            // The leak this case exists for. A credential templated into an
            // httpcall's path is resolved to its live value BEFORE the URI is
            // built, so it arrives here as plaintext. It was previously redacted
            // in queryParams and shown verbatim in uri — the same secret, two
            // adjacent fields of one JSON object handed to an approver.
            var resolved = ResolvedRequest.of("GET", "https://x/y?api_key=" + KEY, Map.of(), Map.of(), null, true);

            assertFalse(resolved.uri().contains(KEY), resolved.uri());
            assertTrue(resolved.uri().contains("REDACTED"), resolved.uri());
            // The rest of the URI must survive — an approver who cannot see which
            // host and path is being called cannot evaluate the request at all.
            assertTrue(resolved.uri().startsWith("https://x/y?api_key="), resolved.uri());
        }

        @Test
        void aSecretShapedValueAnywhereInTheURIIsRedacted() {
            // Not only the query string: userinfo and path segments carry them too.
            var inUserInfo = ResolvedRequest.of("GET", "https://user:" + KEY + "@x/y", Map.of(), Map.of(), null, true);
            assertFalse(inUserInfo.uri().contains(KEY), inUserInfo.uri());

            var inPath = ResolvedRequest.of("GET", "https://x/keys/" + KEY + "/rotate", Map.of(), Map.of(), null, true);
            assertFalse(inPath.uri().contains(KEY), inPath.uri());
        }

        @Test
        void twoDifferentURICredentialsDoNotShareAFingerprint() {
            // The uri is hashed RAW and stored REDACTED, exactly like the body and
            // query — so swapping one credential for another still moves the hash
            // and is refused by the pre-execution re-check.
            assertNotEquals(ResolvedRequest.of("GET", "https://x/y?api_key=" + KEY, Map.of(), Map.of(), null, true).fingerprint(),
                    ResolvedRequest.of("GET", "https://x/y?api_key=" + OTHER_KEY, Map.of(), Map.of(), null, true).fingerprint());
        }

        @Test
        void anOrdinaryURIIsLeftIntact() {
            // Over-redaction is its own failure mode: the method and path are the
            // first thing an approver reads.
            var resolved = ResolvedRequest.of("PATCH", "https://eddi.example/descriptorstore/descriptors/abc?version=3",
                    Map.of(), Map.of(), null, true);
            assertEquals("https://eddi.example/descriptorstore/descriptors/abc?version=3", resolved.uri());
        }

        @Test
        void anOrdinaryQueryParameterSurvivesUnredacted() {
            var query = new LinkedHashMap<String, List<String>>();
            query.put("version", List.of("3"));
            assertEquals("3", ResolvedRequest.of("GET", "https://x/y", query, Map.of(), null, true).queryParams().get("version"));
        }

        @Test
        void anUnpinnableCallStillGetsARedactedBody() {
            // No fingerprint to protect here, but the preview is still shown to a
            // human — redaction is not conditional on pinning.
            var resolved = ResolvedRequest.of("POST", "https://x/y", Map.of(), Map.of(), "{\"apiKey\":\"" + KEY + "\"}", false);
            assertNull(resolved.fingerprint());
            assertFalse(resolved.body().contains(KEY), resolved.body());
        }
    }

    @Test
    void nullsAreToleratedRatherThanThrowing() {
        // A call with no body, no query and no headers is ordinary, not an error.
        var resolved = ResolvedRequest.of("GET", "https://x/y", null, null, null, true);
        assertTrue(resolved.isPinned());
        assertEquals(Map.of(), resolved.queryParams());
        assertEquals(Map.of(), resolved.headers());
    }
}
