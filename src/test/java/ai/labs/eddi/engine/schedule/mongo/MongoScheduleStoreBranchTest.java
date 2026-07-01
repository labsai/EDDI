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

import java.io.IOException;
import java.time.Instant;
import java.util.Date;

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

        store = new MongoScheduleStore(database, jsonSerialization, documentBuilder);
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
        ScheduleConfiguration config = new ScheduleConfiguration();
        doThrow(new RuntimeException("serialize fail")).when(jsonSerialization).serialize(any());

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
                () -> store.findDueSchedules(Instant.now(), Instant.now(), 3));
    }

    // ==================== tryClaim error path ====================

    @Test
    @DisplayName("tryClaim wraps exception in ResourceStoreException")
    void tryClaimException() throws Exception {
        doThrow(new RuntimeException("db error")).when(scheduleCollection)
                .findOneAndUpdate(any(Bson.class), any(Bson.class));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.tryClaim("s1", "inst1", Instant.now()));
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

    // ==================== convertInstantField edge cases ====================

    @Nested
    @DisplayName("convertInstantField via storeInstantsAsLong")
    class ConvertInstantFieldTests {

        @Test
        @DisplayName("handles Date fields by converting to epoch millis")
        void dateField() throws Exception {
            var method = MongoScheduleStore.class.getDeclaredMethod("storeInstantsAsLong", Document.class);
            method.setAccessible(true);

            Document doc = new Document();
            Date now = new Date();
            doc.put("nextFire", now);

            method.invoke(null, doc);

            Object result = doc.get("nextFire");
            assertInstanceOf(Long.class, result);
            assertEquals(now.getTime(), result);
        }

        @Test
        @DisplayName("handles Instant fields by converting to epoch millis")
        void instantField() throws Exception {
            var method = MongoScheduleStore.class.getDeclaredMethod("storeInstantsAsLong", Document.class);
            method.setAccessible(true);

            Document doc = new Document();
            Instant now = Instant.now();
            doc.put("lastFired", now);

            method.invoke(null, doc);

            Object result = doc.get("lastFired");
            assertInstanceOf(Long.class, result);
            assertEquals(now.toEpochMilli(), result);
        }

        @Test
        @DisplayName("handles Number fields by normalizing to Long")
        void numberField() throws Exception {
            var method = MongoScheduleStore.class.getDeclaredMethod("storeInstantsAsLong", Document.class);
            method.setAccessible(true);

            Document doc = new Document();
            doc.put("claimedAt", 12345); // Integer

            method.invoke(null, doc);

            Object result = doc.get("claimedAt");
            assertInstanceOf(Long.class, result);
            assertEquals(12345L, result);
        }

        @Test
        @DisplayName("null fields are left as-is")
        void nullField() throws Exception {
            var method = MongoScheduleStore.class.getDeclaredMethod("storeInstantsAsLong", Document.class);
            method.setAccessible(true);

            Document doc = new Document();
            doc.put("nextRetryAt", null);

            method.invoke(null, doc);

            assertNull(doc.get("nextRetryAt"));
        }

        @Test
        @DisplayName("string fields are left as-is")
        void stringField() throws Exception {
            var method = MongoScheduleStore.class.getDeclaredMethod("storeInstantsAsLong", Document.class);
            method.setAccessible(true);

            Document doc = new Document();
            doc.put("createdAt", "not-a-date");

            method.invoke(null, doc);

            assertEquals("not-a-date", doc.get("createdAt"));
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
