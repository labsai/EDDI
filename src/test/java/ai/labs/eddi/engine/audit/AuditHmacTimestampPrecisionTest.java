/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.AuditHmac.VerificationOutcome;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A signature has to survive the trip through storage.
 * <p>
 * The v3 canonical form signed the raw {@link Instant}, which on a Linux
 * container carries nanoseconds — but PostgreSQL's {@code TIMESTAMPTZ} keeps
 * microseconds and MongoDB's {@code Date} keeps milliseconds. The entry read
 * back was therefore never the entry that was signed, and verification failed
 * for effectively every row on both supported backends: a live ledger reported
 * {@code valid=0 invalid=78} on entries written seconds earlier. Nothing was
 * tampered with; the control simply had no signal in it, so an operator could
 * not tell a forged row from a healthy one.
 * <p>
 * These tests do what the ledger's own tests never did: sign an entry, apply
 * each backend's truncation, and verify what comes back.
 */
@DisplayName("audit HMAC survives each backend's timestamp precision")
class AuditHmacTimestampPrecisionTest {

    private static byte[] hmacKey;

    @BeforeAll
    static void deriveKey() {
        hmacKey = AuditHmac.deriveHmacKey("test-master-key");
    }

    /** An instant with digits below every storage floor: 123456789 nanos. */
    private static final Instant NANO_PRECISE = Instant.parse("2026-08-20T10:15:30Z").plusNanos(123_456_789L);

    private static AuditEntry entryAt(Instant timestamp) {
        return new AuditEntry("id-1", "conv-1", "agent-1", 1, "alice", "prod", 0, "ai.labs.llm", "langchain", 2, 780L,
                Map.of("input", "hello"), Map.of("output", "ALPHA."), null, null, List.of("greet"), 0.0, timestamp, null, null, 7L);
    }

    /** What PostgreSQL's {@code TIMESTAMPTZ} (precision 6) hands back. */
    private static AuditEntry asStoredByPostgres(AuditEntry entry) {
        return entry.withTimestamp(entry.timestamp().truncatedTo(ChronoUnit.MICROS));
    }

    /** What MongoDB's {@code Date} hands back. */
    private static AuditEntry asStoredByMongo(AuditEntry entry) {
        return entry.withTimestamp(entry.timestamp().truncatedTo(ChronoUnit.MILLIS));
    }

    @Nested
    @DisplayName("v4, the form written today")
    class V4 {

        @Test
        @DisplayName("verifies after a PostgreSQL round-trip")
        void survivesMicrosecondTruncation() {
            AuditEntry signed = entryAt(NANO_PRECISE).withHmac(AuditHmac.computeHmac(entryAt(NANO_PRECISE), hmacKey));

            assertTrue(AuditHmac.verifyHmac(asStoredByPostgres(signed), hmacKey),
                    "a row read back from TIMESTAMPTZ must still verify");
        }

        @Test
        @DisplayName("verifies after a MongoDB round-trip")
        void survivesMillisecondTruncation() {
            AuditEntry signed = entryAt(NANO_PRECISE).withHmac(AuditHmac.computeHmac(entryAt(NANO_PRECISE), hmacKey));

            assertTrue(AuditHmac.verifyHmac(asStoredByMongo(signed), hmacKey),
                    "a row read back from a BSON Date must still verify");
        }

        @Test
        @DisplayName("still rejects a timestamp moved by a whole millisecond")
        void rejectsRealTimestampTampering() {
            AuditEntry signed = entryAt(NANO_PRECISE).withHmac(AuditHmac.computeHmac(entryAt(NANO_PRECISE), hmacKey));
            AuditEntry moved = signed.withTimestamp(signed.timestamp().plusMillis(1));

            assertEquals(VerificationOutcome.MISMATCH, AuditHmac.verify(moved, hmacKey, true),
                    "tolerating sub-millisecond loss must not tolerate an actual edit");
        }

