/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AuditStoreFailures - an entry the store refused vs. a store that is not answering")
class AuditStoreFailuresTest {

    private static RuntimeException wrapped(String sqlState) {
        return new RuntimeException("Failed to insert audit entry", new SQLException("driver text with the value", sqlState));
    }

    @ParameterizedTest
    @ValueSource(strings = {"22P05", "22021", "22001", "22P02", "23502", "23514"})
    @DisplayName("data exceptions and constraint violations reject the entry")
    void contentRefusals(String state) {
        assertTrue(AuditStoreFailures.isEntryRejected(wrapped(state)));
        assertFalse(AuditStoreFailures.isAlreadyStored(wrapped(state)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"08006", "08001", "40001", "40P01", "53300", "53100", "57P01", "57014", "XX000", "25P02"})
    @DisplayName("connection, serialization, resource and operator-intervention failures stay outages")
    void outages(String state) {
        assertFalse(AuditStoreFailures.isEntryRejected(wrapped(state)));
        assertFalse(AuditStoreFailures.isAlreadyStored(wrapped(state)));
    }

    @Test
    @DisplayName("an exception nobody classified, or one without SQLState, is an outage")
    void unclassified() {
        assertFalse(AuditStoreFailures.isEntryRejected(new IllegalStateException("pool exhausted")));
        assertFalse(AuditStoreFailures.isEntryRejected(new SQLException("no state")));
        assertFalse(AuditStoreFailures.isEntryRejected(null));
    }

    @Test
    @DisplayName("a unique violation means the entry is already stored")
    void duplicateIsAlreadyStored() {
        assertTrue(AuditStoreFailures.isAlreadyStored(wrapped("23505")));
    }

    @Test
    @DisplayName("the reason names the kind of refusal, never the driver message")
    void describeHidesTheDriverMessage() {
        String reason = AuditStoreFailures.describe(wrapped("22P05"));
        assertEquals("store rejected the entry: SQLException SQLState 22P05", reason);
        assertFalse(reason.contains("driver text"));
    }
}
