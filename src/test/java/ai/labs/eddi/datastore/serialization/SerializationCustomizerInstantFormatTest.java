/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the REST/persistence split of the JSON date format.
 *
 * <p>
 * The deployment sets {@code quarkus.jackson.write-dates-as-timestamps=true},
 * so {@code JavaTimeModule} renders an {@link Instant} as fractional epoch
 * <em>seconds</em>. Clients call {@code new Date(value)}, which expects
 * <em>millis</em>, so every timestamp showed as a 1970 date.
 * </p>
 *
 * <p>
 * The fix applies only to the CDI/REST mapper. The
 * {@link PersistenceMapper}-qualified mapper must keep the numeric shape,
 * because stores write epoch-millis for range queries and sort server-side on
 * persisted timestamps — mixing formats would reorder results silently. These
 * tests assert both halves, since a change that "fixed" both would be the
 * regression commit {@code dc117cddc} already reverted once.
 * </p>
 */
@DisplayName("SerializationCustomizer — REST vs persistence date format")
class SerializationCustomizerInstantFormatTest {

    private static final Instant FIXED = Instant.ofEpochMilli(1719964800123L);

    /** The CDI/REST mapper: customizer + the Quarkus timestamp flag. */
    private static ObjectMapper restMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
        new SerializationCustomizer(false).customize(mapper);
        return mapper;
    }

    /**
     * The REAL producer, not a reconstruction. Building a look-alike here once
     * masked the thing under test: the fixture set WRITE_DATES_AS_TIMESTAMPS
     * itself, so it would have passed even if the producer had left Instants as ISO
     * strings.
     */
    private static ObjectMapper persistenceMapper() {
        return new PersistenceMapperProducer().persistenceMapper();
    }

    @Nested
    @DisplayName("REST mapper")
    class Rest {

        @Test
        @DisplayName("Instant serializes as an ISO-8601 string")
        void instantIsIsoString() throws Exception {
            assertEquals("{\"value\":\"2024-07-03T00:00:00.123Z\"}", restMapper().writeValueAsString(new InstantHolder(FIXED)));
        }

        @Test
        @DisplayName("java.util.Date stays epoch millis so working clients keep working")
        void dateStaysNumeric() throws Exception {
            assertEquals("{\"value\":1719964800123}", restMapper().writeValueAsString(new DateHolder(new Date(1719964800123L))));
        }
    }

    @Nested
    @DisplayName("persistence mapper")
    class Persistence {

        @Test
        @DisplayName("Instant stays NUMERIC — the stored shape must not change")
        void instantStaysNumeric() throws Exception {
            String json = persistenceMapper().writeValueAsString(new InstantHolder(FIXED));

            assertTrue(json.matches("\\{\"value\":[0-9.]+}"),
                    "persisted Instants must stay numeric or server-side sorts and range queries break; got: " + json);
        }

        @Test
        @DisplayName("does not inherit the REST override even though both share configureObjectMapper")
        void doesNotInheritRestOverride() throws Exception {
            String rest = restMapper().writeValueAsString(new InstantHolder(FIXED));
            String persisted = persistenceMapper().writeValueAsString(new InstantHolder(FIXED));

            assertTrue(rest.contains("\""), "REST form should be a quoted string, got: " + rest);
            assertTrue(!persisted.contains("\"value\":\""), "persistence form must not be quoted, got: " + persisted);
        }
    }

    @Nested
    @DisplayName("deserialization back-compatibility")
    class Deserialization {

        @Test
        @DisplayName("REST mapper still reads the old fractional-epoch-seconds form")
        void restReadsNumeric() throws Exception {
            assertEquals(FIXED, restMapper().readValue("{\"value\":1719964800.123000000}", InstantHolder.class).getValue());
        }

        @Test
        @DisplayName("persistence mapper reads BOTH numeric and ISO, so existing rows survive")
        void persistenceReadsBoth() throws Exception {
            ObjectMapper mapper = persistenceMapper();

            assertEquals(FIXED, mapper.readValue("{\"value\":1719964800.123000000}", InstantHolder.class).getValue());
            assertEquals(FIXED, mapper.readValue("{\"value\":\"2024-07-03T00:00:00.123Z\"}", InstantHolder.class).getValue());
        }

        @Test
        @DisplayName("each mapper round-trips its own output")
        void roundTrips() throws Exception {
            ObjectMapper rest = restMapper();
            ObjectMapper persisted = persistenceMapper();

            assertEquals(FIXED, rest.readValue(rest.writeValueAsString(new InstantHolder(FIXED)), InstantHolder.class).getValue());
            assertEquals(FIXED,
                    persisted.readValue(persisted.writeValueAsString(new InstantHolder(FIXED)), InstantHolder.class).getValue());
        }
    }

    public static class InstantHolder {
        private Instant value;

        public InstantHolder() {
        }

        public InstantHolder(Instant value) {
            this.value = value;
        }

        public Instant getValue() {
            return value;
        }

        public void setValue(Instant value) {
            this.value = value;
        }
    }

    public static class DateHolder {
        private Date value;

        public DateHolder() {
        }

        public DateHolder(Date value) {
            this.value = value;
        }

        public Date getValue() {
            return value;
        }

        public void setValue(Date value) {
            this.value = value;
        }
    }
}
