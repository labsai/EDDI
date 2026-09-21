/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.datastore.postgres.PostgresAuditStore;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.audit.AuditHmac.VerificationOutcome;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit signature must survive the REAL database — not a simulated one.
 * <p>
 * The original defect (D7) was a cross-layer mismatch: the JVM signed
 * nanoseconds, PostgreSQL stored microseconds, and every unit test with a mock
 * store was structurally blind to it — a live ledger reported
 * {@code valid=0 invalid=78} while all its tests were green. The unit tests
 * that now cover the fix <em>simulate</em> storage truncation; this class
 * closes the loop against a real PostgreSQL container, so whatever the server
 * and JDBC driver actually do to a timestamp (floor, round to nearest, either
 * direction) is what the assertions run against. If a future PostgreSQL or
 * driver upgrade changes that behaviour, this is the test that says so.
 * <p>
 * <b>Scope, precisely:</b> this class pins the <em>shipped pipeline</em> (floor
 * → sign → store → read → verify) and the recovery search against real storage.
 * It deliberately floors before signing, exactly like the service — so a
 * canonical-form regression alone would still pass here (flooring makes even v3
 * round-trip); that regression is pinned by
 * {@code AuditHmacTimestampPrecisionTest}, which signs raw nanoseconds, and a
 * service-side flooring removal is pinned by {@code AuditLedgerServiceTest}.
 * What this class uniquely catches was demonstrated by mutation against the
 * live container: making the recovery search forward-only fails
 * {@code v3LegacyRowIsRecoverableFromRealStorage}, because the real
 * PostgreSQL/JDBC pipeline rounds a 789ns remainder <em>up</em> — the stored
 * value sits above the signed one, where a forward search can never reach it.
 */
@DisplayName("audit HMAC round-trip against real PostgreSQL")
class PostgresAuditHmacRoundTripTest extends PostgresTestBase {

    private static PostgresAuditStore store;
    private static DataSource ds;
    private static byte[] hmacKey;

    @BeforeAll
    static void init() {
        var dsInstance = createDataSourceInstance();
        ds = dsInstance.get();
        IJsonSerialization json = new JsonSerialization(
                SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        store = new PostgresAuditStore(dsInstance, json);
        hmacKey = AuditHmac.deriveHmacKey("pg-roundtrip-test-key");
    }

    @BeforeEach
    void clean() {
        try {
            truncateTables(ds, "audit_ledger");
        } catch (SQLException ignored) {
        }
    }

    private static AuditEntry entryAt(String convId, Instant timestamp) {
        return new AuditEntry(UUID.randomUUID().toString(), convId, "agent1", 1,
                "user1", "test", 0, "t1", "langchain", 0, 42L,
                null, null, null, null, List.of("greet"), 0.0,
                timestamp, null, null, 1L);
    }

    private static AuditEntry readBack(String convId) {
        List<AuditEntry> rows = store.getEntries(convId, 0, 10);
        assertEquals(1, rows.size());
        return rows.getFirst();
    }

    @Test
    @DisplayName("a v4-signed entry verifies after the real timestamptz round-trip")
    void v4SignedEntrySurvivesTheDatabase() {
        AuditEntry raw = entryAt("conv-rt-v4", Instant.now());
        // Exactly what AuditLedgerService does on submit: floor, then sign.
        AuditEntry floored = AuditHmac.withStorablePrecision(raw);
        AuditEntry signed = floored.withHmac(AuditHmac.computeHmac(floored, hmacKey));

        store.appendEntry(signed);
        AuditEntry stored = readBack("conv-rt-v4");

        assertEquals(signed.timestamp(), stored.timestamp(),
                "a millisecond-floored timestamp must round-trip byte-identically — nothing left for the database to round");
        assertTrue(AuditHmac.verifyHmac(stored, hmacKey),
                "the row read back must be the row that was signed");
    }

    /**
     * The pre-fix write path, replayed against the real database: sign the raw
     * nano-precision instant with v3, store it, read back whatever PostgreSQL kept.
     * Direct verification fails — that IS the defect — and the completion search
     * must still prove the row, whichever direction the server and driver moved the
     * timestamp. This test deliberately refuses to assume the direction.
     */
    @Test
    @DisplayName("a legacy v3 row is recoverable from what PostgreSQL actually stored")
    void v3LegacyRowIsRecoverableFromRealStorage() {
        AuditEntry raw = entryAt("conv-rt-v3",
                Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_789L));
        String v3Hmac = "v3:" + AuditHmacTestSupport.hmacHex(AuditHmac.buildCanonicalStringV3(raw), hmacKey);

        store.appendEntry(raw.withHmac(v3Hmac));
        AuditEntry stored = readBack("conv-rt-v3");

        assertNotEquals(raw.timestamp(), stored.timestamp(),
                "precondition: the database really does not keep nanoseconds");
        assertEquals(VerificationOutcome.MISMATCH, AuditHmac.verify(stored, hmacKey, false),
                "precondition: the stored row is not the signed row — the live defect");
        assertNotEquals(VerificationOutcome.MISMATCH,
                AuditHmac.verify(stored, hmacKey, new AuditRecoveryBudget(1)),
                "the completion search must find the signed timestamp on either side of what was stored");
    }

    /**
     * The timestamp was not the only field that failed to round-trip.
     * <p>
     * The pipeline hands the ledger LIVE Java objects — a turn's {@code output} is
     * a list of {@code TextOutputItem} POJOs, not of Maps — and the signature was
     * computed over those while verification later ran over what JSON gave back.
     * The two canonicalize completely differently: {@code s:<toString()>} against
     * {@code m&#123;…&#125;}. Measured here before the fix, one output item signed
     * as {@code {output=[Hi there!]}} came back as {@code {output=[{text=Hi there!,
     * type=text, delay=0}]}}.
     * <p>
     * This is why fixing the timestamp alone was not enough: the end-to-end IT
     * reported {@code entriesChecked=5 valid=2} on a plain rule-based turn, the
     * three failures being every task with rendered output in scope. The cure is
     * the one the timestamp got — normalise to the stored shape, THEN sign — which
     * lives in {@code AuditLedgerService}, so this test drives the real submit path
     * rather than hand-building the entry.
     */
    @Test
    @DisplayName("an entry whose payload holds POJOs verifies after the real round-trip")
    void pojoPayloadSurvivesTheDatabase() {
        AuditEntry raw = new AuditEntry(UUID.randomUUID().toString(), "conv-pojo", "agent1", 1,
                "user1", "test", 0, "t1", "output", 0, 42L,
                null, Map.of("output", List.of(new TextOutputItem("Hi there!", 0))), null, null,
                List.of("greet"), 0.0, Instant.now(), null, null, 1L);

        var service = AuditLedgerService.createForTesting(store, true, 3600, "pg-roundtrip-test-key",
                new SimpleMeterRegistry());
        service.init();
        service.submit(raw);
        service.flush();

        assertTrue(AuditHmac.verifyHmac(readBack("conv-pojo"), hmacKey),
                "an entry carrying rendered output must verify — that is most of a real turn's entries");
    }

    @Test
    @DisplayName("the Ed25519 agent signature round-trips (D7b)")
    void agentSignatureRoundTrips() {
        AuditEntry signed = entryAt("conv-rt-sig", Instant.now())
                .withAgentSignature("ed25519:dGVzdC1zaWduYXR1cmU=");

        store.appendEntry(signed);

        assertEquals("ed25519:dGVzdC1zaWduYXR1cmU=", readBack("conv-rt-sig").agentSignature(),
                "the column existed nowhere and the row-mapper hard-coded null — signatures were computed and discarded");
    }
}
