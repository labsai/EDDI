/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule.mongo;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class MongoScheduleStoreTest {

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

        when(database.getCollection("eddi_schedules")).thenReturn(scheduleCollection);
        when(database.getCollection("eddi_schedule_fire_logs")).thenReturn(fireLogCollection);

        store = new MongoScheduleStore(database, jsonSerialization, documentBuilder, 100);
    }

    // ==================== createSchedule ====================

    @Test
    @DisplayName("createSchedule — stores and returns generated ID")
    void createSchedule() throws Exception {
        ScheduleConfiguration config = new ScheduleConfiguration();
        config.setName("test-schedule");
        config.setAgentId("agent1");

        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(jsonSerialization.deserialize(anyString(), eq(Document.class))).thenReturn(new Document());

        String id = store.createSchedule(config);
        assertNotNull(id);
        assertNotNull(config.getId());
        assertNotNull(config.getCreatedAt());
        assertNotNull(config.getUpdatedAt());
        verify(scheduleCollection).insertOne(any(Document.class));
    }

    @Test
    @DisplayName("createSchedule — wraps exception in ResourceStoreException")
    void createScheduleError() throws Exception {
        ScheduleConfiguration config = new ScheduleConfiguration();
        when(jsonSerialization.serialize(any())).thenThrow(new IOException("serialize fail"));

        assertThrows(IResourceStore.ResourceStoreException.class, () -> store.createSchedule(config));
    }

    // ==================== readSchedule ====================

    @Test
    @DisplayName("readSchedule — returns config when found")
    void readScheduleFound() throws Exception {
        Document doc = new Document("_id", "sched-1").append("id", "sched-1");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(scheduleCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        ScheduleConfiguration expected = new ScheduleConfiguration();
        when(documentBuilder.build(any(Document.class), eq(ScheduleConfiguration.class))).thenReturn(expected);

        ScheduleConfiguration result = store.readSchedule("sched-1");
        assertSame(expected, result);
    }

    @Test
    @DisplayName("readSchedule — throws ResourceNotFoundException when not found")
    void readScheduleNotFound() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(scheduleCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.readSchedule("missing"));
    }

    // ==================== updateSchedule ====================

    @Test
    @DisplayName("updateSchedule — updates existing schedule")
    void updateSchedule() throws Exception {
        ScheduleConfiguration config = new ScheduleConfiguration();

        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(jsonSerialization.deserialize(anyString(), eq(Document.class))).thenReturn(new Document());

        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(scheduleCollection.replaceOne(any(Bson.class), any(Document.class))).thenReturn(updateResult);

        assertDoesNotThrow(() -> store.updateSchedule("sched-1", config));
    }

    @Test
    @DisplayName("updateSchedule — throws ResourceNotFoundException when no match")
    void updateScheduleNotFound() throws Exception {
        ScheduleConfiguration config = new ScheduleConfiguration();

        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(jsonSerialization.deserialize(anyString(), eq(Document.class))).thenReturn(new Document());

        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(0L);
        when(scheduleCollection.replaceOne(any(Bson.class), any(Document.class))).thenReturn(updateResult);

        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.updateSchedule("missing", config));
    }

    // ==================== setScheduleEnabled ====================

    @Test
    @DisplayName("setScheduleEnabled — enables with nextFire")
    void setScheduleEnabledTrue() throws Exception {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        assertDoesNotThrow(() -> store.setScheduleEnabled("sched-1", true, Instant.now().plusSeconds(60)));
    }

    @Test
    @DisplayName("setScheduleEnabled — disables without nextFire")
    void setScheduleEnabledFalse() throws Exception {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        assertDoesNotThrow(() -> store.setScheduleEnabled("sched-1", false, null));
    }

    @Test
    @DisplayName("setScheduleEnabled — throws when not found")
    void setScheduleEnabledNotFound() {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(0L);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> store.setScheduleEnabled("missing", true, Instant.now()));
    }

    // ==================== deleteSchedule ====================

    @Test
    @DisplayName("deleteSchedule — deletes by id")
    void deleteSchedule() throws Exception {
        assertDoesNotThrow(() -> store.deleteSchedule("sched-1"));
        verify(scheduleCollection).deleteOne(any(Bson.class));
    }

    // ==================== deleteSchedulesByAgentId ====================

    @Test
    @DisplayName("deleteSchedulesByAgentId — returns deleted count")
    void deleteSchedulesByAgentId() throws Exception {
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(3L);
        when(scheduleCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        int count = store.deleteSchedulesByAgentId("agent1");
        assertEquals(3, count);
    }

    // ==================== readAllSchedules ====================

    @Test
    @DisplayName("readAllSchedules — returns list of configs")
    void readAllSchedules() throws Exception {
        setupScheduleIteration();

        List<ScheduleConfiguration> result = store.readAllSchedules(100);
        assertEquals(1, result.size());
    }

    // ==================== readSchedulesByAgentId ====================

    @Test
    @DisplayName("readSchedulesByAgentId — returns configs for agent")
    void readSchedulesByAgentId() throws Exception {
        setupScheduleIteration();

        List<ScheduleConfiguration> result = store.readSchedulesByAgentId("agent1");
        assertEquals(1, result.size());
    }

    // ==================== findDueSchedules ====================

    @Test
    @DisplayName("findDueSchedules — returns due schedules")
    void findDueSchedules() throws Exception {
        setupScheduleIteration();

        List<ScheduleConfiguration> result = store.findDueSchedules(Instant.now(), Instant.now().minusSeconds(60), 3);
        assertEquals(1, result.size());
    }

    // ==================== tryClaim ====================

    @Test
    @DisplayName("tryClaim — returns true when claimed")
    void tryClaimSuccess() throws Exception {
        Document result = new Document("_id", "sched-1");
        when(scheduleCollection.findOneAndUpdate(any(Bson.class), any(Bson.class))).thenReturn(result);

        assertTrue(store.tryClaim("sched-1", "instance-1", Instant.now()));
    }

    @Test
    @DisplayName("tryClaim — returns false when already claimed")
    void tryClaimFail() throws Exception {
        when(scheduleCollection.findOneAndUpdate(any(Bson.class), any(Bson.class))).thenReturn(null);

        assertFalse(store.tryClaim("sched-1", "instance-1", Instant.now()));
    }

    // ==================== markCompleted ====================

    @Test
    @DisplayName("markCompleted — with nextFire sets PENDING")
    void markCompletedWithNextFire() throws Exception {
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));
        assertDoesNotThrow(() -> store.markCompleted("sched-1", Instant.now().plusSeconds(3600)));
        verify(scheduleCollection).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    @DisplayName("markCompleted — null nextFire disables one-shot")
    void markCompletedOneShot() throws Exception {
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));
        assertDoesNotThrow(() -> store.markCompleted("sched-1", null));
    }

    // ==================== markFailed ====================

    @Test
    @DisplayName("markFailed — increments fail count")
    void markFailed() throws Exception {
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));
        assertDoesNotThrow(() -> store.markFailed("sched-1", Instant.now().plusSeconds(30)));
    }

    // ==================== markDeadLettered ====================

    @Test
    @DisplayName("markDeadLettered — sets DEAD_LETTERED status")
    void markDeadLettered() throws Exception {
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));
        assertDoesNotThrow(() -> store.markDeadLettered("sched-1"));
    }

    // ==================== requeueDeadLetter ====================

    @Test
    @DisplayName("requeueDeadLetter — succeeds when found in DEAD_LETTERED state")
    void requeueDeadLetterSuccess() throws Exception {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        assertDoesNotThrow(() -> store.requeueDeadLetter("sched-1"));
    }

    @Test
    @DisplayName("requeueDeadLetter — throws when not found")
    void requeueDeadLetterNotFound() {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(0L);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.requeueDeadLetter("missing"));
    }

    // ==================== logFire ====================

    @Test
    @DisplayName("logFire — inserts fire log document")
    void logFire() throws Exception {
        ScheduleFireLog fireLog = new ScheduleFireLog("log-1", "sched-1", "fire-1",
                Instant.now(), Instant.now(), Instant.now(), "COMPLETED", "inst-1", "conv-1", null, 1, 0.5);

        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(jsonSerialization.deserialize(anyString(), eq(Document.class))).thenReturn(new Document());

        assertDoesNotThrow(() -> store.logFire(fireLog));
        verify(fireLogCollection).insertOne(any(Document.class));
    }

    // ==================== readFireLogs ====================

    @Test
    @DisplayName("readFireLogs — returns logs for schedule")
    void readFireLogs() throws Exception {
        Document doc = new Document("_id", "log-1").append("id", "log-1");
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(fireLogCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any(Document.class))).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);

        ScheduleFireLog fireLog = new ScheduleFireLog("log-1", "sched-1", "fire-1",
                Instant.now(), Instant.now(), Instant.now(), "COMPLETED", "inst-1", "conv-1", null, 1, 0.5);
        when(documentBuilder.build(any(Document.class), eq(ScheduleFireLog.class))).thenReturn(fireLog);

        List<ScheduleFireLog> result = store.readFireLogs("sched-1", 10);
        assertEquals(1, result.size());
    }

    // ==================== readFailedFireLogs ====================

    @Test
    @DisplayName("readFailedFireLogs — returns failed and dead-lettered logs")
    void readFailedFireLogs() throws Exception {
        Document doc = new Document("_id", "log-1");
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(fireLogCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any(Document.class))).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);

        ScheduleFireLog fireLog = new ScheduleFireLog("log-1", "sched-1", "fire-1",
                Instant.now(), Instant.now(), null, "FAILED", "inst-1", null, "error", 1, 0.0);
        when(documentBuilder.build(any(Document.class), eq(ScheduleFireLog.class))).thenReturn(fireLog);

        List<ScheduleFireLog> result = store.readFailedFireLogs(50);
        assertEquals(1, result.size());
    }

    // ==================== Helper ====================

    private void setupScheduleIteration() throws Exception {
        Document doc = new Document("_id", "sched-1");
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(scheduleCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);

        ScheduleConfiguration config = new ScheduleConfiguration();
        when(documentBuilder.build(any(Document.class), eq(ScheduleConfiguration.class))).thenReturn(config);
    }
}
