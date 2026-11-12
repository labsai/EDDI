/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.postgres.PostgresConversationMemoryStore;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.engine.hitl.HitlPlanHash;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import org.bson.codecs.RawBsonDocumentCodec;
import org.bson.codecs.configuration.CodecRegistries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The plan hash of a pause through the real stores (#1118: a HITL flag a loader
 * dropped passed every in-memory test). A pause committed with a hash must load
 * back — on MongoDB and PostgreSQL alike — with the same hash, the same nonce,
 * and a plan that re-derives to the same value; the approver-facing tail read
 * must keep the hash and leave the nonce on the server; clearing the bookmark
 * must remove the hash.
 */
@DisplayName("Plan hash — store round trip (MongoDB and PostgreSQL)")
class HitlPlanHashPersistenceTest extends MongoTestBase {

    /** The BSON-backed mapper production's PersistenceModule gives the codec. */
    private static ObjectMapper bsonMapper() {
        BsonFactory bsonFactory = new BsonFactory();
        bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);
        var mapper = new ObjectMapper(bsonFactory);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new SerializationCustomizer(false).customize(mapper);
        return mapper;
    }

    static Stream<Arguments> conversationStores() {
        Supplier<IConversationMemoryStore> mongo = () -> new ConversationMemoryStore(getDatabase().withCodecRegistry(
                CodecRegistries.fromRegistries(getDatabase().getCodecRegistry(), CodecRegistries.fromCodecs(new RawBsonDocumentCodec()),
                        CodecRegistries.fromProviders(new JacksonProvider(bsonMapper())))));
        Supplier<IConversationMemoryStore> postgres = () -> new PostgresConversationMemoryStore(PostgresTestBase.createDataSourceInstance(),
                jsonSerialization);
        return Stream.of(Arguments.of("mongo", mongo), Arguments.of("postgres", postgres));
    }

    private static ConversationMemory toolPause() {
        var memory = new ConversationMemory("agent-plan", 2, "user-plan");
        memory.getCurrentStep().storeData(new Data<>("input", "pay the invoice"));
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        // Nanoseconds a store may drop: the plan uses the pause's millisecond.
        memory.setHitlPausedAt(Instant.ofEpochSecond(1_700_000_000L, 123_456_789));
        memory.setHitlPausedWorkflowId("wf-1");
        memory.setHitlPausedAbsoluteTaskIndex(3);
        memory.setHitlPauseType("TOOL_CALL");
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setCallId("call-1");
        call.setToolName("payInvoice");
        call.setSource("http");
        // Key order, a float with a trailing zero and an escaped character — the
        // stores may re-encode none of it, or all of it.
        call.setArgumentsRaw("{\"amount\":120.50,\"currency\":\"EUR\",\"payee\":{\"name\":\"Caf\\u00e9 Ltd\",\"iban\":\"AT61\"}}");
        call.setArgumentsRedacted(call.getArgumentsRaw());
        call.setRequestFingerprint("fp-internal");
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-plan");
        batch.setCalls(new ArrayList<>(List.of(call)));
        memory.setHitlPendingToolCalls(batch);
        return memory;
    }

    /**
     * Commits the plan hash the way a pause commit does — on the memory, which
     * carries the stored id — and writes the result back over the stored document.
     */
    private static String commitAndStore(IConversationMemoryStore store, String conversationId) throws Exception {
        var stored = store.loadConversationMemorySnapshot(conversationId);
        var memory = ConversationMemoryUtilities.convertConversationMemorySnapshot(stored);
        String committed = HitlPlanHash.commit(memory);
        stored.setHitlPlanHash(memory.getHitlPlanHash());
        if (stored.getHitlPendingToolCalls() != null) {
            stored.getHitlPendingToolCalls().setPlanNonce(memory.getHitlPendingToolCalls().getPlanNonce());
            stored.getHitlPendingToolCalls().setPlanHash(memory.getHitlPendingToolCalls().getPlanHash());
        }
        store.storeConversationMemorySnapshot(stored);
        return committed;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("a tool pause's hash, nonce and plan survive the store")
    void toolPauseRoundTrip(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        var store = factory.get();
        String conversationId = store.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(toolPause()));
        String committed = commitAndStore(store, conversationId);
        assertNotNull(committed);

        ConversationMemorySnapshot loaded = store.loadConversationMemorySnapshot(conversationId);
        assertEquals(committed, loaded.getHitlPlanHash(), backend + ": the conversation's plan hash");
        assertEquals(committed, loaded.getHitlPendingToolCalls().getPlanHash(), backend + ": the batch's plan hash");
        assertNotNull(loaded.getHitlPendingToolCalls().getPlanNonce(), backend + ": the nonce");
        assertEquals(committed, HitlPlanHash.forPause(ConversationMemoryUtilities.convertConversationMemorySnapshot(loaded)),
                backend + ": the loaded plan re-derives to the committed hash");
        // The resume runs on the memory converted from this snapshot and writes it back
        // when it restores a pause: a loader that dropped the hash there would make the
        // restored pause a legacy (unbound) one.
        var resumed = ConversationMemoryUtilities.convertConversationMemorySnapshot(loaded);
        assertEquals(committed, resumed.getHitlPlanHash(), backend + ": the resumed memory keeps the hash");
        assertEquals(committed, ConversationMemoryUtilities.convertConversationMemory(resumed).getHitlPlanHash(),
                backend + ": and writes it back");

        var tail = store.loadConversationMemoryTail(conversationId).snapshot();
        assertEquals(committed, tail.getHitlPlanHash(), backend + ": the approval-status read keeps the hash");
        assertNull(tail.getHitlPendingToolCalls().getPlanNonce(), backend + ": the tail read leaves the nonce on the server");

        var approver = ConversationMemoryUtilities
                .sanitizePendingToolCallsForApprover(store.loadConversationMemorySnapshotForApprover(conversationId));
        assertEquals(committed, approver.getHitlPlanHash(), backend + ": detail=full keeps the hash");
        assertNull(approver.getHitlPendingToolCalls().getPlanNonce(), backend + ": detail=full withholds the nonce");

        store.clearHitlBookmark(conversationId);
        assertNull(store.loadConversationMemorySnapshot(conversationId).getHitlPlanHash(), backend + ": clearing the bookmark drops the hash");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("a pause whose hash could not be computed keeps its UNAVAILABLE marker — it does not load as a legacy pause")
    void unavailableMarkerRoundTrip(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        var store = factory.get();
        var memory = toolPause();
        memory.setHitlPlanHash(HitlPlanHash.UNAVAILABLE);
        memory.getHitlPendingToolCalls().setPlanHash(HitlPlanHash.UNAVAILABLE);
        String conversationId = store.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));

        var loaded = store.loadConversationMemorySnapshot(conversationId);
        assertEquals(HitlPlanHash.UNAVAILABLE, loaded.getHitlPlanHash(), backend + ": the conversation's marker");
        assertEquals(HitlPlanHash.UNAVAILABLE, loaded.getHitlPendingToolCalls().getPlanHash(), backend + ": the batch's marker");
        assertEquals(HitlPlanHash.UNAVAILABLE, store.loadConversationMemoryTail(conversationId).snapshot().getHitlPlanHash(),
                backend + ": the approval-status read keeps the marker");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("a rule pause's hash survives the store and re-derives from the loaded actions")
    void rulePauseRoundTrip(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        var store = factory.get();
        var memory = new ConversationMemory("agent-plan", 2, "user-plan");
        memory.getCurrentStep().storeData(new Data<>("input", "delete my account"));
        memory.getCurrentStep().storeData(new Data<>(ACTIONS.key(), List.of("delete_account", "PAUSE_CONVERSATION")));
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedAt(Instant.ofEpochSecond(1_700_000_100L, 987_654_321));
        memory.setHitlPausedWorkflowId("wf-1");
        memory.setHitlPausedAbsoluteTaskIndex(1);
        memory.setHitlPauseType("RULE");
        String conversationId = store.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));
        String committed = commitAndStore(store, conversationId);

        var loaded = store.loadConversationMemorySnapshot(conversationId);
        assertEquals(committed, loaded.getHitlPlanHash(), backend);
        assertEquals(committed, HitlPlanHash.forPause(ConversationMemoryUtilities.convertConversationMemorySnapshot(loaded)), backend);
    }
}
