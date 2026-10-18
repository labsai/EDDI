/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two PBKDF2 derivations at boot — the audit HMAC key and the vault KEK —
 * run concurrently since {@link AuditKeyring#deriveEarly}. Moving them between
 * threads must not change a single key byte: every signature in the ledger and
 * every DEK in the vault was made with the keys these known answers pin.
 * <p>
 * The expected values were computed outside the JVM, with Python's
 * {@code hashlib.pbkdf2_hmac('sha256', key, salt, 600000, 32)}, so they check
 * the derivation against an independent implementation and not against itself.
 */
@DisplayName("Startup key derivation")
class StartupKeyDerivationTest {

    private static final String MASTER = "master-key-one-1234567890";
    private static final String CONFIGURED = "master-key-two-0987654321";

    /** PBKDF2-SHA256(MASTER, "eddi-audit-hmac-v1", 600,000, 32 bytes). */
    private static final String AUDIT_DERIVED_FROM_MASTER = "47c7aaeb3ca9ec55f44ad5fbdcc87f621ae4bccf6bd3846353f8b4856ab35723";
    /** PBKDF2-SHA256(CONFIGURED, "eddi-audit-hmac-v1", 600,000, 32 bytes). */
    private static final String AUDIT_DERIVED_FROM_CONFIGURED = "a967995bdd4ca987b24749f4d59b46fa8450c7b0443605d2084b67c0fe934e0b";
    /** PBKDF2-SHA256(MASTER, bytes 0x00..0x0f, 600,000, 32 bytes). */
    private static final String VAULT_KEK_DERIVED = "6fd4b90df734b39b03d5bb458f70e1f5dbe32decc6a1aa3702815df3bfa9d5ae";

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }

    @Test
    @DisplayName("the audit HMAC key derivation matches its known answer")
    void auditKeyKnownAnswer() {
        assertArrayEquals(hex(AUDIT_DERIVED_FROM_MASTER), AuditHmac.deriveHmacKey(MASTER));
    }

    @Test
    @DisplayName("the vault KEK derivation matches its known answer")
    void vaultKekKnownAnswer() {
        byte[] salt = new byte[16];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) i;
        }
        assertArrayEquals(hex(VAULT_KEK_DERIVED), EnvelopeCrypto.deriveKeyFromString(MASTER, salt));
    }

    @Test
    @DisplayName("keys derived early on a virtual thread are the keys derived on the caller's thread")
    void earlyDerivationYieldsTheSameKeys() {
        var early = new AuditKeyring(Optional.of(MASTER), Optional.of(CONFIGURED), Optional.empty(), null);
        early.deriveEarly(new StartupEvent());

        // What onStartup and every key accessor do: wait for the derivation to finish.
        var signing = early.signingKey();
        assertArrayEquals(hex(AUDIT_DERIVED_FROM_CONFIGURED), signing.hmacKey());
        assertEquals(AuditHmac.signingKey(hex(AUDIT_DERIVED_FROM_CONFIGURED)).id(), signing.id());

        var direct = new AuditKeyring(Optional.of(MASTER), Optional.of(CONFIGURED), Optional.empty(), null);
        direct.initialize();
        assertEquals(direct.verificationKeys().stream().map(AuditHmac.SigningKey::id).toList(),
                early.verificationKeys().stream().map(AuditHmac.SigningKey::id).toList());
        assertTrue(early.verificationKeys().stream().anyMatch(key -> Arrays.equals(hex(AUDIT_DERIVED_FROM_MASTER), key.hmacKey())),
                "the master-derived key still verifies");
    }

    @Test
    @DisplayName("the early derivation does not run on the startup thread")
    void earlyDerivationDoesNotBlockTheCaller() {
        // Four derivations of 600,000 iterations each: long enough that running them
        // inline would be unmistakable next to starting a thread.
        List<String> previous = List.of("retired-key-a-12345678", "retired-key-b-12345678");
        var reference = new AuditKeyring(Optional.of(MASTER), Optional.of(CONFIGURED), Optional.of(previous), null);
        long inlineStart = System.nanoTime();
        reference.initialize();
        long inlineNanos = System.nanoTime() - inlineStart;

        var early = new AuditKeyring(Optional.of(MASTER), Optional.of(CONFIGURED), Optional.of(previous), null);
        long start = System.nanoTime();
        early.deriveEarly(new StartupEvent());
        long callerNanos = System.nanoTime() - start;

        assertTrue(callerNanos < inlineNanos / 4,
                "deriveEarly held the caller for " + callerNanos / 1_000_000 + " ms; inline derivation takes " + inlineNanos / 1_000_000 + " ms");
        assertEquals(reference.signingKey().id(), early.signingKey().id());
        var expected = reference.verificationKeys();
        var actual = early.verificationKeys();
        assertEquals(4, actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).id(), actual.get(i).id());
            assertArrayEquals(expected.get(i).hmacKey(), actual.get(i).hmacKey(), "hmac key of " + expected.get(i).id());
            assertArrayEquals(expected.get(i).pseudonymKey(), actual.get(i).pseudonymKey(), "pseudonym key of " + expected.get(i).id());
        }
    }
}
