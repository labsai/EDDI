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
import static org.junit.jupiter.api.Assertions.assertNull;
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

        /**
         * Signing milliseconds is not enough on its own: {@code timestamp(6)} ROUNDS to
         * the nearest microsecond, so an instant in the last half-microsecond of a
         * millisecond rounds up across the boundary and reads back a millisecond late.
         * Storing what was signed leaves nothing to round.
         */
        @Test
        @DisplayName("the stored timestamp is floored first, so PostgreSQL rounding cannot move it")
        void storedPrecisionDefeatsDatabaseRounding() {
            Instant lastHalfMicrosecond = Instant.parse("2026-08-20T10:15:30Z").plusNanos(123_999_600L);
            AuditEntry storable = AuditHmac.withStorablePrecision(entryAt(lastHalfMicrosecond));

            assertEquals(0, storable.timestamp().getNano() % 1_000_000,
                    "a millisecond-floored instant has no sub-millisecond digits for a database to round");

            AuditEntry signed = storable.withHmac(AuditHmac.computeHmac(storable, hmacKey));
            // PostgreSQL rounding this value is a no-op, because there is nothing below
            // the millisecond left to round.
            assertTrue(AuditHmac.verifyHmac(asStoredByPostgres(signed), hmacKey));
            assertTrue(AuditHmac.verifyHmac(asStoredByMongo(signed), hmacKey));
        }

        @Test
        @DisplayName("withStorablePrecision tolerates a null entry and a null timestamp")
        void storablePrecisionIsNullTolerant() {
            assertNull(AuditHmac.withStorablePrecision(null));
            AuditEntry noTimestamp = entryAt(null);
            assertEquals(noTimestamp, AuditHmac.withStorablePrecision(noTimestamp));
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
    @DisplayName("versionOf — the triage field")
    class VersionOf {

        @Test
        @DisplayName("names each canonical form, bare digests as v1, unsigned as null")
        void namesEveryForm() {
            assertEquals("v4", AuditHmac.versionOf("v4:" + "0".repeat(64)));
            assertEquals("v3", AuditHmac.versionOf("v3:" + "0".repeat(64)));
            assertEquals("v2", AuditHmac.versionOf("v2:" + "0".repeat(64)));
            assertEquals("v1", AuditHmac.versionOf("0".repeat(64)));
            assertNull(AuditHmac.versionOf(null));
            assertNull(AuditHmac.versionOf(""));
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

        /**
         * Storage does not only floor. PostgreSQL's {@code timestamp(6)} — and the JDBC
         * driver's nanos-to-micros conversion — round to NEAREST, so a value whose lost
         * digits were in the upper half is stored ABOVE the signed one. A forward-only
         * search missed every such row: roughly half of all PostgreSQL legacy rows.
         */
        @Test
        @DisplayName("recovery also finds a timestamp PostgreSQL rounded UP")
        void recoversRoundedUpRow() {
            // 789ns remainder ≥ 500 → rounds up to the next microsecond: the stored
            // value sits 211ns ABOVE the signed one.
            AuditEntry original = entryAt(NANO_PRECISE);
            Instant roundedUp = original.timestamp().truncatedTo(ChronoUnit.MICROS).plusNanos(1_000);
            AuditEntry stored = original.withTimestamp(roundedUp).withHmac(signV3(original));

            assertTrue(roundedUp.isAfter(original.timestamp()), "precondition: rounding moved the value up");
            assertEquals(VerificationOutcome.MATCH_RECOVERED, AuditHmac.verify(stored, hmacKey, true),
                    "a rounded-up stored value sits above the signed one, so the search must look down too");
        }

        /**
         * The searched window is, unavoidably, also the window within which a MOVED
         * stored timestamp is indistinguishable from a truncated one — so it must be
         * capped to exactly the precision the row demonstrably lost. A row with
         * sub-millisecond digits present came through a microsecond store: only
         * sub-microsecond digits are unknowable, and a whole-microsecond shift is a
         * real edit that recovery must NOT absorb.
         */
        @Test
        @DisplayName("a microsecond-precision row shifted by whole microseconds is NOT recovered")
        void microsecondShiftOnMicrosecondRowStaysInvalid() {
            // µs-aligned signed value, so a whole-µs shift lands exactly on a
            // candidate the µs tier WOULD try — the only thing standing between this
            // edit and MATCH_RECOVERED is the precision cap.
            AuditEntry original = entryAt(Instant.parse("2026-08-20T10:15:30Z").plusNanos(123_456_000L));
            AuditEntry shifted = original.withTimestamp(original.timestamp().plusNanos(5_000))
                    .withHmac(signV3(original));

            assertEquals(VerificationOutcome.MISMATCH, AuditHmac.verify(shifted, hmacKey, true),
                    "the ±µs tier must not run for a row whose sub-ms digits prove a µs-precision store");
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

        /**
         * The per-row search is bounded; the per-sweep work was not. A page of ten
         * thousand rows that cannot be recovered — which is what a ledger verified with
         * the wrong key looks like — would have spent about twenty million HMACs on one
         * request thread before answering.
         */
        @Test
        @DisplayName("a sweep stops searching once its budget is spent")
        void budgetBoundsTheSweepNotJustTheRow() {
            AuditEntry original = entryAt(NANO_PRECISE);
            AuditEntry stored = asStoredByPostgres(original).withHmac(signV3(original));

            var budget = new AuditRecoveryBudget(1);

            assertEquals(VerificationOutcome.MATCH_RECOVERED, AuditHmac.verify(stored, hmacKey, budget),
                    "the first row is within budget");
            assertEquals(VerificationOutcome.MISMATCH, AuditHmac.verify(stored, hmacKey, budget),
                    "the second is not searched, so it reports as it stands");
            assertEquals(1, budget.searchesSkipped(),
                    "an unsearched row must be counted, or the sweep looks more thorough than it was");
        }

        @Test
        @DisplayName("a row that verifies directly spends none of the budget")
        void healthyRowsDoNotConsumeBudget() {
            AuditEntry original = entryAt(Instant.parse("2026-08-20T10:15:30Z"));
            AuditEntry stored = asStoredByPostgres(original).withHmac(signV3(original));

            var budget = new AuditRecoveryBudget(1);
            assertEquals(VerificationOutcome.MATCH, AuditHmac.verify(stored, hmacKey, budget));

            // The one search is still available for a row that actually needs it.
            AuditEntry needsSearch = entryAt(NANO_PRECISE);
            assertEquals(VerificationOutcome.MATCH_RECOVERED, AuditHmac.verify(
                    asStoredByPostgres(needsSearch).withHmac(signV3(needsSearch)), hmacKey, budget));
            assertEquals(0, budget.searchesSkipped());
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
