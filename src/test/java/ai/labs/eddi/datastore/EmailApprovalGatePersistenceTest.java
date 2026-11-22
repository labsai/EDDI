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
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.engine.security.TurnOrigin;
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
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The email approval gate through the real stores (email plan v3 §8.1; #1118: a
 * HITL flag a loader dropped passed every in-memory test). A mail turn's gate
 * must load back from its step's context, the conversation's recorded gate must
 * survive next to {@code origin}, so a later turn without a gate of its own (a
 * continuation, a check-back) and a resumed pre-run pause still see it — on
 * MongoDB and PostgreSQL alike.
 */
@DisplayName("Email approval gate — store round trip (MongoDB and PostgreSQL)")
class EmailApprovalGatePersistenceTest extends MongoTestBase {

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

    /**
     * A mail conversation paused before its start: the step holds the gate (with
     * the held mail) and the channel's marker, the memory the recorded gate, the
     * bookmark the pre-run marker and the approval context with the mail.
     */
    private static ConversationMemory heldStart() {
        var memory = new ConversationMemory("agent-mail", 1, "email:int1:alice@example.com");
        memory.setOrigin(TurnOrigin.EMAIL);
        var step = memory.getCurrentStep();
        step.storeData(new Data<>("context:" + TurnOrigin.CONTEXT_KEY, new Context(Context.ContextType.string, TurnOrigin.EMAIL)));
        step.storeData(new Data<>(EmailApprovalGate.INTEGRATION_STEP_KEY, new Context(Context.ContextType.string, "int1")));
        Context gate = EmailApprovalGate.contextValue("everyTurn", List.of("http.get:*"), true, "Not by mail, sorry.", "A mail waits.",
                Map.of("from", "alice@example.com", "subject", "Refund order 7", "excerpt", "Please refund."));
        step.storeData(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY, gate));
        step.storeData(new Data<>(EmailApprovalGate.PRE_RUN_MARKER, EmailApprovalGate.PRE_RUN_START));
        EmailApprovalGate.record(memory, List.of(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY, gate)));
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedAt(Instant.ofEpochSecond(1_700_000_000L));
        memory.setHitlPausedWorkflowId("wf-1");
        memory.setHitlPausedAbsoluteTaskIndex(-1);
        memory.setHitlPauseType("RULE");
        memory.setHitlApprovalContext(RenderedApprovalContext.of(List.of(new RenderedApprovalContext.Item(EmailApprovalGate.HELD_MAIL_LABEL,
                "From: <alice@example.com>\nSubject: Refund order 7", false, EmailApprovalGate.HELD_MAIL_SOURCE, null, null)), false));
        return memory;
    }

    private static IConversationMemory load(IConversationMemoryStore store, String conversationId) throws Exception {
        return ConversationMemoryUtilities.convertConversationMemorySnapshot(store.loadConversationMemorySnapshot(conversationId));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("the resumed pre-run pause still sees its gate, its held mail and its start marker")
    void resumedPauseSeesGate(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        var store = factory.get();
        String conversationId = store.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(heldStart()));

        IConversationMemory loaded = load(store, conversationId);

        EmailApprovalGate.Policy gate = EmailApprovalGate.of(loaded);
        assertNotNull(gate, backend + ": the step's gate loads back");
        assertEquals("everyTurn", gate.policy(), backend);
        assertEquals(List.of("http.get:*"), gate.readOnlyTools(), backend);
        assertEquals("Not by mail, sorry.", gate.rejectedReply(), backend);
        assertNotNull(gate.mail(), backend + ": the held mail for the approver");
        assertEquals("Refund order 7", gate.mail().get("subject"), backend);
        assertEquals(EmailApprovalGate.PRE_RUN_START, String.valueOf(loaded.getCurrentStep().getData(EmailApprovalGate.PRE_RUN_MARKER).getResult()),
                backend + ": a rejected start still ends its conversation after a reload");
        assertEquals(EmailApprovalGate.HELD_MAIL_SOURCE, loaded.getHitlApprovalContext().getItems().get(0).getSource(),
                backend + ": the approver's view of the mail");
        var tail = store.loadConversationMemoryTail(conversationId).snapshot();
        assertTrue(tail.getHitlApprovalContext().getItems().get(0).getText().contains("Refund order 7"),
                backend + ": the approval-status read keeps it");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("the recorded gate survives next to origin, without preRun or the mail, and gates a later turn with no gate of its own")
    void recordedGateGatesLaterTurn(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        var store = factory.get();
        String conversationId = store.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(heldStart()));

        IConversationMemory loaded = load(store, conversationId);
        assertEquals(TurnOrigin.EMAIL, loaded.getOrigin(), backend);
        Map<String, Object> recorded = loaded.getEmailApprovalGate();
        assertNotNull(recorded, backend + ": the recorded gate");
        assertEquals("everyTurn", recorded.get("policy"), backend);
        assertNull(recorded.get("preRun"), backend + ": a per-turn flag is not recorded");
        assertNull(recorded.get("mail"), backend + ": nor the held mail");

        // A self-continuation: a new step, no gate in its context.
        if (loaded instanceof ConversationMemory memory) {
            memory.startNextStep();
        }
        EmailApprovalGate.Policy gate = EmailApprovalGate.of(loaded);
        assertNotNull(gate, backend + ": the continuation is gated by the recorded gate");
        assertEquals(List.of("http.get:*"), gate.readOnlyTools(), backend);
        assertTrue(EmailApprovalGate.refusesRuleCall(loaded, List.of("createOrder")), backend);
        assertNotNull(EmailApprovalGate.preRunRequested(loaded, List.of(), true), backend + ": and under everyTurn it waits");

        // The continuation's turn writes the memory back: the recorded gate goes with
        // it.
        assertEquals("everyTurn", ConversationMemoryUtilities.convertConversationMemory(loaded).getEmailApprovalGate().get("policy"), backend);
        // And an update of the stored document (the write path of every later turn)
        // keeps it: stored again over itself, then reloaded.
        var stored = store.loadConversationMemorySnapshot(conversationId);
        store.storeConversationMemorySnapshot(stored);
        var reloaded = store.loadConversationMemorySnapshot(conversationId);
        assertEquals("everyTurn", reloaded.getEmailApprovalGate().get("policy"), backend + ": an update keeps the recorded gate");
        assertEquals(List.of("http.get:*"), reloaded.getEmailApprovalGate().get("readOnlyTools"), backend);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("a mail conversation stored before the gate was recorded fails closed after a reload")
    void legacyMailConversationFailsClosed(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        var store = factory.get();
        var memory = new ConversationMemory("agent-mail", 1, "email:int1:alice@example.com");
        memory.setOrigin(TurnOrigin.EMAIL);
        memory.getCurrentStep().storeData(new Data<>(EmailApprovalGate.INTEGRATION_STEP_KEY, new Context(Context.ContextType.string, "int1")));
        memory.setConversationState(ConversationState.READY);
        String conversationId = store.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));

        IConversationMemory loaded = load(store, conversationId);
        if (loaded instanceof ConversationMemory reloaded) {
            reloaded.startNextStep();
        }

        EmailApprovalGate.Policy gate = EmailApprovalGate.of(loaded);
        assertNotNull(gate, backend);
        assertEquals("actions", gate.policy(), backend);
        assertTrue(gate.readOnlyTools().isEmpty(), backend);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("a released, unauthenticated held mail stays read-only-only after a reload, also for a later turn (v3 §7.3)")
    void readOnlyOnlySurvivesTheStores(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        var store = factory.get();
        var memory = new ConversationMemory("agent-mail", 1, "email-anon:abc");
        memory.setOrigin(TurnOrigin.EMAIL);
        var step = memory.getCurrentStep();
        step.storeData(new Data<>("context:" + TurnOrigin.CONTEXT_KEY, new Context(Context.ContextType.string, TurnOrigin.EMAIL)));
        step.storeData(new Data<>(EmailApprovalGate.INTEGRATION_STEP_KEY, new Context(Context.ContextType.string, "int1")));
        Context gate = EmailApprovalGate.contextValue("none", List.of("http.get:*"), false, null, null, null, true);
        step.storeData(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY, gate));
        EmailApprovalGate.record(memory, List.of(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY, gate)));
        memory.setConversationState(ConversationState.READY);
        String conversationId = store.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));

        IConversationMemory loaded = load(store, conversationId);
        assertTrue(EmailApprovalGate.of(loaded).readOnlyOnly(), backend + ": the mail's own step");
        if (loaded instanceof ConversationMemory reloaded) {
            reloaded.startNextStep();
        }
        EmailApprovalGate.Policy later = EmailApprovalGate.of(loaded);
        assertNotNull(later, backend);
        assertTrue(later.readOnlyOnly(), backend + ": a later turn of the conversation stays restricted");
        assertEquals(List.of("http.get:*"), later.readOnlyTools(), backend);
    }
}