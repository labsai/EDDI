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
import ai.labs.eddi.engine.memory.model.Data;
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

import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The tool ceiling of an email sender role (email plan v3 §9) through the real
 * conversation-memory stores. The empty ceiling is the one that matters: it
 * means "no tool at all", and a store that read it back as absent would hand an
 * unauthenticated sender's conversation every tool on its next turn.
 */
@DisplayName("Tool ceiling — store round trip (MongoDB and PostgreSQL)")
class ToolCeilingStorePersistenceTest extends MongoTestBase {

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

    private static Set<String> roundTrip(IConversationMemoryStore store, Set<String> ceiling) throws Exception {
        var memory = new ConversationMemory("agent-ceiling", 1, "email-anon:1");
        memory.getCurrentStep().storeData(new Data<>("input", "hello"));
        memory.setToolCeiling(ceiling);
        String id = store.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));
        return ConversationMemoryUtilities.convertConversationMemorySnapshot(store.loadConversationMemorySnapshot(id)).getToolCeiling();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("an empty ceiling (no tools) loads back empty, not absent")
    void emptyCeiling(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        assertEquals(Set.of(), roundTrip(factory.get(), Set.of()), backend);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationStores")
    @DisplayName("a role's ceiling loads back with its names; no ceiling stays none")
    void namedAndNone(String backend, Supplier<IConversationMemoryStore> factory) throws Exception {
        IConversationMemoryStore store = factory.get();
        assertEquals(Set.of("kb_search", "lookup_order"), roundTrip(store, Set.of("kb_search", "lookup_order")), backend);
        assertNull(roundTrip(store, null), backend);
    }
}
