/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule.mongo;

import ai.labs.eddi.datastore.serialization.DocumentBuilder;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the Instant → epoch-millis storage contract on the
 * MongoDB backend, exercised through the REAL shared JSON serialization rather
 * than mocks.
 * <p>
 * Guards against the class of bug where the shared mapper serializes an Instant
 * as fractional SECONDS ({@code write-dates-as-timestamps} +
 * {@code JavaTimeModule}) and a naive "already epoch-millis" assumption
 * truncates it to epoch-SECONDS — 1000x too small — which makes every
 * future-armed schedule (including HITL approval timeouts) look immediately
 * due, and diverges from
 * {@link ai.labs.eddi.datastore.postgres.PostgresScheduleStore}.
 * <p>
 * The sibling {@code MongoScheduleStoreTest} mocks {@code IJsonSerialization}
 * and {@code IDocumentBuilder}, so it never exercises the real round-trip where
 * the bug lives. This test wires the production mapper configuration
 * deliberately.
 */
@SuppressWarnings("unchecked")
@DisplayName("MongoScheduleStore — Instant epoch-millis round-trip (real serialization)")
class MongoScheduleStoreInstantRoundTripTest {

    private static final long ONE_TRILLION = 1_000_000_000_000L;

    private MongoCollection<Document> scheduleCollection;
    private MongoScheduleStore store;

    @BeforeEach
    void setUp() {
        // Configure the mapper exactly as production does: SerializationCustomizer
        // registers JavaTimeModule, and application.properties keeps numeric
        // (timestamp) dates. This is the configuration that makes an Instant
        // serialize as fractional SECONDS — the trigger for the original bug.
        ObjectMapper mapper = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
        mapper.configure(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS, true);
        IJsonSerialization jsonSerialization = new JsonSerialization(mapper);
        IDocumentBuilder documentBuilder = new DocumentBuilder(jsonSerialization);

        MongoDatabase database = mock(MongoDatabase.class);
        scheduleCollection = mock(MongoCollection.class);
        MongoCollection<Document> fireLogCollection = mock(MongoCollection.class);
        when(database.getCollection("eddi_schedules")).thenReturn(scheduleCollection);
        when(database.getCollection("eddi_schedule_fire_logs")).thenReturn(fireLogCollection);

        store = new MongoScheduleStore(database, jsonSerialization, documentBuilder, 100);
    }

    @Test
    @DisplayName("createSchedule stores a future nextFire as epoch-MILLIS, not epoch-seconds")
    void futureNextFireStoredAsMillis() throws Exception {
        Instant nextFire = Instant.now().plusSeconds(3600);
        ScheduleConfiguration schedule = new ScheduleConfiguration();
        schedule.setName("hitl-timeout-conv1");
        schedule.setNextFire(nextFire);

        store.createSchedule(schedule);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(scheduleCollection).insertOne(captor.capture());
        Object storedNextFire = captor.getValue().get("nextFire");

        assertInstanceOf(Long.class, storedNextFire, "nextFire must be persisted as a Long epoch value");
        long storedMs = (Long) storedNextFire;

        assertEquals(nextFire.toEpochMilli(), storedMs, "nextFire must be stored as epoch-millis, matching PostgresScheduleStore");
        assertTrue(storedMs > ONE_TRILLION,
                "an epoch-millis timestamp is ~1.7e12; a value ~1.7e9 would be epoch-seconds (the original bug)");
        // The whole point: a future schedule must NOT compare as already-due against
        // now-in-millis, which is exactly what findDueSchedules' `nextFire <= nowMs`
        // does.
        assertTrue(storedMs > Instant.now().toEpochMilli(), "a future schedule must not look immediately due");
    }

    @Test
    @DisplayName("readSchedule reconstructs the original nextFire Instant from the stored millis")
    void nextFireRoundTrips() throws Exception {
        Instant nextFire = Instant.now().plusSeconds(3600);
        ScheduleConfiguration schedule = new ScheduleConfiguration();
        schedule.setName("hitl-timeout-conv1");
        schedule.setNextFire(nextFire);

        store.createSchedule(schedule);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(scheduleCollection).insertOne(captor.capture());
        Document stored = captor.getValue();

        // Feed the exact stored document back through the read path.
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(scheduleCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(stored);

        ScheduleConfiguration readBack = store.readSchedule(schedule.getId());
        assertNotNull(readBack.getNextFire(), "nextFire must survive the round-trip");
        assertEquals(nextFire.toEpochMilli(), readBack.getNextFire().toEpochMilli(),
                "read-back nextFire must match the original to the millisecond");
    }
}
