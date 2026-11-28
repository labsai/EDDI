/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule.mongo;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Branch coverage tests for {@link MongoScheduleStore} — error paths, edge
 * cases, and convertInstantField variants.
 */
@SuppressWarnings("unchecked")
@DisplayName("MongoScheduleStore — Branch Coverage")
class MongoScheduleStoreBranchTest {

    private MongoCollection<Document> scheduleCollection;
    private MongoCollection<Document> fireLogCollection;
    private IJsonSerialization jsonSerialization;
    private IDocumentBuilder documentBuilder;
    private MongoScheduleStore store;

    @BeforeEach
    void setUp() throws Exception {
        MongoDatabase database = mock(MongoDatabase.class);
        scheduleCollection = mock(MongoCollection.class);
        fireLogCollection = mock(MongoCollection.class);
        jsonSerialization = mock(IJsonSerialization.class);
        documentBuilder = mock(IDocumentBuilder.class);

        doReturn(scheduleCollection).when(database).getCollection("eddi_schedules");
        doReturn(fireLogCollection).when(database).getCollection("eddi_schedule_fire_logs");

        // The store keeps a primary-read view of the schedule collection for the two
        // reads the erasure guarantee rests on. One logical node here, so the two
        // views are the same mock and the stubs below apply to both; that they are
        // distinct on a replica set is pinned in MongoScheduleStoreTest.
        doReturn(scheduleCollection).when(scheduleCollection).withReadPreference(any(ReadPreference.class));

        // Every delete path now cascades to the schedule's fire logs (they are
        // unreachable once the schedule row is gone, and each carries a
        // conversationId). Permissive defaults; tests that care re-stub them.
        DeleteResult noneDeleted = mock(DeleteResult.class);
        when(noneDeleted.getDeletedCount()).thenReturn(0L);
        when(fireLogCollection.deleteMany(any(Bson.class))).thenReturn(noneDeleted);
        when(scheduleCollection.deleteOne(any(Bson.class))).thenReturn(noneDeleted);
        FindIterable<Document> empty = mock(FindIterable.class);
        MongoCursor<Document> emptyCursor = mock(MongoCursor.class);
        when(emptyCursor.hasNext()).thenReturn(false);
        when(empty.projection(any())).thenReturn(empty);
        when(empty.iterator()).thenReturn(emptyCursor);
        when(scheduleCollection.find(any(Bson.class))).thenReturn(empty);

        store = new MongoScheduleStore(database, jsonSerialization, documentBuilder, 100);
    }

    // ==================== readSchedule error paths ====================

    @Nested
    @DisplayName("readSchedule error paths")
    class ReadScheduleErrors {