        @Test
        @DisplayName("signs the epoch value, so toString's trailing-zero variance cannot matter")
        void signsEpochNotText() {
            // "…:30Z" and "…:30.000Z" are the same instant but different text.
            Instant whole = Instant.parse("2026-08-20T10:15:30Z");
            Instant sameWithExplicitMillis = Instant.ofEpochMilli(whole.toEpochMilli());

            assertEquals(AuditHmac.buildCanonicalStringV4(entryAt(whole)),
                    AuditHmac.buildCanonicalStringV4(entryAt(sameWithExplicitMillis)));
        }
    }

    @Nested
    @DisplayName("v3 rows already in the ledger")
    class V3Recovery {

        private static String signV3(AuditEntry entry) {
            return "v3:" + AuditHmacTestSupport.hmacHex(AuditHmac.buildCanonicalStringV3(entry), hmacKey);
        }

        @Test
        @DisplayName("a PostgreSQL-truncated row cannot verify as stored")
        void demonstratesTheDefect() {
            AuditEntry original = entryAt(NANO_PRECISE);
            AuditEntry stored = asStoredByPostgres(original).withHmac(signV3(original));

            assertNotEquals(original.timestamp(), stored.timestamp(),
                    "precondition: storage really did drop digits");
            assertEquals(VerificationOutcome.MISMATCH, AuditHmac.verify(stored, hmacKey, false),
                    "this is what made every entry report INVALID");
        }

        @Test
        @DisplayName("recovery reconstructs the nanoseconds PostgreSQL dropped")
        void recoversMicrosecondFlooredRow() {
            AuditEntry original = entryAt(NANO_PRECISE);
            AuditEntry stored = asStoredByPostgres(original).withHmac(signV3(original));

            assertEquals(VerificationOutcome.MATCH_RECOVERED, AuditHmac.verify(stored, hmacKey, true),
                    "the missing digits are recoverable from the signature itself");
        }

        @Test
        @DisplayName("recovery reconstructs the microseconds MongoDB dropped")
        void recoversMillisecondFlooredRow() {
            // A microsecond-resolution clock, which is what a JVM Date-backed row has.
            AuditEntry original = entryAt(Instant.parse("2026-08-20T10:15:30Z").plusNanos(123_456_000L));
            AuditEntry stored = asStoredByMongo(original).withHmac(signV3(original));

            assertEquals(VerificationOutcome.MATCH_RECOVERED, AuditHmac.verify(stored, hmacKey, true));
        }

        @Test
        @DisplayName("a v3 row whose clock landed on a whole millisecond verifies with no search")
        void exactRowNeedsNoRecovery() {
            AuditEntry original = entryAt(Instant.parse("2026-08-20T10:15:30Z"));
            AuditEntry stored = asStoredByPostgres(original).withHmac(signV3(original));

            assertEquals(VerificationOutcome.MATCH, AuditHmac.verify(stored, hmacKey, true));
        }

        /**
         * The point of recovery is that it proves integrity rather than assuming it.
         * Re-signing legacy rows in place would have laundered exactly this case.
         */
        @Test
        @DisplayName("recovery does not rescue a genuinely tampered row")
        void tamperedRowStaysInvalid() {
            AuditEntry original = entryAt(NANO_PRECISE);
            AuditEntry stored = asStoredByPostgres(original).withHmac(signV3(original));
            AuditEntry tampered = stored.withEnvironment("TAMPERED");

            assertEquals(VerificationOutcome.MISMATCH, AuditHmac.verify(tampered, hmacKey, true));
        }

        @Test
        @DisplayName("recovery can be switched off")
        void recoveryIsOptional() {
            AuditEntry original = entryAt(NANO_PRECISE);
            AuditEntry stored = asStoredByPostgres(original).withHmac(signV3(original));

            assertEquals(VerificationOutcome.MISMATCH, AuditHmac.verify(stored, hmacKey, false));
        }
    }
}
