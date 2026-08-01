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
}
