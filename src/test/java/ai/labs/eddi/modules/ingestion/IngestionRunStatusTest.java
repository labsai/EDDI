/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.modules.ingestion.IIngestionStateStore.IngestionRun.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading a stored run status. Both stores go through {@link Status#parse}, so
 * a row written by a newer build — {@code MAINTENANCE} was new to the build
 * before this one — does not fail the whole run history with an exception
 * during a rolling upgrade or after a rollback.
 */
class IngestionRunStatusTest {

    @Test
    @DisplayName("a known status reads as itself")
    void readsKnownStatuses() {
        for (Status status : Status.values()) {
            assertEquals(status, Status.parse(status.name()));
        }
    }

    @Test
    @DisplayName("a status this build does not know reads as FAILED instead of throwing")
    void toleratesAnUnknownStatus() {
        assertEquals(Status.FAILED, Status.parse("SOMETHING_NEWER"));
        assertEquals(Status.FAILED, Status.parse(null));
    }
}
