/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scan-completeness flag has to survive the wire.
 *
 * <p>
 * {@code GET /administration/orphans} is the operator's review surface for an
 * irreversible deletion, and a partial scan lists resources that are still in
 * use as "orphans". A flag that the server sets but that never reaches — or
 * never reads back out of — the JSON body is indistinguishable from a clean
 * scan, which is exactly the failure it was added to remove.
 * </p>
 */
@DisplayName("OrphanReport — scan completeness on the wire")
class OrphanReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static OrphanInfo orphan() {
        return new OrphanInfo(URI.create("eddi://ai.labs.rules/rulestore/rulesets/aabbccddeeff112233445566?version=1"), "ai.labs.rules",
                "an-unreferenced-ruleset", false);
    }

    @Test
    @DisplayName("an incomplete scan round-trips its flag and its reason")
    void incompleteScanRoundTrips() throws Exception {
        var report = new OrphanReport(1, 0, List.of(orphan()), false, "could not enumerate Agent/workflow descriptors: boom");

        var restored = MAPPER.readValue(MAPPER.writeValueAsString(report), OrphanReport.class);

        assertFalse(restored.isScanComplete(), "a partial scan must still read as partial after serialization");
        assertEquals("could not enumerate Agent/workflow descriptors: boom", restored.getScanWarning());
        assertEquals(1, restored.getTotalOrphans());
        assertEquals(0, restored.getDeletedCount());
        assertEquals(1, restored.getOrphans().size());
    }

    /**
     * The default matters as much as the flag: a body written by an older node — or
     * by the three-argument constructor the purge still uses — carries no
     * {@code scanComplete}, and must not read back as "the scan failed".
     */
    @Test
    @DisplayName("a body without the flag reads as a complete scan, not a failed one")
    void absentFlagDefaultsToComplete() throws Exception {
        var restored = MAPPER.readValue("{\"totalOrphans\":2,\"deletedCount\":2,\"orphans\":[]}", OrphanReport.class);

        assertTrue(restored.isScanComplete());
        assertNull(restored.getScanWarning());
        assertEquals(2, restored.getTotalOrphans());
    }

    @Test
    @DisplayName("the three-argument constructor reports a complete scan")
    void threeArgConstructorIsComplete() {
        var report = new OrphanReport(3, 3, List.of(orphan()));

        assertTrue(report.isScanComplete());
        assertNull(report.getScanWarning());
        assertEquals(3, report.getDeletedCount());
    }
}
