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
import ai.labs.eddi.engine.hitl.review.HitlReviewDetails;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.security.EmailReplyReview;
import ai.labs.eddi.engine.security.TurnOrigin;
import ai.labs.eddi.integrations.email.reply.EmailReplyThread;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reply review through the real stores (email plan v3 §8.2; #1118: a HITL flag
 * a loader dropped passed every in-memory test). The pending review's draft
 * must load back for the approver and the decision, and the decided step's
 * outputs — which the email channel reads, on every send attempt — must keep
 * the decision and the approver's text, on MongoDB and PostgreSQL alike.
 */
@DisplayName("Email reply review — store round trip (MongoDB and PostgreSQL)")
class EmailReplyReviewPersistenceTest extends MongoTestBase {

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

    private static final String DRAFT = "Your refund is on its way.";

    /** A mail turn armed for reply review, paused on its review. */
    private static ConversationMemory pendingReview() {
        var memory = new ConversationMemory("agent-mail", 1, "email:int1:alice@example.com");
        memory.setOrigin(TurnOrigin.EMAIL);
        var step = memory.getCurrentStep();
        step.storeData(new Data<>("context:" + TurnOrigin.CONTEXT_KEY, new Context(Context.ContextType.string, TurnOrigin.EMAIL)));
        Context review = EmailReplyReview.contextValue("always", "The reply waits for review.");
        step.storeData(new Data<>(EmailReplyReview.STEP_KEY, review));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("mail", Map.of("messageId", "<m1@example.com>"));
        context.put(EmailReplyReview.CONTEXT_KEY, review.getValue());
        step.addConversationOutputMap("context", context);
        step.addConversationOutputList("output", List.of(DRAFT));
        step.storeData(new Data<>(EmailReplyReview.MARKER, EmailReplyReview.marker(DRAFT)));
        step.addConversationOutputString(EmailReplyReview.OUTPUT_KEY, EmailReplyReview.PENDING);
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedAt(Instant.ofEpochSecond(1_700_000_000L));
        memory.setHitlPausedWorkflowId("wf-2");
        memory.setHitlPausedAbsoluteTaskIndex(-1);
        memory.setHitlPauseType("RULE");
        return memory;
    }

    private static IConversationMemory load(IConversationMemoryStore store, String conversationId) throws Exception {
        return ConversationMemoryUtilities.convertConversationMemorySnapshot(store.loadConversationMemorySnapshot(conversationId));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("the pending review loads back armed, with its draft, for the approver and the decision")
    void pendingReviewLoadsBack(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        var store = factory.get();
        String conversationId = store.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(pendingReview()));

        IConversationMemory loaded = load(store, conversationId);
        assertTrue(EmailReplyReview.armed(loaded), backend + ": still armed after a reload");
        assertTrue(EmailReplyReview.pending(loaded), backend);
        assertEquals(DRAFT, EmailReplyReview.draftOf(loaded), backend + ": the decision compares against the stored draft");
        assertEquals("The reply waits for review.", EmailReplyReview.reasonOf(loaded), backend);

        var snapshot = store.loadConversationMemorySnapshot(conversationId);
        assertEquals(DRAFT, EmailReplyReview.draftIn(snapshot), backend + ": approval-status and the validator read it");
        Map<String, Object> details = new LinkedHashMap<>();
        HitlReviewDetails.forRule(details, snapshot, null);
        assertEquals(Boolean.TRUE, details.get("replyReview"), backend);
        @SuppressWarnings("unchecked")
        Map<String, Object> editable = (Map<String, Object>) details.get("editableAnswer");
        assertNotNull(editable, backend + ": editable on an agent without hitlConfig");
        assertEquals(DRAFT, editable.get("draft"), backend);

        var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(snapshot, true, false);
        var outputs = simple.getConversationOutputs();
        assertEquals(EmailReplyReview.State.PENDING, EmailReplyReview.stateOf(outputs.get(EmailReplyThread.mailStep(outputs, "<m1@example.com>"))),
                backend + ": the channel sends nothing while it waits");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("an edited decision survives the store: the channel reads approved and the approver's text")
    void decidedReviewLoadsBack(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        var store = factory.get();
        var memory = pendingReview();
        var step = memory.getCurrentStep();
        step.removeData(EmailReplyReview.MARKER);
        step.addConversationOutputString(EmailReplyReview.OUTPUT_KEY, EmailReplyReview.EDITED);
        step.addConversationOutputString(EmailReplyReview.TEXT_OUTPUT_KEY, "Your refund of 20 EUR left today.");
        step.storeData(new Data<>(EmailReplyReview.DECISION_KEY, Map.of("decision", EmailReplyReview.EDITED)));
        memory.setConversationState(ConversationState.READY);
        String conversationId = store.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));

        // The store's update path too: written over itself, then read as the channel
        // does.
        store.storeConversationMemorySnapshot(store.loadConversationMemorySnapshot(conversationId));
        var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(store.loadConversationMemorySnapshot(conversationId), true, false);
        var outputs = simple.getConversationOutputs();
        var mailStep = outputs.get(EmailReplyThread.mailStep(outputs, "<m1@example.com>"));
        assertEquals(EmailReplyReview.State.APPROVED, EmailReplyReview.stateOf(mailStep), backend);
        assertEquals("Your refund of 20 EUR left today.", EmailReplyReview.editedText(mailStep), backend);
        assertTrue(!EmailReplyReview.pending(load(store, conversationId)), backend + ": nothing waits any more");
    }
}
