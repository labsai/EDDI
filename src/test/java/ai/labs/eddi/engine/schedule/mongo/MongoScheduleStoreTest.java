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
import com.mongodb.MongoClientSettings;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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

        stubFireLogCascadeDefaults();

        store = new MongoScheduleStore(database, jsonSerialization, documentBuilder, 100);
    }

    /**
     * Every delete path now cascades to the schedule's fire logs — they are
     * unreachable once the schedule is gone, and each carries a conversationId.
     * These are permissive defaults so that tests about the schedule delete itself
     * do not have to care; tests that assert on the cascade re-stub them.
     */
    private void stubFireLogCascadeDefaults() {
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

    /**
     * {@code cronDescription} is computed for the API response, not state. The
     * field is marked {@code transient}, but that is a Java-serialization marker
     * and Jackson ignores it here (PROPAGATE_TRANSIENT_MARKER is off), so a PUT
     * that echoed a GET body persisted a stale description — which then read back
     * as though it had been configured. The store strips it explicitly.
     */
    @Test
    @DisplayName("createSchedule — never persists the computed cronDescription")
    void createScheduleStripsCronDescription() throws Exception {
        ScheduleConfiguration config = new ScheduleConfiguration();
        config.setName("test-schedule");
        config.setAgentId("agent1");

        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(jsonSerialization.deserialize(anyString(), eq(Document.class)))
                .thenReturn(new Document("name", "test-schedule").append("cronDescription", "Every day at 09:00"));

        store.createSchedule(config);

        ArgumentCaptor<Document> inserted = ArgumentCaptor.forClass(Document.class);
        verify(scheduleCollection).insertOne(inserted.capture());
        assertFalse(inserted.getValue().containsKey("cronDescription"),
                "a computed description must not be stored as state: " + inserted.getValue());
        assertEquals("test-schedule", inserted.getValue().getString("name"),
                "stripping the description must not take the rest of the document with it");
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

        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        assertDoesNotThrow(() -> store.updateSchedule("sched-1", config));
    }

    @Test
    @DisplayName("updateSchedule — throws ResourceNotFoundException when no match")
    void updateScheduleNotFound() throws Exception {
        ScheduleConfiguration config = new ScheduleConfiguration();

        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(0L);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.updateSchedule("missing", config));
    }

    /**
     * updateSchedule was {@code replaceOne(eq(_id), toDocument(schedule))} — a
     * whole-document replace built from the request body. A normal PUT (the shape
     * the Manager, curl and MCP all send) omits the read-only fields, so every edit
     * nulled createdAt/createdBy/lastFired and wiped the claim record
     * (claimedBy/claimedAt/fireId) of a fire that was still running, re-opening the
     * CAS claim mid-flight. PostgreSQL never behaved that way, so the two supported
     * backends disagreed about what an update means.
     */
    @Test
    @DisplayName("updateSchedule — $sets editable fields only, never provenance or claim state")
    void updateScheduleDoesNotTouchProvenanceOrClaimState() throws Exception {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        ScheduleConfiguration config = new ScheduleConfiguration();
        config.setName("edited");
        store.updateSchedule("sched-1", config);

        verify(scheduleCollection, never()).replaceOne(any(Bson.class), any(Document.class));

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).updateOne(any(Bson.class), update.capture());
        String rendered = update.getValue().toString();
        assertTrue(rendered.contains("name"), "the editable fields must still be written");
        for (String forbidden : List.of("createdAt", "createdBy", "lastFired", "claimedBy", "claimedAt", "fireId",
                "persistentConversationId")) {
            assertFalse(rendered.contains(forbidden), forbidden + " must not be written by an ordinary update");
        }
    }

    /**
     * {@code metadata} is now written as a plain {@code Map} through
     * {@code Updates.set} rather than as a {@code Document} produced by
     * {@code toDocument}. That only works because the driver's default codec
     * registry carries a {@code MapCodecProvider} — and a mocked collection never
     * encodes anything, so no other test in this class can tell.
     * <p>
     * Rendering the captured update through
     * {@code MongoClientSettings.getDefaultCodecRegistry()} is precisely the step
     * the driver performs before putting the command on the wire, so it fails here
     * for the same reason it would fail against a real server — and it exercises
     * the nested map and list shapes that HITL and cadence schedules actually
     * store.
     */
    @Test
    @DisplayName("updateSchedule — a nested metadata Map encodes with the driver's own codec registry")
    void updateScheduleEncodesTheMetadataMap() throws Exception {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        ScheduleConfiguration config = new ScheduleConfiguration();
        config.setName("edited");
        config.setMetadata(Map.of(
                "hitlType", "hitl_timeout",
                "nested", Map.of("policy", "AUTO_APPROVE", "attempts", 3),
                "surfaces", List.of("slack", "mcp")));

        store.updateSchedule("sched-1", config);

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).updateOne(any(Bson.class), update.capture());

        BsonDocument encoded = assertDoesNotThrow(
                () -> update.getValue().toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()),
                "the update must encode with the driver's default registry — a plain Map needs MapCodecProvider");
        BsonDocument metadata = encoded.getDocument("$set").getDocument("metadata");
        assertEquals("hitl_timeout", metadata.getString("hitlType").getValue());
        assertEquals("AUTO_APPROVE", metadata.getDocument("nested").getString("policy").getValue());
        assertEquals(3, metadata.getDocument("nested").getInt32("attempts").getValue());
        assertEquals(2, metadata.getArray("surfaces").size());
    }

    /**
     * The fire path records the persistent conversation with a single-field $set.
     * It used to call updateSchedule with the PRE-claim copy of the schedule, which
     * un-claimed the row while its own fire was still running: the next poll (15s
     * by default) claimed it again and pushed a second turn into that very same
     * conversation.
     */
    @Test
    @DisplayName("setPersistentConversationId — single-field $set, no claim state")
    void setPersistentConversationIdIsNarrow() throws Exception {
        store.setPersistentConversationId("sched-1", "conv-42");

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).updateOne(any(Bson.class), update.capture());
        String rendered = update.getValue().toString();
        assertTrue(rendered.contains("persistentConversationId"));
        assertTrue(rendered.contains("conv-42"));
        assertFalse(rendered.contains("fireStatus"), "must not touch the claim state mid-fire");
        assertFalse(rendered.contains("nextFire"), "must not touch the arming mid-fire");
    }

    /**
     * Enabling clears the failure state even when no nextFire could be computed.
     * Gating that reset on a non-null nextFire left a re-enabled schedule stuck in
     * FAILED/DEAD_LETTERED with a non-zero failCount, so it could never be claimed.
     */
    @Test
    @DisplayName("setScheduleEnabled — clears failure state even without a nextFire")
    void setScheduleEnabledWithoutNextFireStillResets() throws Exception {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        store.setScheduleEnabled("sched-1", true, null);

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).updateOne(any(Bson.class), update.capture());
        String rendered = update.getValue().toString();
        assertTrue(rendered.contains("fireStatus"));
        assertTrue(rendered.contains("PENDING"));
        assertTrue(rendered.contains("failCount"));
    }

    /**
     * Fire logs are unreachable once their schedule is gone, and each carries a
     * conversationId — leaving them behind orphans personal data that no erasure
     * path can find again.
     */
    @Test
    @DisplayName("deleteSchedule — cascades the fire logs")
    void deleteScheduleCascadesFireLogs() throws Exception {
        DeleteResult logResult = mock(DeleteResult.class);
        when(logResult.getDeletedCount()).thenReturn(4L);
        when(fireLogCollection.deleteMany(any(Bson.class))).thenReturn(logResult);
        when(scheduleCollection.deleteOne(any(Bson.class))).thenReturn(mock(DeleteResult.class));

        store.deleteSchedule("sched-1");

        verify(fireLogCollection).deleteMany(any(Bson.class));
        verify(scheduleCollection).deleteOne(any(Bson.class));
    }

    @Test
    @DisplayName("deleteFireLogsOlderThan — prunes by startedAt")
    void deleteFireLogsOlderThanPrunes() throws Exception {
        DeleteResult logResult = mock(DeleteResult.class);
        when(logResult.getDeletedCount()).thenReturn(7L);
        when(fireLogCollection.deleteMany(any(Bson.class))).thenReturn(logResult);

        assertEquals(7, store.deleteFireLogsOlderThan(Instant.now().minusSeconds(3600)));
    }

    /**
     * The bulk delete paths cascade too, and the GDPR one is the reason the cascade
     * exists: every fire log carries a conversationId of the user being erased, and
     * once the schedule row is gone nothing can find those logs again — an erasure
     * that reports success while leaving them behind is a compliance failure, not a
     * tidiness one.
     * <p>
     * The three pre-existing bulk-delete tests assert only the returned count and
     * were given permissive cascade stubs in {@code setUp}, so they are silent
     * about whether the cascade runs at all. These are what pin it.
     */
    @Test
    @DisplayName("deleteSchedulesByUserId — cascades the fire logs of every matched schedule")
    void deleteSchedulesByUserIdCascadesFireLogs() throws Exception {
        stubScheduleIdProjection("sched-1", "sched-2");
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(2L);
        when(scheduleCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        assertEquals(2, store.deleteSchedulesByUserId("user-1"));

        ArgumentCaptor<Bson> logFilter = ArgumentCaptor.forClass(Bson.class);
        verify(fireLogCollection).deleteMany(logFilter.capture());
        String rendered = logFilter.getValue().toString();
        assertTrue(rendered.contains("scheduleId"), "the cascade must scope by scheduleId: " + rendered);
        assertTrue(rendered.contains("sched-1") && rendered.contains("sched-2"),
                "every matched schedule's logs must go, not just the first: " + rendered);
    }

    @Test
    @DisplayName("deleteSchedulesByAgentId — cascades the fire logs of every matched schedule")
    void deleteSchedulesByAgentIdCascadesFireLogs() throws Exception {
        stubScheduleIdProjection("sched-7");
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(1L);
        when(scheduleCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        assertEquals(1, store.deleteSchedulesByAgentId("agent-1"));

        ArgumentCaptor<Bson> logFilter = ArgumentCaptor.forClass(Bson.class);
        verify(fireLogCollection).deleteMany(logFilter.capture());
        assertTrue(logFilter.getValue().toString().contains("sched-7"), logFilter.getValue().toString());
    }

    @Test
    @DisplayName("deleteSchedulesByName — cascades the fire logs of every matched schedule")
    void deleteSchedulesByNameCascadesFireLogs() throws Exception {
        stubScheduleIdProjection("sched-9");
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(1L);
        when(scheduleCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        assertEquals(1, store.deleteSchedulesByName("hitl-timeout-conv-1"));

        ArgumentCaptor<Bson> logFilter = ArgumentCaptor.forClass(Bson.class);
        verify(fireLogCollection).deleteMany(logFilter.capture());
        assertTrue(logFilter.getValue().toString().contains("sched-9"), logFilter.getValue().toString());
    }

    /**
     * A cascade over a filter that matches no schedule must not issue an unscoped
     * {@code deleteMany} — an {@code $in} over an empty id list would be harmless,
     * but the guard against it is what keeps a future refactor from turning "no
     * matches" into "delete everything".
     */
    @Test
    @DisplayName("bulk delete with no matching schedules touches no fire logs")
    void bulkDeleteWithNoMatchesDoesNotTouchFireLogs() throws Exception {
        stubScheduleIdProjection(); // no ids
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(0L);
        when(scheduleCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        assertEquals(0, store.deleteSchedulesByUserId("nobody"));

        verify(fireLogCollection, never()).deleteMany(any(Bson.class));
    }

    /**
     * Stub the id-only projection the cascade uses to resolve which schedules a
     * bulk filter matches.
     */
    private void stubScheduleIdProjection(String... scheduleIds) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(scheduleCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.projection(any())).thenReturn(iterable);
        doReturn(cursor).when(iterable).iterator();

        Boolean[] hasNext = new Boolean[scheduleIds.length + 1];
        for (int i = 0; i < scheduleIds.length; i++) {
            hasNext[i] = true;
        }
        hasNext[scheduleIds.length] = false;
        when(cursor.hasNext()).thenReturn(hasNext[0], java.util.Arrays.copyOfRange(hasNext, 1, hasNext.length));
        if (scheduleIds.length > 0) {
            Document[] rest = new Document[scheduleIds.length - 1];
            for (int i = 1; i < scheduleIds.length; i++) {
                rest[i - 1] = new Document("_id", scheduleIds[i]);
            }
            when(cursor.next()).thenReturn(new Document("_id", scheduleIds[0]), rest);
        }
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

        assertTrue(store.tryClaim("sched-1", "instance-1", Instant.now(), Instant.now().minusSeconds(300)));
    }

    @Test
    @DisplayName("tryClaim — returns false when already claimed")
    void tryClaimFail() throws Exception {
        when(scheduleCollection.findOneAndUpdate(any(Bson.class), any(Bson.class))).thenReturn(null);

        assertFalse(store.tryClaim("sched-1", "instance-1", Instant.now(), Instant.now().minusSeconds(300)));
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

    // ==================== G15: user-scoped erasure ====================

    /**
     * A schedule keeps the {@code userId} it fires conversations as. Left behind by
     * a GDPR erasure it goes on starting new conversations under the erased
     * identity — indefinitely recreating the data that was just removed.
     */
    @Test
    @DisplayName("deleteSchedulesByUserId — bulk-deletes on the userId field")
    void deleteSchedulesByUserId() throws Exception {
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(2L);
        when(scheduleCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        int count = store.deleteSchedulesByUserId("user-1");

        assertEquals(2, count);
        var captor = org.mockito.ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).deleteMany(captor.capture());
        assertTrue(captor.getValue().toString().contains("userId"),
                "the filter must scope the delete to the user: " + captor.getValue());
    }

    @Test
    @DisplayName("deleteSchedulesByUserId — a blank user deletes nothing")
    void deleteSchedulesByBlankUserIdDeletesNothing() throws Exception {
        assertEquals(0, store.deleteSchedulesByUserId(null));
        assertEquals(0, store.deleteSchedulesByUserId("  "));
        verify(scheduleCollection, never()).deleteMany(any(Bson.class));
    }
}
