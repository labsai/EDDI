/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.postgres.PostgresConversationMemoryStore;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mandatory call's marker (reason code, approver rule, summary) must survive
 * every store and every read surface: the resume enforces the approver from the
 * stored batch, so a loader that dropped the marker would turn a consent
 * approval into an ordinary one.
 */
@DisplayName("Mandatory approval marker — store round trip (MongoDB and PostgreSQL)")
class MandatoryApprovalPersistenceTest extends MongoTestBase {

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

    private static ConversationMemory consentPause() {
        var memory = new ConversationMemory("64b7f0c2a1d3e4f5a6b7c8a0", 1, "64b7f0c2a1d3e4f5a6b7c8d9");
        memory.getCurrentStep().storeData(new Data<>("input", "send the report to the partner"));
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedAt(Instant.ofEpochSecond(1_700_000_000L));
        memory.setHitlPausedWorkflowId("wf-1");
        memory.setHitlPausedAbsoluteTaskIndex(1);
        memory.setHitlPauseType("TOOL_CALL");
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setCallId("call-1");
        call.setToolName("sendToPeer");
        call.setSource("a2a");
        call.setArgumentsRaw("{\"file\":\"report.pdf\"}");
        call.setArgumentsRedacted("{\"file\":\"report.pdf\"}");
        call.setGateReason("mandatory:file-egress");
        call.setMandatoryReasonCode("file-egress");
        call.setMandatoryApprover("CONVERSATION_USER");
        call.setMandatorySummary("Sends 1 file out of this deployment");
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setCalls(new ArrayList<>(List.of(call)));
        memory.setHitlPendingToolCalls(batch);
        return memory;
    }

    private static void assertMarker(String surface, PendingToolCallBatch batch) {
        var call = batch.getCalls().get(0);
        assertEquals("file-egress", call.getMandatoryReasonCode(), surface + ": reason code");
        assertEquals("CONVERSATION_USER", call.getMandatoryApprover(), surface + ": approver");
        assertEquals("Sends 1 file out of this deployment", call.getMandatorySummary(), surface + ": summary");
        assertTrue(call.isMandatory(), surface);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("the marker survives the store, the resume conversion and every approver read")
    void roundTrip(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        var store = factory.get();
        String conversationId = store.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(consentPause()));

        var loaded = store.loadConversationMemorySnapshot(conversationId);
        assertMarker(backend + " load", loaded.getHitlPendingToolCalls());
        // The resume runs on the memory converted from the snapshot and writes it back
        // when it restores a refused decision's pause.
        var resumed = ConversationMemoryUtilities.convertConversationMemorySnapshot(loaded);
        assertMarker(backend + " resume memory", resumed.getHitlPendingToolCalls());
        assertMarker(backend + " written back", ConversationMemoryUtilities.convertConversationMemory(resumed).getHitlPendingToolCalls());

        assertMarker(backend + " approval-status tail", store.loadConversationMemoryTail(conversationId).snapshot().getHitlPendingToolCalls());
        assertMarker(backend + " detail=full", ConversationMemoryUtilities
                .sanitizePendingToolCallsForApprover(store.loadConversationMemorySnapshotForApprover(conversationId)).getHitlPendingToolCalls());
        assertMarker(backend + " names-only projection",
                ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(loaded, false, false, null).getHitlPendingToolCalls());
    }
}
