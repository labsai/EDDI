/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.model;

import ai.labs.eddi.engine.audit.model.AuditVerificationReport.ChainStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two health bits have to distinguish "disproven" from "not proven".
 * <p>
 * A pre-v4 row that fails its direct check and is then not searched — because
 * the sweep's recovery budget ran out — establishes nothing either way. Failing
 * the direct check is the <em>expected</em> outcome for such a row, so counting
 * it as tampering would let a large enough page raise a compliance alarm purely
 * by exhausting a budget. That is the same "reported as proven when it is not"
 * shape this release exists to remove, pointed the other way.
 */
@DisplayName("verification report health bits")
class AuditVerificationReportTest {

    private static AuditVerificationReport report(int valid, int recovered, int recoverySkipped, int invalid, ChainStatus chain) {
        return new AuditVerificationReport("conversation", "conv-1", true, valid + invalid, valid, recovered, recoverySkipped,
                invalid, 0, chain, List.of(), List.of(), List.of(), List.of(), Instant.parse("2026-08-20T10:00:00Z"));
    }

    @Test
    @DisplayName("an entry whose HMAC was shown not to recompute is tampering")
    void realMismatchIsTampering() {
        var swept = report(9, 0, 0, 1, ChainStatus.INTACT);

        assertTrue(swept.tamperingSuspected());
        assertEquals(1, swept.disproven());
        assertFalse(swept.intact());
    }

    @Test
    @DisplayName("rows left unsearched by the budget are not tampering")
    void skippedRecoveryIsNotTampering() {
        var swept = report(0, 0, 500, 500, ChainStatus.INTACT);

        assertFalse(swept.tamperingSuspected(),
                "nothing was established about these rows — a budget ran out");
        assertEquals(0, swept.disproven());
    }

    @Test
    @DisplayName("but an unfinished sweep is still not a clean bill of health")
    void skippedRecoveryStillDefeatsIntact() {
        assertFalse(report(0, 0, 500, 500, ChainStatus.INTACT).intact(),
                "a sweep that could not finish checking must not report intact");
    }

    @Test
    @DisplayName("a real mismatch alongside skipped rows still raises the alarm")
    void mixedSweepReportsTheRealOne() {
        var swept = report(10, 2, 4, 5, ChainStatus.INTACT);

        assertTrue(swept.tamperingSuspected(), "one row of the five was actually disproven");
        assertEquals(1, swept.disproven());
    }

    @Test
    @DisplayName("a broken chain is tampering regardless of the recovery counts")
    void brokenChainStillCounts() {
        assertTrue(report(10, 0, 3, 3, ChainStatus.BROKEN).tamperingSuspected());
    }

    @Test
    @DisplayName("a clean sweep stays clean")
    void cleanSweep() {
        var swept = report(10, 0, 0, 0, ChainStatus.INTACT);

        assertTrue(swept.intact());
        assertFalse(swept.tamperingSuspected());
        assertEquals(0, swept.disproven());
    }

    @Test
    @DisplayName("recovered rows are valid and raise nothing")
    void recoveredRowsAreClean() {
        var swept = report(10, 10, 0, 0, ChainStatus.INTACT);

        assertTrue(swept.intact(), "recovery proves integrity as strongly as a direct match");
        assertFalse(swept.tamperingSuspected());
    }

    /** Defensive: the counts come from one sweep, but never go negative. */
    @Test
    @DisplayName("a skipped count larger than invalid cannot produce a negative")
    void countsCannotGoNegative() {
        assertEquals(0, report(0, 0, 7, 3, ChainStatus.INTACT).disproven());
        assertFalse(report(0, 0, 7, 3, ChainStatus.INTACT).tamperingSuspected());
    }
}
