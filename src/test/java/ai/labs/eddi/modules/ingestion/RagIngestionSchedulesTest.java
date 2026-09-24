/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The metadata a scheduled fire is read back from.
 *
 * <p>
 * This is the whole contract between a stored schedule and the run it triggers,
 * and it is read after a round trip through MongoDB or PostgreSQL — which is
 * why the version is taken as a {@link Number} rather than an {@code Integer}.
 * A driver that hands back a {@code Double} for a stored integer would
 * otherwise make every scheduled run read version {@code null}, and a version
 * of null is the wrong knowledge base, not an error anyone sees.
 */
class RagIngestionSchedulesTest {

    @Test
    @DisplayName("round-trips the fields a fire needs")
    void metadataRoundTrips() {
        Map<String, Object> metadata = RagIngestionSchedules.metadata("kb-1", 3, "src-1");

        assertTrue(RagIngestionSchedules.isIngestionSchedule(metadata));
        assertEquals("kb-1", RagIngestionSchedules.ragConfigId(metadata));
        assertEquals(3, RagIngestionSchedules.ragConfigVersion(metadata));
        assertEquals("src-1", RagIngestionSchedules.sourceId(metadata));
        assertEquals("rag-ingestion:kb-1:src-1", RagIngestionSchedules.scheduleName("kb-1", "src-1"));
    }

    @Test
    @DisplayName("a missing version means version 1, not null")
    void absentVersionDefaultsToOne() {
        assertEquals(1, RagIngestionSchedules.ragConfigVersion(RagIngestionSchedules.metadata("kb-1", null, "src-1")));
    }

    @Test
    @DisplayName("a version that came back from the database as a Double still reads as a number")
    void versionSurvivesADatabaseRoundTrip() {
        // BSON and JDBC both hand back numeric types the writer did not choose.
        Map<String, Object> asDouble = new HashMap<>(RagIngestionSchedules.metadata("kb-1", 7, "src-1"));
        asDouble.put(RagIngestionSchedules.METADATA_RAG_CONFIG_VERSION, 7.0d);
        assertEquals(7, RagIngestionSchedules.ragConfigVersion(asDouble));

        Map<String, Object> asLong = new HashMap<>(asDouble);
        asLong.put(RagIngestionSchedules.METADATA_RAG_CONFIG_VERSION, 7L);
        assertEquals(7, RagIngestionSchedules.ragConfigVersion(asLong));
    }

    @Test
    @DisplayName("unusable metadata reads as absent rather than throwing on a scheduler thread")
    void unusableMetadataIsNull() {
        assertNull(RagIngestionSchedules.ragConfigVersion(null));
        assertNull(RagIngestionSchedules.ragConfigId(null));
        assertNull(RagIngestionSchedules.sourceId(null));
        assertFalse(RagIngestionSchedules.isIngestionSchedule(null));

        Map<String, Object> wrongType = new HashMap<>(RagIngestionSchedules.metadata("kb-1", 2, "src-1"));
        wrongType.put(RagIngestionSchedules.METADATA_RAG_CONFIG_VERSION, "2");
        assertNull(RagIngestionSchedules.ragConfigVersion(wrongType),
                "a string version is not silently parsed — the caller decides what to do");
    }

    @Test
    @DisplayName("a schedule that is not an ingestion schedule is not claimed")
    void otherSchedulesAreNotClaimed() {
        assertFalse(RagIngestionSchedules.isIngestionSchedule(Map.of()));
        assertFalse(RagIngestionSchedules.isIngestionSchedule(Map.of("ragIngestion", "true")),
                "the marker is a boolean; a string must not pass, or any schedule carrying that key fires a crawl");
    }
}