        @Test
        @DisplayName("wraps IOException from documentBuilder in ResourceStoreException")
        void documentBuilderIOException() throws Exception {
            Document doc = new Document("_id", "s1").append("id", "s1");
            FindIterable<Document> iterable = mock(FindIterable.class);
            doReturn(iterable).when(scheduleCollection).find(any(Bson.class));
            doReturn(doc).when(iterable).first();

            doThrow(new IOException("build fail")).when(documentBuilder)
                    .build(any(Document.class), eq(ScheduleConfiguration.class));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> store.readSchedule("s1"));
        }
    }

    // ==================== updateSchedule error paths ====================

    @Test
    @DisplayName("updateSchedule wraps RuntimeException in ResourceStoreException")
    void updateScheduleRuntimeException() throws Exception {
        // The failure is provoked at the write, not at serialization: updateSchedule is
        // now an explicit $set of the editable fields and never serializes the object.
        // Stubbing serialize() here still "passed" afterwards, but only because the
        // unstubbed updateOne returned null and the NPE happened to be wrapped too.
        ScheduleConfiguration config = new ScheduleConfiguration();
        doThrow(new RuntimeException("db error")).when(scheduleCollection)
                .updateOne(any(Bson.class), any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.updateSchedule("s1", config));
    }

    // ==================== setScheduleEnabled error path ====================

    @Test
    @DisplayName("setScheduleEnabled wraps RuntimeException in ResourceStoreException")
    void setScheduleEnabledRuntimeException() throws Exception {
        doThrow(new RuntimeException("db error")).when(scheduleCollection)
                .updateOne(any(Bson.class), any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.setScheduleEnabled("s1", true, Instant.now()));
    }

    // ==================== deleteSchedule error path ====================

    @Test
    @DisplayName("deleteSchedule wraps exception in ResourceStoreException")
    void deleteScheduleException() throws Exception {
        doThrow(new RuntimeException("db error")).when(scheduleCollection)
                .deleteOne(any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.deleteSchedule("s1"));
    }

    // ==================== deleteSchedulesByAgentId ====================

    @Nested
    @DisplayName("deleteSchedulesByAgentId")
    class DeleteByAgentId {

        @Test
        @DisplayName("wraps exception in ResourceStoreException")
        void exceptionPath() throws Exception {
            doThrow(new RuntimeException("db error")).when(scheduleCollection)
                    .deleteMany(any(Bson.class));

            assertThrows(IResourceStore.ResourceStoreException.class,
                    () -> store.deleteSchedulesByAgentId("agent1"));
        }

        @Test
        @DisplayName("returns 0 without logging when no schedules deleted")
        void zeroDeleted() throws Exception {
            DeleteResult result = mock(DeleteResult.class);
            doReturn(0L).when(result).getDeletedCount();
            doReturn(result).when(scheduleCollection).deleteMany(any(Bson.class));

            assertEquals(0, store.deleteSchedulesByAgentId("agent1"));
        }
    }

    // ==================== findDueSchedules error path ====================

    @Test
    @DisplayName("findDueSchedules wraps exception in ResourceStoreException")
    void findDueSchedulesException() throws Exception {
        doThrow(new RuntimeException("db error")).when(scheduleCollection)
                .find(any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.findDueSchedules(Instant.now(), Duration.ZERO, 3));
    }

    // ==================== tryClaim error path ====================

    @Test
    @DisplayName("tryClaim wraps exception in ResourceStoreException")
    void tryClaimException() throws Exception {
        doThrow(new RuntimeException("db error")).when(scheduleCollection)
                .findOneAndUpdate(any(Bson.class), anyList());

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.tryClaim("s1", "inst1", Instant.now(), Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("tryClaim re-checks enabled: a schedule disabled after the poll's read cannot be claimed")
    void tryClaimRequiresEnabled() throws Exception {
        store.tryClaim("s1", "inst1", Instant.now(), Duration.ofMinutes(5));

        String rendered = renderedClaimFilter();
        assertTrue(rendered.contains("\"enabled\": true"), "the claim filter must require enabled=true: " + rendered);
    }

    /**
     * A poll batch is read once and claimed row by row; a row another instance
     * claimed, fired and re-armed in between is PENDING again with a future
     * nextFire, and a claim on state alone fired it a second time.
     */
    @Test
    @DisplayName("tryClaim re-checks due-ness: a schedule re-armed after the poll's read cannot be claimed")
    void tryClaimRequiresTheScheduleToStillBeDue() throws Exception {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        store.tryClaim("s1", "inst1", now, Duration.ofMinutes(5));

        String rendered = renderedClaimFilter();
        assertTrue(rendered.contains("\"nextFire\": {\"$lte\": " + now.toEpochMilli() + "}"),
                "the poller's claim must require nextFire <= now: " + rendered);
    }

    @Test
    @DisplayName("tryClaimAnyTime has no due check: Fire now may fire before the next fire time")
    void tryClaimAnyTimeHasNoDueCheck() throws Exception {
        store.tryClaimAnyTime("s1", "inst1", Instant.now(), Duration.ofMinutes(5));

        String rendered = renderedClaimFilter();
        assertFalse(rendered.contains("nextFire"), "a manual fire's claim must not depend on nextFire: " + rendered);
        assertTrue(rendered.contains("\"enabled\": true"), "every other claim condition still applies: " + rendered);
    }

    /**
     * claimedAt is written by one instance and compared by another; on the
     * claimers' own clocks, an instance running fast stole a live claim. Both the
     * write and the expiry test must use the database's clock.
     */
    @Test
    @DisplayName("tryClaim writes claimedAt and judges the lease on the database's clock")
    void tryClaimUsesTheDatabaseClockForTheLease() throws Exception {
        store.tryClaim("s1", "inst1", Instant.now(), Duration.ofMinutes(10));

        String filter = renderedClaimFilter();
        assertTrue(filter.contains("\"$$NOW\"") && filter.contains(String.valueOf(Duration.ofMinutes(10).toMillis())),
                "the lease must be measured against the database's clock: " + filter);
        assertFalse(filter.contains("\"claimedAt\": {\"$lte\""), "no lease bound computed on this instance's clock: " + filter);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Bson>> update = ArgumentCaptor.forClass(List.class);
        verify(scheduleCollection).findOneAndUpdate(any(Bson.class), update.capture());
        String set = update.getValue().getFirst().toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
        assertTrue(set.contains("\"claimedAt\": {\"$toLong\": \"$$NOW\"}"), "claimedAt must come from the database's clock: " + set);
    }

    @Test
    @DisplayName("findDueSchedules judges an expired lease on the database's clock")
    void findDueSchedulesUsesTheDatabaseClockForTheLease() throws Exception {
        doThrow(new RuntimeException("stop once the filter is built")).when(scheduleCollection).find(any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class, () -> store.findDueSchedules(Instant.now(), Duration.ofMinutes(10), 3));

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).find(filter.capture());
        String rendered = filter.getValue().toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
        assertTrue(rendered.contains("\"$$NOW\""), "the lease must be measured against the database's clock: " + rendered);
        assertFalse(rendered.contains("\"claimedAt\": {\"$lte\""), "no lease bound computed on this instance's clock: " + rendered);
    }

    private String renderedClaimFilter() {
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).findOneAndUpdate(filter.capture(), anyList());
        return filter.getValue().toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
    }

    // ==================== markCompleted error path ====================

    @Test
    @DisplayName("markCompleted wraps exception in ResourceStoreException")
    void markCompletedException() throws Exception {
        doThrow(new RuntimeException("db error")).when(scheduleCollection)
                .updateOne(any(Bson.class), any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.markCompleted("s1", Instant.now()));
    }

    // ==================== markFailed error path ====================

    @Test
    @DisplayName("markFailed wraps exception in ResourceStoreException")
    void markFailedException() throws Exception {
        doThrow(new RuntimeException("db error")).when(scheduleCollection)
                .updateOne(any(Bson.class), any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.markFailed("s1", Instant.now()));
    }

    // ==================== markDeadLettered error path ====================

    @Test
    @DisplayName("markDeadLettered wraps exception in ResourceStoreException")
    void markDeadLetteredException() throws Exception {
        doThrow(new RuntimeException("db error")).when(scheduleCollection)
                .updateOne(any(Bson.class), any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.markDeadLettered("s1"));
    }

    // ==================== requeueDeadLetter error path ====================

    @Test
    @DisplayName("requeueDeadLetter wraps RuntimeException in ResourceStoreException")
    void requeueDeadLetterRuntimeException() throws Exception {
        doThrow(new RuntimeException("db error")).when(scheduleCollection)
                .updateOne(any(Bson.class), any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.requeueDeadLetter("s1"));
    }

    // ==================== logFire error path ====================

    @Test
    @DisplayName("logFire wraps exception in ResourceStoreException")
    void logFireException() throws Exception {
        ScheduleFireLog log = new ScheduleFireLog("l1", "s1", "f1",
                Instant.now(), Instant.now(), null, "FAILED", "inst1", null, "err", 1, 0.0);
        doThrow(new IOException("serialize fail")).when(jsonSerialization).serialize(any());

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.logFire(log));
    }

    // ==================== readFireLogs error path ====================

    @Test
    @DisplayName("readFireLogs wraps exception in ResourceStoreException")
    void readFireLogsException() throws Exception {
        doThrow(new RuntimeException("db error")).when(fireLogCollection)
                .find(any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.readFireLogs("s1", 10));
    }

    // ==================== readFailedFireLogs error path ====================

    @Test
    @DisplayName("readFailedFireLogs wraps exception in ResourceStoreException")
    void readFailedFireLogsException() throws Exception {
        doThrow(new RuntimeException("db error")).when(fireLogCollection)
                .find(any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.readFailedFireLogs(50));
    }

    // ==================== readAllSchedules error path ====================

    @Test
    @DisplayName("readAllSchedules wraps exception in ResourceStoreException")
    void readAllSchedulesException() throws Exception {
        doThrow(new RuntimeException("db error")).when(scheduleCollection)
                .find(any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.readAllSchedules(100));
    }

    // ==================== Instant field write/read helpers ====================

    @Nested
    @DisplayName("Instant field write/read helpers")
    class InstantFieldHelpers {

        @Test
        @DisplayName("writeScheduleInstants stores Instant getters as epoch-MILLIS Long (not seconds)")
        void writeScheduleInstantsAsMillis() throws Exception {
            var method = MongoScheduleStore.class.getDeclaredMethod("writeScheduleInstants", Document.class, ScheduleConfiguration.class);
            method.setAccessible(true);

            ScheduleConfiguration schedule = new ScheduleConfiguration();
            schedule.setNextFire(Instant.ofEpochMilli(1719964800123L));
            Document doc = new Document();

            method.invoke(null, doc, schedule);

            Object result = doc.get("nextFire");
            assertInstanceOf(Long.class, result);
            assertEquals(1719964800123L, result, "must be epoch-MILLIS, not the epoch-SECONDS the mapper would produce");
        }

        @Test
        @DisplayName("writeScheduleInstants stores null for a null Instant getter")
        void writeScheduleInstantsNull() throws Exception {
            var method = MongoScheduleStore.class.getDeclaredMethod("writeScheduleInstants", Document.class, ScheduleConfiguration.class);
            method.setAccessible(true);

            Document doc = new Document();
            method.invoke(null, doc, new ScheduleConfiguration());

            assertTrue(doc.containsKey("nextRetryAt"));
            assertNull(doc.get("nextRetryAt"));
        }

        @Test
        @DisplayName("writeFireLogInstants stores Instant components as epoch-MILLIS Long")
        void writeFireLogInstantsAsMillis() throws Exception {
            var method = MongoScheduleStore.class.getDeclaredMethod("writeFireLogInstants", Document.class, ScheduleFireLog.class);
            method.setAccessible(true);

            ScheduleFireLog log = new ScheduleFireLog("l1", "s1", "f1",
                    Instant.ofEpochMilli(1719964800123L), Instant.ofEpochMilli(1719964800456L), null,
                    "COMPLETED", "inst1", "conv1", null, 1, 0.0);
            Document doc = new Document();

            method.invoke(null, doc, log);

            assertEquals(1719964800123L, doc.get("fireTime"));
            assertEquals(1719964800456L, doc.get("startedAt"));
            assertTrue(doc.containsKey("completedAt"));
            assertNull(doc.get("completedAt"));
        }

        @Test
        @DisplayName("readEpochMillis converts a stored Long back to the same Instant")
        void readEpochMillisRoundTrip() throws Exception {
            var method = MongoScheduleStore.class.getDeclaredMethod("readEpochMillis", Document.class, String.class);
            method.setAccessible(true);

            Document doc = new Document("nextFire", 1719964800123L);
            assertEquals(Instant.ofEpochMilli(1719964800123L), method.invoke(null, doc, "nextFire"));
        }

        @Test
        @DisplayName("readEpochMillis returns null for a missing or non-numeric field")
        void readEpochMillisNull() throws Exception {
            var method = MongoScheduleStore.class.getDeclaredMethod("readEpochMillis", Document.class, String.class);
            method.setAccessible(true);

            assertNull(method.invoke(null, new Document(), "nextFire"));
            assertNull(method.invoke(null, new Document("nextFire", "not-a-number"), "nextFire"));
        }
    }

    // ==================== toDocument error path ====================

    @Test
    @DisplayName("toDocument wraps IOException in ResourceStoreException via createSchedule")
    void toDocumentIOException() throws Exception {
        ScheduleConfiguration config = new ScheduleConfiguration();
        doReturn("{}").when(jsonSerialization).serialize(any());
        doThrow(new IOException("deserialize fail")).when(jsonSerialization)
                .deserialize(anyString(), eq(Document.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.createSchedule(config));
    }

    // ==================== fireLogFromDocument error path ====================

    @Test
    @DisplayName("fireLogFromDocument wraps IOException in ResourceStoreException")
    void fireLogFromDocumentIOException() throws Exception {
        Document doc = new Document("_id", "l1");
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        FindIterable<Document> iterable = mock(FindIterable.class);
        doReturn(iterable).when(fireLogCollection).find(any(Bson.class));
        doReturn(iterable).when(iterable).sort(any(Document.class));
        doReturn(iterable).when(iterable).limit(anyInt());
        doReturn(cursor).when(iterable).iterator();
        doReturn(true, false).when(cursor).hasNext();
        doReturn(doc).when(cursor).next();

        doThrow(new IOException("build fail")).when(documentBuilder)
                .build(any(Document.class), eq(ScheduleFireLog.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.readFireLogs("s1", 10));
    }

    // ==================== fromDocument — _id to id mapping ====================

    @Test
    @DisplayName("fromDocument maps _id to id field")
    void fromDocumentMapsId() throws Exception {
        Document doc = new Document("_id", "sched-test");
        FindIterable<Document> iterable = mock(FindIterable.class);
        doReturn(iterable).when(scheduleCollection).find(any(Bson.class));
        doReturn(doc).when(iterable).first();

        ScheduleConfiguration config = new ScheduleConfiguration();
        doReturn(config).when(documentBuilder).build(any(Document.class), eq(ScheduleConfiguration.class));

        store.readSchedule("sched-test");

        // Verify the doc has "id" field set from "_id"
        assertEquals("sched-test", doc.get("id"));
    }

    // ==================== setScheduleEnabled — enabled=true with null nextFire
    // ====================

    @Test
    @DisplayName("setScheduleEnabled — enabled=true with null nextFire skips nextFire/status updates")
    void setScheduleEnabledTrueNullNextFire() throws Exception {
        UpdateResult updateResult = mock(UpdateResult.class);
        doReturn(1L).when(updateResult).getMatchedCount();
        doReturn(updateResult).when(scheduleCollection).updateOne(any(Bson.class), any(Bson.class));

        // enabled=true but nextFire=null — the condition is (enabled && nextFire !=
        // null),
        // so nextFire/status updates are skipped
        assertDoesNotThrow(() -> store.setScheduleEnabled("s1", true, null));
    }
}
