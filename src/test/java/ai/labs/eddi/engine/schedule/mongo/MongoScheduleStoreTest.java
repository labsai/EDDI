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
import com.mongodb.ReadPreference;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
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

        // The store keeps a primary-read view of the schedule collection for the two
        // reads the erasure guarantee rests on. Every test here runs against a single
        // logical node, so the two views are the same mock and existing stubs on
        // scheduleCollection keep applying to both. That they are DISTINCT views on a
        // replica set is what logFire_probesTheScheduleOnThePrimary pins.
        when(scheduleCollection.withReadPreference(any(ReadPreference.class))).thenReturn(scheduleCollection);

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
     * The field list is a {@code $set} of explicit values, so an absent triggerType
     * has to be written as BSON null. Calling {@code name()} unguarded would throw
     * an NPE from inside the update builder and fail the whole PUT; PostgreSQL
     * binds SQL NULL for the same case, and the two backends must agree.
     * <p>
     * Asserted on the ENCODED document rather than on {@code Bson.toString()}. The
     * builder renders as {@code Update{fieldName='triggerType', operator='$set',
     * value=CRON}}, so a substring check for "triggerType=CRON" is unconditionally
     * true and a production line that invented CRON for an absent trigger type —
     * exactly what this test exists to prevent — sailed through it. Both directions
     * are pinned here: null stays null, and a real value is still written.
     */
    @Test
    @DisplayName("updateSchedule — a null triggerType is written as BSON null, never as an invented default")
    void updateScheduleWithNullTriggerTypeWritesNull() throws Exception {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        ScheduleConfiguration config = new ScheduleConfiguration();
        config.setTriggerType(null);
        store.updateSchedule("sched-1", config);

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).updateOne(any(Bson.class), update.capture());
        BsonDocument set = encodedSet(update.getValue());
        assertTrue(set.containsKey("triggerType"), "the field must still be $set, so the column is cleared: " + set.toJson());
        assertTrue(set.get("triggerType").isNull(),
                "an absent trigger type must be written as BSON null, not as an invented default: " + set.toJson());

        clearInvocations(scheduleCollection);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);
        ScheduleConfiguration withType = new ScheduleConfiguration();
        withType.setTriggerType(ScheduleConfiguration.TriggerType.HEARTBEAT);
        store.updateSchedule("sched-1", withType);

        ArgumentCaptor<Bson> typed = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).updateOne(any(Bson.class), typed.capture());
        BsonDocument typedSet = encodedSet(typed.getValue());
        // Checked before reading the value: getString() on a null (or absent) field
        // throws BsonInvalidOperationException, and a regression that wrote null for
        // every trigger type would surface as that crash rather than as a stated
        // expectation — a test bug to whoever reads the CI mail, not a product bug.
        assertTrue(typedSet.isString("triggerType"),
                "a real trigger type must be written as a BSON string, not as null or another type: "
                        + typedSet.toJson());
        assertEquals("HEARTBEAT", typedSet.getString("triggerType").getValue(),
                "a real trigger type must still be written by name");
    }

    /**
     * The {@code $set} sub-document of an update, encoded exactly as the driver
     * encodes it before putting the command on the wire.
     */
    private static BsonDocument encodedSet(Bson update) {
        return update.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry()).getDocument("$set");
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
     * A configuration update must not touch the fire lifecycle at all.
     * <p>
     * {@code fireStatus} and {@code failCount} used to be written from the caller's
     * object (defaulting to PENDING when absent), which made every PUT a
     * read-modify-write over live state: the REST layer read PENDING, the poller
     * claimed the row, and this update then wrote PENDING back over the fresh
     * CLAIMED. Because {@code tryClaim} accepts any PENDING row, the next poll
     * started a duplicate fire into the very same persistent conversation. Carrying
     * the values over in the REST layer narrowed the window to milliseconds and did
     * nothing for a non-REST caller; the two fields belong to the claim/completion
     * methods.
     */
    @Test
    @DisplayName("updateSchedule — never writes fireStatus or failCount")
    void updateScheduleDoesNotTouchTheFireLifecycle() throws Exception {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        ScheduleConfiguration config = new ScheduleConfiguration();
        config.setName("edited");
        config.setFireStatus(FireStatus.PENDING);
        config.setFailCount(0);
        store.updateSchedule("sched-1", config);

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).updateOne(any(Bson.class), update.capture());
        String rendered = update.getValue().toString();
        assertFalse(rendered.contains("fireStatus"),
                "writing fireStatus from a PUT un-claims a running fire: " + rendered);
        assertFalse(rendered.contains("failCount"),
                "failCount is owned by markFailed/markCompleted, not by an edit: " + rendered);
        assertTrue(rendered.contains("nextFire"),
                "nextFire stays: an edited cron or interval legitimately re-arms the schedule");
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
     * <p>
     * Like the bulk cascades, the single delete runs the cascade TWICE and the
     * order is the point. This is the everyday path — {@code DELETE
     * /schedulestore/schedules/{id}}, the one an operator reaches for to remove a
     * schedule that is firing right now — so the window it has to close is the more
     * likely one, not the rarer: a fire log written by an executor mid-fire on
     * another instance, landing between the first cascade and the schedule delete,
     * would outlive its schedule with nothing left to find it by.
     */
    @Test
    @DisplayName("deleteSchedule — cascades the fire logs, before AND after")
    void deleteScheduleCascadesFireLogs() throws Exception {
        DeleteResult logResult = mock(DeleteResult.class);
        when(logResult.getDeletedCount()).thenReturn(4L);
        when(fireLogCollection.deleteMany(any(Bson.class))).thenReturn(logResult);
        when(scheduleCollection.deleteOne(any(Bson.class))).thenReturn(mock(DeleteResult.class));

        store.deleteSchedule("sched-1");

        ArgumentCaptor<Bson> logFilter = ArgumentCaptor.forClass(Bson.class);
        verify(fireLogCollection, times(2)).deleteMany(logFilter.capture());
        for (Bson filter : logFilter.getAllValues()) {
            String rendered = filter.toString();
            assertTrue(rendered.contains("scheduleId"), "the cascade must scope by scheduleId: " + rendered);
            assertTrue(rendered.contains("sched-1"), "the cascade must scope to the deleted schedule: " + rendered);
        }
        var inOrder = inOrder(fireLogCollection, scheduleCollection);
        inOrder.verify(fireLogCollection).deleteMany(any(Bson.class));
        inOrder.verify(scheduleCollection).deleteOne(any(Bson.class));
        inOrder.verify(fireLogCollection).deleteMany(any(Bson.class));
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
     * <p>
     * The cascade runs TWICE, and the order is the point. MongoDB cannot span the
     * two collections in one transaction, so a fire log written between the first
     * pass and the schedule delete — by an executor mid-fire on one of these
     * schedules — would outlive its schedule carrying the erased user's
     * conversationId. The second pass, over the same resolved ids and after the
     * schedules are gone, closes that window.
     */
    @Test
    @DisplayName("deleteSchedulesByUserId — cascades the fire logs of every matched schedule, before AND after")
    void deleteSchedulesByUserIdCascadesFireLogs() throws Exception {
        stubScheduleIdProjection("sched-1", "sched-2");
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(2L);
        when(scheduleCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        assertEquals(2, store.deleteSchedulesByUserId("user-1"));

        ArgumentCaptor<Bson> logFilter = ArgumentCaptor.forClass(Bson.class);
        verify(fireLogCollection, times(2)).deleteMany(logFilter.capture());
        for (Bson filter : logFilter.getAllValues()) {
            String rendered = filter.toString();
            assertTrue(rendered.contains("scheduleId"), "the cascade must scope by scheduleId: " + rendered);
            assertTrue(rendered.contains("sched-1") && rendered.contains("sched-2"),
                    "every matched schedule's logs must go, not just the first: " + rendered);
        }
        var inOrder = inOrder(fireLogCollection, scheduleCollection);
        inOrder.verify(fireLogCollection).deleteMany(any(Bson.class));
        inOrder.verify(scheduleCollection).deleteMany(any(Bson.class));
        inOrder.verify(fireLogCollection).deleteMany(any(Bson.class));
    }

    @Test
    @DisplayName("deleteSchedulesByAgentId — cascades the fire logs of every matched schedule, before AND after")
    void deleteSchedulesByAgentIdCascadesFireLogs() throws Exception {
        stubScheduleIdProjection("sched-7");
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(1L);
        when(scheduleCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        assertEquals(1, store.deleteSchedulesByAgentId("agent-1"));

        ArgumentCaptor<Bson> logFilter = ArgumentCaptor.forClass(Bson.class);
        verify(fireLogCollection, times(2)).deleteMany(logFilter.capture());
        assertTrue(logFilter.getAllValues().stream().allMatch(f -> f.toString().contains("sched-7")), logFilter.getAllValues().toString());
        var inOrder = inOrder(fireLogCollection, scheduleCollection);
        inOrder.verify(fireLogCollection).deleteMany(any(Bson.class));
        inOrder.verify(scheduleCollection).deleteMany(any(Bson.class));
        inOrder.verify(fireLogCollection).deleteMany(any(Bson.class));
    }

    @Test
    @DisplayName("deleteSchedulesByName — cascades the fire logs of every matched schedule, before AND after")
    void deleteSchedulesByNameCascadesFireLogs() throws Exception {
        stubScheduleIdProjection("sched-9");
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(1L);
        when(scheduleCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        assertEquals(1, store.deleteSchedulesByName("hitl-timeout-conv-1"));

        ArgumentCaptor<Bson> logFilter = ArgumentCaptor.forClass(Bson.class);
        verify(fireLogCollection, times(2)).deleteMany(logFilter.capture());
        assertTrue(logFilter.getAllValues().stream().allMatch(f -> f.toString().contains("sched-9")), logFilter.getAllValues().toString());
        var inOrder = inOrder(fireLogCollection, scheduleCollection);
        inOrder.verify(fireLogCollection).deleteMany(any(Bson.class));
        inOrder.verify(scheduleCollection).deleteMany(any(Bson.class));
        inOrder.verify(fireLogCollection).deleteMany(any(Bson.class));
    }

    /**
     * The bulk cascade's own failure must surface as a store exception rather than
     * a raw driver error, and it must never be mistaken for "nothing matched": a
     * caller that reads 0 back from a failed erasure records a compliance sweep
     * that did not happen.
     */
    @Test
    @DisplayName("deleteSchedulesByName — a driver failure becomes a ResourceStoreException")
    void deleteSchedulesByNameStoreFailureIsWrapped() {
        stubScheduleIdProjection("sched-9");
        when(scheduleCollection.deleteMany(any(Bson.class))).thenThrow(new IllegalStateException("cluster down"));

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.deleteSchedulesByName("hitl-timeout-conv-1"));
        assertTrue(thrown.getMessage().contains("hitl-timeout-conv-1"), thrown.getMessage());
    }

    /**
     * The SECOND cascade pass — the one that runs after the schedule row is gone —
     * must not fail quietly. Its whole purpose is to catch a fire log written
     * mid-delete, so a caller told the delete "succeeded" while the sweep threw
     * would believe the erasure complete when a log carrying a conversationId
     * outlived it.
     */
    @Test
    @DisplayName("deleteSchedule — a failing post-delete sweep is reported, not swallowed")
    void deleteScheduleReportsAFailingPostDeleteSweep() {
        DeleteResult firstPass = mock(DeleteResult.class);
        when(firstPass.getDeletedCount()).thenReturn(2L);
        when(fireLogCollection.deleteMany(any(Bson.class))).thenReturn(firstPass)
                .thenThrow(new IllegalStateException("cluster down"));
        when(scheduleCollection.deleteOne(any(Bson.class))).thenReturn(mock(DeleteResult.class));

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class, () -> store.deleteSchedule("sched-1"));
        assertTrue(thrown.getMessage().contains("sweep"),
                "the second pass must be distinguishable from the first in the error: " + thrown.getMessage());
    }

    /**
     * A cascade over a filter that matches no schedule must not issue an unscoped
     * {@code deleteMany} — an {@code $in} over an empty id list would be harmless,
     * but the guard against it is what keeps a future refactor from turning "no
     * matches" into "delete everything". That covers BOTH passes: the post-delete
     * sweep carries the same empty-id guard.
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
        when(cursor.hasNext()).thenReturn(hasNext[0], Arrays.copyOfRange(hasNext, 1, hasNext.length));
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

    /**
     * The retention sweep deletes on {@code startedAt} alone, and MongoDB cannot
     * use a compound index without its leading field — so neither
     * {@code (scheduleId, startedAt)} nor {@code (status, startedAt)} serves it.
     * Without a standalone index the hourly prune scans the whole fire-log
     * collection, which is exactly what it exists to keep bounded.
     */
    @Test
    @DisplayName("indexes — a standalone startedAt index backs the retention sweep")
    void fireLogIndexesIncludeAStandaloneStartedAt() {
        var options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(fireLogCollection, atLeastOnce()).createIndex(any(Bson.class), options.capture());
        assertTrue(options.getAllValues().stream().anyMatch(o -> "idx_fire_logs_startedAt".equals(o.getName())),
                "the prune has no index it can use: "
                        + options.getAllValues().stream().map(IndexOptions::getName).toList());
    }

    // ==================== readAllSchedules ====================

    @Test
    @DisplayName("readAllSchedules — returns list of configs")
    void readAllSchedules() throws Exception {
        setupScheduleIteration();

        List<ScheduleConfiguration> result = store.readAllSchedules(100);
        assertEquals(1, result.size());
    }

    /**
     * The HITL redaction belongs in the QUERY, not in a filter over the returned
     * page. Filtering afterwards counted limit/offset over rows a non-admin cannot
     * see, so an editor's first page could come back short — or empty — while later
     * pages held their own schedules, and a client obeying the documented "a full
     * page may be truncated" rule stopped paging and never saw them.
     * <p>
     * {@code $ne} also matches documents with no {@code metadata} at all, so only
     * an explicit {@code hitlType=hitl_timeout} is excluded.
     */
    @Test
    @DisplayName("readAllSchedules — excludes HITL timeouts in the filter, not after the page")
    void readAllSchedulesExcludingHitlTimeoutsFiltersInTheQuery() throws Exception {
        setupSchedulePageIteration();

        store.readAllSchedules(50, 0, true);

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).find(filter.capture());
        assertRedactsHitlTimeouts(filter.getValue());
    }

    @Test
    @DisplayName("readAllSchedules — an admin listing carries no redaction filter")
    void readAllSchedulesForAnAdminIsUnfiltered() throws Exception {
        setupSchedulePageIteration();

        store.readAllSchedules(50, 0, false);

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).find(filter.capture());
        assertFalse(filter.getValue().toString().contains("hitlType"),
                "an admin listing must not be filtered: " + filter.getValue());
    }

    // ==================== readSchedulesByAgentId ====================

    @Test
    @DisplayName("readSchedulesByAgentId — returns configs for agent")
    void readSchedulesByAgentId() throws Exception {
        setupScheduleIteration();

        List<ScheduleConfiguration> result = store.readSchedulesByAgentId("agent1");
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("readSchedulesByAgentId — ANDs the redaction onto the agent filter")
    void readSchedulesByAgentIdExcludingHitlTimeouts() throws Exception {
        setupSchedulePageIteration();

        store.readSchedulesByAgentId("agent1", 50, 0, true);

        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).find(filter.capture());
        BsonDocument rendered = encodedFilter(filter.getValue());
        assertEquals("agent1", clauseFor(rendered, "agentId").asString().getValue(),
                "the agent filter must survive the redaction: " + rendered.toJson());
        assertRedactsHitlTimeouts(filter.getValue());
    }

    /**
     * The redaction has to be the {@code $ne} clause, and the operator is the whole
     * behaviour: {@code $eq} on the same field and value renders with the same two
     * substrings a string-contains check looks for, but returns ONLY the HITL
     * timeouts to a non-admin — the exact inverse of the redaction. {@code $ne} is
     * also what makes documents carrying no {@code metadata} at all match, so only
     * an explicit {@code hitlType=hitl_timeout} is excluded.
     */
    private static void assertRedactsHitlTimeouts(Bson filter) {
        BsonDocument rendered = encodedFilter(filter);
        BsonValue marker = clauseFor(rendered, "metadata.hitlType");
        assertNotNull(marker, "the redaction must be part of the query filter: " + rendered.toJson());
        assertTrue(marker.isDocument() && marker.asDocument().containsKey("$ne"),
                "the redaction must EXCLUDE the marker with $ne — an equality match returns only the HITL timeouts: "
                        + rendered.toJson());
        assertEquals("hitl_timeout", marker.asDocument().getString("$ne").getValue(),
                "the redaction must name the marker value: " + rendered.toJson());
    }

    /** The filter as the driver encodes it before putting the query on the wire. */
    private static BsonDocument encodedFilter(Bson filter) {
        return filter.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    /**
     * The clause a rendered filter binds to {@code field}, whether the driver
     * flattened the AND into one document or fell back to an explicit {@code $and}
     * array. {@code null} when the field is not constrained at all.
     */
    private static BsonValue clauseFor(BsonDocument rendered, String field) {
        if (rendered.containsKey(field)) {
            return rendered.get(field);
        }
        if (rendered.containsKey("$and")) {
            for (BsonValue clause : rendered.getArray("$and")) {
                BsonValue found = clauseFor(clause.asDocument(), field);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** {@link #setupScheduleIteration} plus the sort/skip a paged read applies. */
    private void setupSchedulePageIteration() throws Exception {
        Document doc = new Document("_id", "sched-1");
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(scheduleCollection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any(Document.class))).thenReturn(iterable);
        when(iterable.skip(anyInt())).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);

        when(documentBuilder.build(any(Document.class), eq(ScheduleConfiguration.class))).thenReturn(new ScheduleConfiguration());
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

    /**
     * Lease stealing is deliberate — {@code tryClaim} reclaims a CLAIMED row whose
     * lease expired — so a fire that overran its lease and the replacement fire
     * that stole it can be in flight at once. An outcome write filtered on
     * {@code _id} alone lets the loser release or fail the WINNER's claim on its
     * way out: the document goes back to PENDING while a fire is still running, and
     * the next poll starts a third copy into the same conversation. Matching
     * {@code fireId} as well makes the stale write match no document, which is the
     * correct outcome.
     * <p>
     * All four transitions are covered together: fencing three and forgetting the
     * fourth only moves the damage.
     */
    @Test
    @DisplayName("mark* — every outcome write is fenced to the claim's fireId")
    void outcomeWritesAreFencedByTheClaimsFireId() throws Exception {
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));

        store.markCompleted("sched-1", "sched-1_fire-a", Instant.parse("2099-01-01T00:00:00Z"));
        store.markFailed("sched-1", "sched-1_fire-a", Instant.parse("2099-01-01T00:00:00Z"));
        store.markSkipped("sched-1", "sched-1_fire-a", Instant.parse("2099-01-01T00:00:00Z"));
        store.markDeadLettered("sched-1", "sched-1_fire-a");

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection, times(4)).updateOne(filter.capture(), any(Bson.class));
        for (Bson captured : filter.getAllValues()) {
            String rendered = captured.toString();
            assertTrue(rendered.contains("fireId") && rendered.contains("sched-1_fire-a"),
                    "a late fire must not be able to overwrite a newer claim by schedule id alone: " + rendered);
        }
    }

    /**
     * The unfenced overload exists for the one caller holding no claim —
     * {@code dismissDeadLetter}, where no fire is running by definition — and must
     * still match by id alone, or dismissing a dead letter would silently do
     * nothing.
     */
    @Test
    @DisplayName("mark* — an unfenced caller still matches by schedule id alone")
    void unfencedOutcomeWriteMatchesByScheduleIdAlone() throws Exception {
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));

        store.markCompleted("sched-1", Instant.parse("2099-01-01T00:00:00Z"));

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).updateOne(filter.capture(), any(Bson.class));
        assertFalse(filter.getValue().toString().contains("fireId"),
                "an unfenced write must not filter on a fireId it was not given: " + filter.getValue());
    }

    // ==================== markFailed ====================

    @Test
    @DisplayName("markFailed — increments fail count")
    void markFailed() throws Exception {
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));
        assertDoesNotThrow(() -> store.markFailed("sched-1", Instant.now().plusSeconds(30)));
    }

    // ==================== markSkipped ====================

    /**
     * A skipped fire releases the claim and re-arms the cadence — and touches
     * NOTHING else. The whole point of the method is what it does not write: not
     * {@code failCount} (incrementing it dead-letters a healthy heartbeat during a
     * human pause; clearing it lets a fail/skip/fail schedule dodge max-retries),
     * not {@code lastFired} (nothing fired), not {@code nextRetryAt} (the schedule
     * is not in retry).
     */
    @Test
    @DisplayName("markSkipped — re-arms and releases the claim without touching the retry state")
    void markSkippedLeavesTheRetryStateAlone() throws Exception {
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));

        store.markSkipped("sched-1", Instant.parse("2099-01-01T00:00:00Z"));

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(scheduleCollection).updateOne(any(Bson.class), update.capture());
        String rendered = update.getValue().toString();
        assertTrue(rendered.contains("nextFire"), "the cadence must be re-armed: " + rendered);
        assertTrue(rendered.contains("PENDING"), "the claim must be released: " + rendered);
        assertTrue(rendered.contains("claimedBy"), "the claim owner must be cleared: " + rendered);
        assertFalse(rendered.contains("failCount"), "a skip is neither a failure nor a success: " + rendered);
        assertFalse(rendered.contains("lastFired"), "nothing fired, so lastFired must not move: " + rendered);
        assertFalse(rendered.contains("nextRetryAt"), "a skip does not put the schedule into (or out of) retry: " + rendered);
    }

    /**
     * A failed re-arm must be reported. The poller logs it and moves on, but if
     * this threw a raw driver exception instead of a store exception the poller's
     * own {@code catch} would still swallow it while the schedule stayed CLAIMED
     * with a past nextFire — reclaimed on every lease expiry and never able to
     * dead-letter.
     */
    @Test
    @DisplayName("markSkipped — a driver failure becomes a ResourceStoreException naming the schedule")
    void markSkippedStoreFailureIsWrapped() {
        when(scheduleCollection.updateOne(any(Bson.class), any(Bson.class))).thenThrow(new IllegalStateException("cluster down"));

        var thrown = assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.markSkipped("sched-1", Instant.parse("2099-01-01T00:00:00Z")));
        assertTrue(thrown.getMessage().contains("sched-1"), thrown.getMessage());
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
        stubScheduleExists(true);

        assertDoesNotThrow(() -> store.logFire(fireLog));
        verify(fireLogCollection).insertOne(any(Document.class));
    }

    /**
     * The write-side half of the erasure guarantee, and the half that actually
     * closes the window.
     * <p>
     * The two cascade passes remove the fire logs that exist when they run, but
     * neither can stop a fire that is mid-flight at that moment from inserting its
     * log afterwards — and an erasure is exactly when a schedule is most likely to
     * be mid-fire. That late document carries the erased user's conversationId and
     * is findable only by a scheduleId that no longer resolves, so no erasure path
     * can ever reach it: a GDPR erasure would report success over personal data it
     * left behind.
     * <p>
     * MongoDB has no conditional insert, and a pre-check would only move the race
     * (the delete can land between the read and the insert). Verifying AFTER the
     * insert has no such gap: either the schedule is already gone when we look and
     * we remove our own document, or it is still there and the delete's own two
     * passes catch the document we have by then written.
     */
    @Test
    @DisplayName("logFire — removes the log again when the schedule was deleted mid-fire")
    void logFireRemovesTheLogWhenTheScheduleIsGone() throws Exception {
        ScheduleFireLog fireLog = new ScheduleFireLog("log-late", "sched-erased", "fire-1",
                Instant.now(), Instant.now(), Instant.now(), "COMPLETED", "inst-1", "conv-erased", null, 1, 0.5);

        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(jsonSerialization.deserialize(anyString(), eq(Document.class))).thenReturn(new Document());
        stubScheduleExists(false);

        assertDoesNotThrow(() -> store.logFire(fireLog));

        ArgumentCaptor<Bson> deleted = ArgumentCaptor.forClass(Bson.class);
        verify(fireLogCollection).deleteOne(deleted.capture());
        String filter = filterJson(deleted.getValue());
        assertTrue(filter.contains("_id") && filter.contains("log-late"),
                "the compensating delete must target the log this call just wrote: " + filter);
    }

    /**
     * The compensating delete must fire ONLY when the schedule is gone — otherwise
     * every fire would silently discard its own log.
     */
    @Test
    @DisplayName("logFire — keeps the log while its schedule still exists")
    void logFireKeepsTheLogWhenTheScheduleExists() throws Exception {
        ScheduleFireLog fireLog = new ScheduleFireLog("log-1", "sched-1", "fire-1",
                Instant.now(), Instant.now(), Instant.now(), "COMPLETED", "inst-1", "conv-1", null, 1, 0.5);

        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(jsonSerialization.deserialize(anyString(), eq(Document.class))).thenReturn(new Document());
        stubScheduleExists(true);

        store.logFire(fireLog);

        verify(fireLogCollection).insertOne(any(Document.class));
        verify(fireLogCollection, never()).deleteOne(any(Bson.class));
    }

    /**
     * The post-insert probe has to be answered by the PRIMARY, or the ordering
     * argument the whole guarantee rests on is not true.
     * <p>
     * {@code PersistenceModule} builds the client with
     * {@code ReadPreference.nearest()}, applied AFTER the connection string, so an
     * unqualified read from this collection can be served by a secondary that has
     * not yet replicated the delete. The probe would then see a schedule that is
     * already gone on the primary, skip the compensating delete, and leave a fire
     * log carrying the erased user's conversationId behind with nothing left to
     * find it by — a GDPR erasure reporting success over data it did not remove.
     * The Javadoc asserted the primary read; only the deployment happening to be a
     * standalone {@code mongod} made it so.
     * <p>
     * Modelled here as two distinct views of one collection that disagree: the
     * default (nearest) view still holds the schedule, the primary view knows it is
     * gone. The compensation must fire, which it can only do by reading the latter.
     */
    @Test
    @DisplayName("logFire — probes the schedule on the PRIMARY, not on a lagging replica")
    void logFire_probesTheScheduleOnThePrimary() throws Exception {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> nearestView = mock(MongoCollection.class);
        MongoCollection<Document> primaryView = mock(MongoCollection.class);
        MongoCollection<Document> fireLogs = mock(MongoCollection.class);

        when(database.getCollection("eddi_schedules")).thenReturn(nearestView);
        when(database.getCollection("eddi_schedule_fire_logs")).thenReturn(fireLogs);
        when(nearestView.withReadPreference(ReadPreference.primary())).thenReturn(primaryView);

        // The secondary is behind: it still reports the just-erased schedule.
        FindIterable<Document> stale = mock(FindIterable.class);
        when(stale.projection(any())).thenReturn(stale);
        when(stale.first()).thenReturn(new Document("_id", "sched-erased"));
        when(nearestView.find(any(Bson.class))).thenReturn(stale);

        // The primary has committed the delete.
        FindIterable<Document> current = mock(FindIterable.class);
        when(current.projection(any())).thenReturn(current);
        when(current.first()).thenReturn(null);
        when(primaryView.find(any(Bson.class))).thenReturn(current);

        when(fireLogs.deleteOne(any(Bson.class))).thenReturn(mock(DeleteResult.class));
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(jsonSerialization.deserialize(anyString(), eq(Document.class))).thenReturn(new Document());

        var replicaSetStore = new MongoScheduleStore(database, jsonSerialization, documentBuilder, 100);
        replicaSetStore.logFire(new ScheduleFireLog("log-late", "sched-erased", "fire-1",
                Instant.now(), Instant.now(), Instant.now(), "COMPLETED", "inst-1", "conv-erased", null, 1, 0.5));

        verify(primaryView).find(any(Bson.class));
        verify(nearestView, never()).find(any(Bson.class));
        verify(fireLogs).deleteOne(any(Bson.class));
    }

    /**
     * Same reasoning for the delete side. The ids resolved here are the input to
     * BOTH cascade passes, so a stale secondary omitting a schedule that the
     * primary's {@code deleteMany} then removes strands that schedule's fire logs
     * permanently — no later pass ever knows to look for them.
     */
    @Test
    @DisplayName("cascade — resolves the schedule ids on the PRIMARY, not on a lagging replica")
    void cascade_resolvesScheduleIdsOnThePrimary() throws Exception {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> nearestView = mock(MongoCollection.class);
        MongoCollection<Document> primaryView = mock(MongoCollection.class);
        MongoCollection<Document> fireLogs = mock(MongoCollection.class);

        when(database.getCollection("eddi_schedules")).thenReturn(nearestView);
        when(database.getCollection("eddi_schedule_fire_logs")).thenReturn(fireLogs);
        when(nearestView.withReadPreference(ReadPreference.primary())).thenReturn(primaryView);

        // The secondary has not replicated the newest schedule of this user yet.
        // Built before the when(...), never inside it: findingIds() stubs mocks of its
        // own, and Mockito reads that as an unfinished stubbing of the outer call.
        FindIterable<Document> staleIds = findingIds();
        FindIterable<Document> currentIds = findingIds("sched-fresh");
        when(nearestView.find(any(Bson.class))).thenReturn(staleIds);
        when(primaryView.find(any(Bson.class))).thenReturn(currentIds);

        DeleteResult deleted = mock(DeleteResult.class);
        when(deleted.getDeletedCount()).thenReturn(1L);
        when(nearestView.deleteMany(any(Bson.class))).thenReturn(deleted);
        when(fireLogs.deleteMany(any(Bson.class))).thenReturn(mock(DeleteResult.class));

        var replicaSetStore = new MongoScheduleStore(database, jsonSerialization, documentBuilder, 100);
        replicaSetStore.deleteSchedulesByUserId("user-erased");

        verify(primaryView).find(any(Bson.class));
        verify(nearestView, never()).find(any(Bson.class));

        // Both cascade passes must have seen the id the secondary was missing.
        ArgumentCaptor<Bson> logFilters = ArgumentCaptor.forClass(Bson.class);
        verify(fireLogs, times(2)).deleteMany(logFilters.capture());
        for (Bson filter : logFilters.getAllValues()) {
            assertTrue(filterJson(filter).contains("sched-fresh"),
                    "a schedule missing from the id resolution is stranded in both passes: " + filterJson(filter));
        }
    }

    /** A {@code find(...).projection(...)} that iterates the given schedule ids. */
    private static FindIterable<Document> findingIds(String... ids) {
        List<Document> docs = Arrays.stream(ids).map(id -> new Document("_id", id)).toList();
        // Built before the when(...) for the same reason as at the call site.
        MongoCursor<Document> cursor = cursorOver(docs);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(iterable.projection(any())).thenReturn(iterable);
        when(iterable.iterator()).thenReturn(cursor);
        return iterable;
    }

    /** A one-shot cursor over {@code docs}; Mockito's default is an empty one. */
    private static MongoCursor<Document> cursorOver(List<Document> docs) {
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        var it = docs.iterator();
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        when(cursor.next()).thenAnswer(inv -> it.next());
        return cursor;
    }

    /**
     * Make {@code logFire}'s schedule lookup report the schedule present or absent.
     * The permissive default in {@link #stubFireLogCascadeDefaults()} leaves
     * {@code first()} returning null, i.e. absent.
     */
    private void stubScheduleExists(boolean exists) {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(iterable.projection(any())).thenReturn(iterable);
        when(iterable.first()).thenReturn(exists ? new Document("_id", "sched-1") : null);
        when(scheduleCollection.find(any(Bson.class))).thenReturn(iterable);
    }

    /** Canonical JSON of a filter, so an assertion can read what it matched on. */
    private static String filterJson(Bson filter) {
        return filter.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
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
        var captor = ArgumentCaptor.forClass(Bson.class);
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
