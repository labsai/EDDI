/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L12 of the 2026-10-15 security review: the credential field names a config
 * author reaches for when the field is not literally called "password". The
 * values are zero-entropy on purpose — a repeated letter scores under the
 * entropy heuristic — so it is the NAME rule, and only the name rule, that
 * these exercise.
 */
class SecretScrubberFieldNamesTest {

    private final SecretScrubber scrubber = new SecretScrubber(new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {"pwd", "pass", "passphrase", "pin", "otp", "privateKey", "private_key", "pem", "privateKeyPem", "dbPwd",
            "sshPassphrase", "user-pwd", "signing_private_key"})
    @DisplayName("a zero-entropy value is scrubbed on the strength of the field name alone")
    void scrubbedByName(String field) {
        String json = "{\"" + field + "\": \"aaaa\", \"name\": \"keep\"}";

        String scrubbed = scrubber.scrubJson(json);

        assertFalse(scrubbed.contains("aaaa"), field + " must be scrubbed: " + scrubbed);
        assertTrue(scrubbed.contains("keep"), scrubbed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"spin", "item", "poem", "topic", "compass", "maxTokens"})
    @DisplayName("names that merely end in those letters are left alone — the export round trip must stay lossless")
    void notOverMatched(String field) {
        String json = "{\"" + field + "\": \"aaaa\"}";

        assertTrue(scrubber.scrubJson(json).contains("aaaa"), field + " must not be scrubbed");
    }
}
