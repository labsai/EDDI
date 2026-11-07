/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.postgres.PostgresConversationMemoryStore;
import ai.labs.eddi.datastore.postgres.PostgresScheduleStore;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.engine.continuation.ContinuationPolicy;
import ai.labs.eddi.engine.continuation.ConversationContinuations;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingContinuation;
import ai.labs.eddi.engine.memory.model.PendingContinuationCandidate;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.mongo.MongoScheduleStore;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.codecs.RawBsonDocumentCodec;
import org.bson.codecs.configuration.CodecRegistries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertConversationMemory;
import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertConversationMemorySnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The self-continuation outbox record (#1180) through the real stores — a real
 * MongoDB and a real PostgreSQL: written with the step on the insert, append
 * and full-replace paths, read back by the real loaders, found by the sweep's
 * indexed query, and marked or removed by the narrow updates the sweep, cancel
 * and end use. Plus the insert-if-absent both schedule stores now offer, which
 * is what makes a recovered arm idempotent.
 */
@DisplayName("Continuation outbox record — MongoDB and PostgreSQL")
class PendingContinuationStoreParityTest extends MongoTestBase {

    private static final ContinuationPolicy POLICY = new ContinuationPolicy(5, "carry on");

    static Stream<Arguments> conversationStores() {
        // The production codec setup (PersistenceModule): a BSON-aware mapper behind
        // the
        // JacksonProvider, without which the snapshot cannot be written at all.
        Supplier<IConversationMemoryStore> mongo = () -> new ConversationMemoryStore(getDatabase().withCodecRegistry(
                CodecRegistries.fromRegistries(getDatabase().getCodecRegistry(), CodecRegistries.fromCodecs(new RawBsonDocumentCodec()),
                        CodecRegistries.fromProviders(new JacksonProvider(bsonMapper())))));
        Supplier<IConversationMemoryStore> postgres = () -> new PostgresConversationMemoryStore(PostgresTestBase.createDataSourceInstance(),
                jsonSerialization);
        return Stream.of(Arguments.of("mongo", mongo), Arguments.of("postgres", postgres));
    }

    private static ObjectMapper bsonMapper() {
        BsonFactory bsonFactory = new BsonFactory();
        bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);
        var mapper = new ObjectMapper(bsonFactory);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new SerializationCustomizer(false).customize(mapper);
        return mapper;
    }

    static Stream<Arguments> scheduleStores() {
        Supplier<IScheduleStore> mongo = () -> new MongoScheduleStore(getDatabase(), jsonSerialization, documentBuilder, 100);
        Supplier<IScheduleStore> postgres = () -> new PostgresScheduleStore(PostgresTestBase.createDataSourceInstance(), jsonSerialization, 100);
        return Stream.of(Arguments.of("mongo", mongo), Arguments.of("postgres", postgres));
    }

    private final ConversationContinuations continuations = new ConversationContinuations(null, new SimpleMeterRegistry());

    /** A new conversation whose first turn decided continuation 1, stored. */
    private String storeDecidingTurn(IConversationMemoryStore store) throws Exception {
        var memory = new ConversationMemory(null, "agent-pc", 1, "user-pc");
        memory.setConversationState(ConversationState.READY);
        memory.setContinuationPolicy(POLICY);
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ACTIONS.key(), new ArrayList<>(List.of(IConversation.CONTINUE_CONVERSATION))));
        assertEquals(1, continuations.decide(memory, Environment.production));
        return store.storeConversationMemorySnapshot(convertConversationMemory(memory));
    }

    /**
     * Loads the conversation and commits one more turn on it, as a say turn does.
     */
    private void storeNextTurn(IConversationMemoryStore store, String conversationId, boolean continues) throws Exception {
        IConversationMemory memory = convertConversationMemorySnapshot(store.loadConversationMemorySnapshot(conversationId));
        memory.setContinuationPolicy(POLICY);
        ((ConversationMemory) memory).startNextStep();
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ACTIONS.key(),
                new ArrayList<>(List.of(continues ? IConversation.CONTINUE_CONVERSATION : "done"))));
        continuations.decide(memory, Environment.production);
        ConversationMemorySnapshot snapshot = convertConversationMemory(memory);
        assertTrue(snapshot.getPersistedStepCount() >= 0, "the next turn takes the append path");
        store.storeConversationMemorySnapshot(snapshot);
    }

    private static PendingContinuation stored(IConversationMemoryStore store, String conversationId) throws Exception {
        return store.loadConversationMemorySnapshot(conversationId).getPendingContinuation();
    }

    /**
     * The sweep's query for one conversation, waiting for the index the store's
     * first sweep read starts building.
     */
    private static PendingContinuationCandidate awaitCandidate(IConversationMemoryStore store, String conversationId) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            PendingContinuationCandidate found = candidate(store, conversationId);
            if (found != null) {
                return found;
            }
            Thread.sleep(200);
        }
        return null;
    }

    /** The sweep's query for one conversation, once. */
    private static PendingContinuationCandidate candidate(IConversationMemoryStore store, String conversationId) throws Exception {
        return store.findPendingContinuations(System.currentTimeMillis() + 1, 10_000).stream()
                .filter(c -> conversationId.equals(c.conversationId())).findFirst().orElse(null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("the record is stored with the deciding turn, read back in full, and the next turn replaces or removes it")
    void recordRoundTrips(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        IConversationMemoryStore store = factory.get();
        String id = storeDecidingTurn(store);

        PendingContinuation first = stored(store, id);
        assertNotNull(first, backend + ": the insert carries the record");
        assertEquals(1, first.getNumber());
        assertEquals(5, first.getMaxContinuations());
        assertEquals("carry on", first.getInput());
        assertEquals("production", first.getEnvironment());
        assertNotNull(first.getDecidedAt());

        storeNextTurn(store, id, true);
        PendingContinuation second = stored(store, id);
        assertNotNull(second, backend + ": an appended turn that decides again replaces the record");
        assertEquals(1, second.getNumber(), "continuation 1 has not run yet, so the next decision is 1 again");

        storeNextTurn(store, id, false);
        assertNull(stored(store, id), backend + ": an appended turn that does not continue removes it");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("the sweep's query lists an unhandled record; marking it takes it out, and only cancel or end removes it")
    void sweepQueryMarkAndClear(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        IConversationMemoryStore store = factory.get();
        String id = storeDecidingTurn(store);

        PendingContinuationCandidate found = awaitCandidate(store, id);
        assertNotNull(found, backend + ": the query lists the stored decision");
        assertEquals("agent-pc", found.agentId());
        assertEquals("user-pc", found.userId());
        assertEquals(ConversationState.READY, found.state());
        assertEquals(1, found.pending().getNumber());
        assertEquals("carry on", found.pending().getInput());

        // Records decided after the cutoff are not read.
        assertTrue(store.findPendingContinuations(found.pending().getDecidedAt(), 10_000).stream()
                .noneMatch(c -> id.equals(c.conversationId())), backend + ": the cutoff is exclusive and honoured");

        assertFalse(store.markPendingContinuationHandled(id, 2), backend + ": another number does not match");
        assertTrue(store.markPendingContinuationHandled(id, 1), backend + ": the record names continuation 1");
        assertTrue(store.markPendingContinuationHandled(id, 1), backend + ": a handled record still names it — what two sweeps see");
        assertNull(candidate(store, id), backend + ": a handled record is out of the query");
        PendingContinuation handled = stored(store, id);
        assertNotNull(handled, backend + ": marking keeps the record");
        assertNull(handled.getDecidedAt());

        assertFalse(store.clearPendingContinuation(id, null), backend + ": removing a handled record reports nothing pending");
        assertNull(stored(store, id), backend + ": but removes it");
        assertFalse(store.markPendingContinuationHandled(id, 1), backend + ": a removed record names nothing — what a cancel looks like");

        String other = storeDecidingTurn(store);
        assertFalse(store.clearPendingContinuation(other, 3), backend + ": a guarded clear of another number removes nothing");
        assertNotNull(stored(store, other));
        assertTrue(store.clearPendingContinuation(other, null), backend + ": removing an unhandled record reports it");
        assertNull(stored(store, other));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("a narrow update to the record moves neither the revision nor blocks the next turn's append")
    void narrowUpdatesDoNotConflictWithATurn(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        IConversationMemoryStore store = factory.get();
        String id = storeDecidingTurn(store);
        Long revision = store.getRevision(id);

        store.markPendingContinuationHandled(id, 1);
        assertEquals(revision, store.getRevision(id), backend + ": marking does not move the revision");

        storeNextTurn(store, id, false);
        assertNull(stored(store, id), backend + ": the turn committed over the marked record");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scheduleStores")
    @DisplayName("createScheduleIfAbsent writes a row once per id, whoever calls it")
    void createScheduleIfAbsent(String backend, Supplier<IScheduleStore> factory) throws Exception {
        IScheduleStore store = factory.get();
        String conversationId = UUID.randomUUID().toString();
        String id = ConversationContinuations.scheduleId(conversationId, 1);

        assertTrue(store.createScheduleIfAbsent(schedule(id, conversationId)), backend + ": the first writer writes");
        assertFalse(store.createScheduleIfAbsent(schedule(id, conversationId)), backend + ": the second finds it");
        assertEquals(1, store.countPendingSchedulesByName(ConversationContinuations.scheduleName(conversationId)));
        assertEquals(id, store.readSchedule(id).getId());

        store.deleteSchedule(id);
        assertTrue(store.createScheduleIfAbsent(schedule(id, conversationId)), backend + ": a deleted id can be written again");
        try {
            store.createScheduleIfAbsent(new ScheduleConfiguration());
            fail(backend + ": a schedule without an id is refused");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    private static ScheduleConfiguration schedule(String id, String conversationId) {
        Instant now = Instant.now();
        var schedule = new ScheduleConfiguration();
        schedule.setId(id);
        schedule.setName(ConversationContinuations.scheduleName(conversationId));
        schedule.setAgentId("agent-pc");
        schedule.setUserId("user-pc");
        schedule.setMessage("");
        schedule.setOneTimeAt(now.toString());
        schedule.setNextFire(now);
        schedule.setEnabled(true);
        schedule.setMetadata(ConversationContinuations.metadata(conversationId, 1, 5));
        return schedule;
    }
}
