/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecretScrubberTest {

    private SecretScrubber scrubber;

    @BeforeEach
    void setUp() {
        scrubber = new SecretScrubber(new ObjectMapper());
    }

    @Test
    void scrubJson_knownSecretFieldNames() throws Exception {
        String json = """
                {
                    "apiKey": "sk-abc123secretValue",
                    "name": "My Config",
                    "password": "hunter2"
                }
                """;

        String scrubbed = scrubber.scrubJson(json);

        assertFalse(scrubbed.contains("sk-abc123secretValue"));
        assertFalse(scrubbed.contains("hunter2"));
        assertTrue(scrubbed.contains("My Config"));
    }

    @Test
    void scrubJson_vaultReferences_passthrough() throws Exception {
        String json = """
                {
                    "apiKey": "${vault:default/agent1/openaiKey}",
                    "name": "My Config"
                }
                """;

        String scrubbed = scrubber.scrubJson(json);

        // Vault references should be preserved (they're already safe)
        assertTrue(scrubbed.contains("${vault:default/agent1/openaiKey}"));
        assertTrue(scrubbed.contains("My Config"));
    }

    @Test
    void scrubJson_safeFields_untouched() throws Exception {
        String json = """
                {
                    "name": "My Agent",
                    "language": "en",
                    "greeting": "Hello, how can I help?"
                }
                """;

        String scrubbed = scrubber.scrubJson(json);

        assertTrue(scrubbed.contains("My Agent"));
        assertTrue(scrubbed.contains("en"));
        assertTrue(scrubbed.contains("Hello, how can I help?"));
    }

    @Test
    void scrubJson_nullAndEmpty() throws Exception {
        assertEquals("{}", scrubber.scrubJson("{}"));
        assertEquals("", scrubber.scrubJson(""));
    }

    // =========================================================================
    // Null and edge cases
    // =========================================================================

    @Test
    void scrubJson_null_returnsNull() throws Exception {
        assertNull(scrubber.scrubJson(null));
    }

    @Test
    void scrubJson_blankString_returnsAsIs() {
        assertEquals("   ", scrubber.scrubJson("   "));
    }

    @Test
    void scrubJson_invalidJson_returnsOriginal() {
        String invalid = "not valid json {{{";
        assertEquals(invalid, scrubber.scrubJson(invalid));
    }

    @Test
    void scrubJson_arrayWithNestedObjects() {
        String json = "[{\"apiKey\": \"secret123\"}]";
        String scrubbed = scrubber.scrubJson(json);
        assertFalse(scrubbed.contains("secret123"));
        assertTrue(scrubbed.contains("${vault:REDACTED}"));
    }

    @Test
    void scrubJson_nestedObjects() {
        String json = "{\"outer\": {\"password\": \"deepSecret\"}}";
        String scrubbed = scrubber.scrubJson(json);
        assertFalse(scrubbed.contains("deepSecret"));
        assertTrue(scrubbed.contains("${vault:REDACTED}"));
    }

    @Test
    void scrubJson_highEntropy_nonSecretFieldName_scrubbed() {
        String highEntropyValue = "sk-aB3cD4eF5gH6iJ7kL8mN9oP0qR";
        String json = String.format("{\"someConfig\": \"%s\"}", highEntropyValue);
        String scrubbed = scrubber.scrubJson(json);
        assertFalse(scrubbed.contains(highEntropyValue));
    }

    @Test
    void scrubJson_lowEntropy_longString_notScrubbed() {
        String lowEntropy = "aaaaaaaaaaaaaaaaaaaa";
        String json = String.format("{\"description\": \"%s\"}", lowEntropy);
        String scrubbed = scrubber.scrubJson(json);
        assertTrue(scrubbed.contains(lowEntropy));
    }

    @Test
    void scrubJson_shortString_notEntropyChecked() {
        String shortValue = "xY3zW9";
        String json = String.format("{\"config\": \"%s\"}", shortValue);
        String scrubbed = scrubber.scrubJson(json);
        assertTrue(scrubbed.contains(shortValue));
    }

    @Test
    void scrubJson_allSecretFieldNames() {
        var secretFields = List.of(
                "token", "auth", "secretkey", "secret_key", "apitoken", "api_token",
                "passwd", "access_token", "authorization", "credential", "credentials",
                "privatekey", "private_key", "clientsecret", "client_secret");

        for (String field : secretFields) {
            String json = String.format("{\"%s\": \"somevalue\"}", field);
            String scrubbed = scrubber.scrubJson(json);
            assertFalse(scrubbed.contains("somevalue"),
                    "Field '" + field + "' should be scrubbed");
        }
    }

    @Test
    void scrubJson_fieldNamesWithDashesAndDots() {
        String json = "{\"api-key\": \"secret1\", \"api.key\": \"secret2\"}";
        String scrubbed = scrubber.scrubJson(json);
        assertFalse(scrubbed.contains("secret1"));
        assertFalse(scrubbed.contains("secret2"));
    }

    @Test
    void scrubJson_eddiVaultReference_preserved() {
        String json = "{\"apiKey\": \"${eddivault:default/key}\"}";
        String scrubbed = scrubber.scrubJson(json);
        assertTrue(scrubbed.contains("${eddivault:default/key}"));
    }

    @Test
    void shannonEntropy_null_returnsZero() {
        assertEquals(0.0, SecretScrubber.shannonEntropy(null));
    }

    @Test
    void shannonEntropy_empty_returnsZero() {
        assertEquals(0.0, SecretScrubber.shannonEntropy(""));
    }

    @Test
    void shannonEntropy_singleChar_returnsZero() {
        assertEquals(0.0, SecretScrubber.shannonEntropy("a"));
    }

    @Test
    void shannonEntropy_highEntropyString_returnsHighValue() {
        double entropy = SecretScrubber.shannonEntropy("aB3cD4eF5gH6iJ7kL8m");
        assertTrue(entropy > 3.5, "Expected high entropy but got: " + entropy);
    }

    // ==================== structural fields must survive export
    // ====================

    /**
     * The entropy heuristic cannot tell a long identifier from a long key, and this
     * scrubber runs on the export path — so before the structural-field exemption
     * it rewrote ordinary configuration values to {@code ${vault:REDACTED}} and
     * produced ZIPs EDDI could not import again.
     * <p>
     * Every value below is taken verbatim from the exported weather-agent fixture
     * and scores over the 3.5 bits/char threshold. The condition {@code type} is
     * the one that failed loudly — {@code RuleDeserialization} could not resolve a
     * class for {@code ${vault:REDACTED}} and {@code ImportMergeIT} got a 400. The
     * other three failed silently: the rule, property name and memory path were
     * simply gone.
     */
    @Test
    @DisplayName("structural identifiers survive scrubbing, however random they look")
    void scrubJson_structuralFields_notScrubbedByEntropy() {
        String json = """
                {
                  "type": "dynamicvaluematcher",
                  "name": "currentWeatherDescription",
                  "fromObjectPath": "memory.current.httpCalls.currentWeatherDescription",
                  "toObjectPath": "properties.count+1"
                }
                """;

        String scrubbed = scrubber.scrubJson(json);

        assertFalse(scrubbed.contains("REDACTED"),
                "export must not rewrite schema-fixed identifiers; a redacted condition type makes the "
                        + "exported agent unimportable: " + scrubbed);
        assertTrue(scrubbed.contains("dynamicvaluematcher"));
        assertTrue(scrubbed.contains("currentWeatherDescription"));
        assertTrue(scrubbed.contains("memory.current.httpCalls.currentWeatherDescription"));
        assertTrue(scrubbed.contains("properties.count+1"));
    }

    /**
     * The exemption is by field name only. A credential is still redacted when it
     * sits in a field that names one, so the security control is intact.
     */
    @Test
    @DisplayName("a secret in a secret-named field is still redacted")
    void scrubJson_structuralExemption_doesNotWeakenSecretFieldNames() {
        // Deliberately LOW entropy, so only the field-name check (check 1) can redact
        // it. That isolates the property under test — the structural exemption must
        // not weaken field-name detection — instead of letting the entropy heuristic
        // pass the test for the wrong reason. It also keeps a key-shaped literal out
        // of the source tree, which the repository's secret scanner flags on sight.
        String credential = "aaaaaaaaaaaaaaaaaaaa";
        String json = String.format("{\"type\": \"httpcall\", \"apiKey\": \"%s\", \"someConfig\": \"%s\"}", credential, credential);

        String scrubbed = scrubber.scrubJson(json);

        assertTrue(scrubbed.contains("\"apiKey\":\"${vault:REDACTED}\""),
                "a credential in a secret-named field must still be redacted: " + scrubbed);
        assertTrue(scrubbed.contains("\"type\":\"httpcall\""),
                "the structural discriminator must survive: " + scrubbed);
        assertTrue(scrubbed.contains("\"someConfig\":\"" + credential + "\""),
                "redaction must be driven by the field name, not applied blanket: " + scrubbed);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The three documented export holes. Each is asserted separately: they have
    // separate causes, and one fix passing does not imply the others.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("hole 1 — a credential inside a JSON array is scrubbed")
    void scrubJson_secretInsideAnArray() {
        // Arrays used to be walked with the PARENT's field name into a branch that
        // handles only objects and arrays, so no string element was ever examined.
        String credential = "aaaaaaaaaaaaaaaaaaaa";
        String json = String.format("{\"apiKeys\": [\"%s\"], \"actions\": [\"greet\"]}", credential);

        String scrubbed = scrubber.scrubJson(json);

        assertFalse(scrubbed.contains(credential), "a secret inside an array must not survive export: " + scrubbed);
        assertTrue(scrubbed.contains("greet"), "a structural array must stay intact: " + scrubbed);
    }

    @Test
    @DisplayName("hole 1b — a nested object inside an array is still walked")
    void scrubJson_objectInsideAnArrayStillWalked() {
        String json = "{\"servers\": [{\"name\": \"jira\", \"apiKey\": \"aaaaaaaaaaaaaaaaaaaa\"}]}";

        String scrubbed = scrubber.scrubJson(json);

        assertFalse(scrubbed.contains("aaaaaaaaaaaaaaaaaaaa"));
        assertTrue(scrubbed.contains("jira"));
    }

    @Test
    @DisplayName("hole 2 — an unconventionally named header is scrubbed")
    void scrubJson_unconventionalHeaderName() {
        // "X-Api-Token" normalizes to xapitoken, which is in no name set, and
        // "Bearer <value>" contains a space so the whole-string entropy pattern
        // never matched it either.
        String json = "{\"headers\": {\"X-Api-Token\": \"Bearer aaaaaaaaaaaaaaaaaaaa\", \"Accept\": \"application/json\"}}";

        String scrubbed = scrubber.scrubJson(json);

        assertFalse(scrubbed.contains("aaaaaaaaaaaaaaaaaaaa"), "an Authorization-equivalent header must be redacted: " + scrubbed);
        assertTrue(scrubbed.contains("application/json"), "a benign header must survive: " + scrubbed);
    }

    @Test
    @DisplayName("hole 2b — the header rule does not leak into ordinary config fields")
    void scrubJson_headerRuleIsScopedToHeaderMaps() {
        // endsWith("key") is aggressive on purpose and is therefore confined to
        // header maps. Outside one it must not fire, or export stops round-tripping.
        // Deliberately zero-entropy, so the pre-existing entropy heuristic cannot
        // decide the outcome and the header-scoping rule is what is under test.
        String json = "{\"config\": {\"publicKey\": \"aaaaaaaaaaaaaaaaaaaa\"}}";

        String scrubbed = scrubber.scrubJson(json);

        assertTrue(scrubbed.contains("aaaaaaaaaaaaaaaaaaaa"), "a structural identifier outside a header map must survive: " + scrubbed);
    }

    @Test
    @DisplayName("hole 3 — credentials embedded in a URL are scrubbed, the host is not")
    void scrubJson_credentialsInsideAUrl() {
        String json = "{\"targetServerUrl\": \"https://user:aaaaaaaaaaaaaaaaaaaa@api.example.com/v1\","
                + " \"specUrl\": \"https://api.example.com/v1?api_key=aaaaaaaaaaaaaaaaaaaa\"}";

        String scrubbed = scrubber.scrubJson(json);

        assertFalse(scrubbed.contains("aaaaaaaaaaaaaaaaaaaa"), "neither userinfo nor a query credential may survive: " + scrubbed);
        assertTrue(scrubbed.contains("api.example.com"), "the target host must stay legible — a config whose host is a placeholder is "
                + "neither reviewable nor importable: " + scrubbed);
    }

    @Test
    @DisplayName("a clean URL is left exactly as it was")
    void scrubJson_cleanUrlUntouched() {
        String json = "{\"targetServerUrl\": \"https://api.example.com/v1/pets\"}";

        assertTrue(scrubber.scrubJson(json).contains("https://api.example.com/v1/pets"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A measured quantity is not a credential. The suffix rule read "maxTokens"
    // as "…token" and replaced the model's output limit with a vault
    // placeholder on EVERY export — parameters are stored as
    // Map<String, String>, so the value is a JSON string and the rule really
    // fired.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("token-BUDGET parameters survive an export round trip unchanged")
    void scrubJson_measuredQuantities_notScrubbed() {
        // Every token-shaped key a model builder actually reads: eight builders
        // take maxTokens, Gemini maxOutputTokens, HuggingFace maxNewTokens.
        // budgetTokens and maxCompletionTokens are the same field again wherever
        // a config carries them.
        String json = """
                {"parameters": {
                    "maxTokens": "4096",
                    "maxOutputTokens": "8192",
                    "maxNewTokens": "512",
                    "budgetTokens": "1024",
                    "maxCompletionTokens": "256",
                    "temperature": "0.7"
                }}
                """;

        String scrubbed = scrubber.scrubJson(json);

        assertFalse(scrubbed.contains("REDACTED"),
                "a count of tokens is not a token; redacting it corrupts every exported agent: " + scrubbed);
        assertTrue(scrubbed.contains("\"maxTokens\":\"4096\""), scrubbed);
        assertTrue(scrubbed.contains("\"maxOutputTokens\":\"8192\""), scrubbed);
        assertTrue(scrubbed.contains("\"maxNewTokens\":\"512\""), scrubbed);
        assertTrue(scrubbed.contains("\"budgetTokens\":\"1024\""), scrubbed);
        assertTrue(scrubbed.contains("\"maxCompletionTokens\":\"256\""), scrubbed);
    }

    @Test
    @DisplayName("a genuine credential name is still redacted, quantity carve-out notwithstanding")
    void scrubJson_credentialNames_stillScrubbedBesideQuantities() {
        // Deliberately zero-entropy, so ONLY the field-name check can redact it.
        // That isolates the property under test — the quantity carve-out must not
        // punch a hole in credential detection — instead of letting the entropy
        // heuristic pass the test for the wrong reason. None of these names begins
        // with a quantity qualifier, which is exactly why the carve-out is a
        // prefix rule rather than a word list.
        String credential = "aaaaaaaaaaaaaaaaaaaa";

        for (String field : List.of("apiToken", "accessToken", "refreshToken", "authToken", "api_key", "password", "clientSecret")) {
            String json = String.format("{\"parameters\": {\"%s\": \"%s\", \"maxTokens\": \"4096\"}}", field, credential);

            String scrubbed = scrubber.scrubJson(json);

            assertTrue(scrubbed.contains("\"" + field + "\":\"${vault:REDACTED}\""),
                    "'" + field + "' names a credential and must still be redacted: " + scrubbed);
            assertTrue(scrubbed.contains("\"maxTokens\":\"4096\""),
                    "and the quantity beside it must still survive: " + scrubbed);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The export path's adoption of the shared header rule (UriRedactorTest owns
    // the rule itself).
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a header named Authentication is redacted on export; 'auth' substrings are not")
    void scrubJson_authenticationHeaderRedacted_benignAuthNamesSurvive() {
        // "Bearer <token>" contains a space, which the whole-string entropy
        // pattern never matches — so the name rule is the only thing that can
        // catch these three, and the assertions below cannot pass for another
        // reason.
        String json = """
                {"headers": {
                    "Authentication": "Bearer aaaaaaaaaaaaaaaaaaaa",
                    "Authorization": "Bearer aaaaaaaaaaaaaaaaaaaa",
                    "Proxy-Authorization": "Basic aaaaaaaaaaaaaaaaaaaa",
                    "Author": "jane-the-author-xx",
                    "X-Authored-By": "jane-xx",
                    "Authority": "eu-west-1"
                }}
                """;

        String scrubbed = scrubber.scrubJson(json);

        assertTrue(scrubbed.contains("\"Authentication\":\"${vault:REDACTED}\""),
                "an Authorization-equivalent header must not export in plaintext: " + scrubbed);
        assertTrue(scrubbed.contains("\"Authorization\":\"${vault:REDACTED}\""), scrubbed);
        assertTrue(scrubbed.contains("\"Proxy-Authorization\":\"${vault:REDACTED}\""), scrubbed);
        assertTrue(scrubbed.contains("\"Author\":\"jane-the-author-xx\""),
                "a header whose name merely contains 'auth' must stay readable: " + scrubbed);
        assertTrue(scrubbed.contains("\"X-Authored-By\":\"jane-xx\""), scrubbed);
        assertTrue(scrubbed.contains("\"Authority\":\"eu-west-1\""), scrubbed);
    }

    @Test
    @DisplayName("an 'auth'-containing query parameter is not mistaken for a credential either")
    void scrubJson_benignAuthQueryParams_survive() {
        String json = "{\"targetServerUrl\": \"https://api.example.com/posts?author=jane-doe&authority=eu-west-1"
                + "&access_token=abcdefghijklmnop\"}";

        String scrubbed = scrubber.scrubJson(json);

        assertTrue(scrubbed.contains("author=jane-doe"), "a benign query parameter must survive export: " + scrubbed);
        assertTrue(scrubbed.contains("authority=eu-west-1"), scrubbed);
        assertFalse(scrubbed.contains("abcdefghijklmnop"),
                "while the credential beside them is still redacted: " + scrubbed);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // What export writes into a URL field has to import again.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an exported URL keeps its query structure when a credential is removed")
    void scrubJson_redactedUrlKeepsItsQueryStructure() {
        // %26 is an encoded ampersand. Writing the decoded form back turned it
        // into a structural '&' that splits one parameter into two, and %20 into
        // a raw space that is not a URI character — in a config that has to be
        // importable.
        String json = "{\"targetServerUrl\": \"https://api.example.com/v1/report?access_token=abcdefghijklmnop"
                + "&filter=a%26b%20c&mode=fast\"}";

        String scrubbed = scrubber.scrubJson(json);

        assertFalse(scrubbed.contains("abcdefghijklmnop"), "the credential must not survive: " + scrubbed);
        assertTrue(scrubbed.contains("access_token=${vault:REDACTED}"),
                "and must be replaced by the placeholder an operator fills in on import: " + scrubbed);
        assertTrue(scrubbed.contains("&filter=a%26b%20c&mode=fast"),
                "the benign parameters must keep the encoding they were written with: " + scrubbed);
    }

    @Test
    @DisplayName("a Qute placeholder in an exported URL is left legible, not blanked")
    void scrubJson_templatePlaceholderInUrl_preserved() {
        // ?api_key={properties.apiKey} is the documented wizard pattern. Judged by
        // name alone it is redacted, and that loses which property the call reads.
        String json = "{\"targetServerUrl\": \"https://api.example.com/v1?api_key={properties.apiKey}&lang=en\"}";

        String scrubbed = scrubber.scrubJson(json);

        assertTrue(scrubbed.contains("api_key={properties.apiKey}"),
                "a placeholder is a pointer, not a secret, and an export that blanks it is not importable: " + scrubbed);
        assertTrue(scrubbed.contains("&lang=en"), scrubbed);
    }

    @Test
    @DisplayName("a vault reference in one query parameter does not exempt a live credential in the next")
    void scrubJson_urlMixingVaultReferenceAndPlaintext() {
        // The vault-reference exemption is about ONE value. A URL is several:
        // reading it over the whole string let a reference in the first parameter
        // speak for the plaintext credential in the second, and the credential was
        // exported verbatim.
        String json = "{\"targetServerUrl\": \"https://api.example.com/v1?api_key=${vault:prodKey}"
                + "&access_token=abcdefghijklmnop&lang=en\"}";

        String scrubbed = scrubber.scrubJson(json);

        assertFalse(scrubbed.contains("abcdefghijklmnop"),
                "the live credential beside the reference must not survive export: " + scrubbed);
        assertTrue(scrubbed.contains("&lang=en"), "the benign parameter still survives: " + scrubbed);
    }
}
