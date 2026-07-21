/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the JSON wire shape of {@link UsageSnapshot#costMonth()}.
 *
 * <p>
 * The deployment sets {@code quarkus.jackson.write-dates-as-timestamps=true}
 * (application.properties). Under that flag Jackson's
 * {@code YearMonthSerializer} takes its {@code useTimestamp} branch and writes
 * a two-element ARRAY — {@code [2026,7]} — instead of {@code "2026-07"}. Both
 * quota stores already persist the value as an ISO string, so the REST
 * representation was the only place that disagreed.
 * </p>
 */
@DisplayName("UsageSnapshot — REST serialization")
class UsageSnapshotSerializationTest {

    /**
     * Mirrors the production mapper: the shared customizer plus the
     * write-dates-as-timestamps flag Quarkus applies from application.properties.
     */
    private static ObjectMapper productionMapper() {
        ObjectMapper mapper = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
        return mapper;
    }

    @Test
    @DisplayName("costMonth serializes as \"YYYY-MM\", not the array [YYYY,M]")
    void costMonthIsIsoString() throws Exception {
        UsageSnapshot snapshot = new UsageSnapshot("t1", 3, 7, 12.5, Instant.ofEpochMilli(1719964800123L),
                Instant.ofEpochMilli(1719964800123L), YearMonth.of(2026, 7));

        String json = productionMapper().writeValueAsString(snapshot);

        assertTrue(json.contains("\"costMonth\":\"2026-07\""), "expected an ISO year-month string, got: " + json);
    }

    @Test
    @DisplayName("costMonth round-trips through the REST mapper")
    void costMonthRoundTrips() throws Exception {
        ObjectMapper mapper = productionMapper();
        UsageSnapshot original = UsageSnapshot.empty("t1");

        UsageSnapshot parsed = mapper.readValue(mapper.writeValueAsString(original), UsageSnapshot.class);

        assertEquals(original.costMonth(), parsed.costMonth());
        assertEquals(original.tenantId(), parsed.tenantId());
    }
}
