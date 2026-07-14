/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the invariant the {@code String -> HitlTimeoutPolicy} enum refactor
 * relies on: the enum serializes to (and deserializes from) its {@code name()}
 * string, byte-identical to the pre-refactor String representation, across BOTH
 * the JSON mapper (Postgres JSONB + REST) and the BSON mapper (MongoDB) — on
 * BOTH HITL surfaces (regular {@link ConversationMemorySnapshot} and group
 * {@link GroupConversation}) — and that documents persisted BEFORE the refactor
 * (a bare JSON string) still deserialize into the enum.
 * <p>
 * Pure-unit (no Testcontainers): mirrors the production mapper config via
 * {@link SerializationCustomizer}, so it runs locally where the store
 * integration tests cannot.
 */
class HitlTimeoutPolicySerializationTest {

    /**
     * Matches the REST / Postgres-JSONB resource mapper (SerializationCustomizer).
     */
    private static ObjectMapper jsonMapper() {
        return SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
    }

    /**
     * Matches the MongoDB BSON mapper built in
     * PersistenceModule.buildMongoClientOptions().
     */
    private static ObjectMapper bsonMapper() {
        BsonFactory bsonFactory = new BsonFactory();
        bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);
        var mapper = new ObjectMapper(bsonFactory);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new SerializationCustomizer(false).customize(mapper);
        return mapper;
    }

    // ---- Regular surface: ConversationMemorySnapshot ----

    @ParameterizedTest
    @EnumSource(HitlTimeoutPolicy.class)
    void snapshotJsonSerializesEnumAsNameStringAndRoundTrips(HitlTimeoutPolicy policy) throws Exception {
        var mapper = jsonMapper();
        var snap = new ConversationMemorySnapshot();
        snap.setConversationId("c1");
        snap.setHitlTimeoutPolicy(policy);

        String json = mapper.writeValueAsString(snap);
        assertTrue(json.contains("\"hitlTimeoutPolicy\":\"" + policy.name() + "\""),
                "policy must serialize as its name string; got: " + json);

        var back = mapper.readValue(json, ConversationMemorySnapshot.class);
        assertEquals(policy, back.getHitlTimeoutPolicy());
    }

    @ParameterizedTest
    @EnumSource(HitlTimeoutPolicy.class)
    void snapshotBsonEncodesEnumAsStringAndRoundTrips(HitlTimeoutPolicy policy) throws Exception {
        var mapper = bsonMapper();
        var snap = new ConversationMemorySnapshot();
        // conversationId maps to the Mongo _id (ObjectId), which requires a valid
        // 24-hex id.
        snap.setConversationId("507f1f77bcf86cd799439011");
        snap.setHitlTimeoutPolicy(policy);

        byte[] bson = mapper.writeValueAsBytes(snap);
        var back = mapper.readValue(bson, ConversationMemorySnapshot.class);
        assertEquals(policy, back.getHitlTimeoutPolicy());

        JsonNode tree = mapper.readTree(bson);
        assertTrue(tree.get("hitlTimeoutPolicy").isTextual(),
                "BSON must encode policy as a string, not an ordinal/int");
        assertEquals(policy.name(), tree.get("hitlTimeoutPolicy").asText());
    }

    @Test
    void snapshotNullPolicyIsOmittedAndRoundTripsToNull() throws Exception {
        var mapper = jsonMapper();
        var snap = new ConversationMemorySnapshot();
        snap.setConversationId("c1");

        String json = mapper.writeValueAsString(snap);
        assertFalse(json.contains("hitlTimeoutPolicy"),
                "a null policy must be omitted (NON_NULL inclusion), matching the pre-refactor behavior: " + json);
        assertNull(mapper.readValue(json, ConversationMemorySnapshot.class).getHitlTimeoutPolicy());
    }

    @Test
    void snapshotDeserializesLegacyPreRefactorStringDocument() throws Exception {
        // A document persisted BEFORE the refactor stored the policy as a bare JSON
        // string.
        var mapper = jsonMapper();
        String legacy = "{\"conversationId\":\"c1\",\"hitlTimeoutPolicy\":\"AUTO_REJECT\",\"hitlApprovalTimeout\":\"PT30M\"}";
        var snap = mapper.readValue(legacy, ConversationMemorySnapshot.class);
        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, snap.getHitlTimeoutPolicy());
        assertEquals("PT30M", snap.getHitlApprovalTimeout());
    }

    // ---- Group surface: GroupConversation ----

    @ParameterizedTest
    @EnumSource(HitlTimeoutPolicy.class)
    void groupJsonSerializesEnumAsNameStringAndRoundTrips(HitlTimeoutPolicy policy) throws Exception {
        var mapper = jsonMapper();
        var gc = new GroupConversation();
        gc.setId("g1");
        gc.setHitlTimeoutPolicy(policy);

        String json = mapper.writeValueAsString(gc);
        assertTrue(json.contains("\"hitlTimeoutPolicy\":\"" + policy.name() + "\""),
                "group policy must serialize as its name string; got: " + json);

        var back = mapper.readValue(json, GroupConversation.class);
        assertEquals(policy, back.getHitlTimeoutPolicy());
    }

    @Test
    void groupDeserializesLegacyPreRefactorStringDocument() throws Exception {
        var mapper = jsonMapper();
        String legacy = "{\"id\":\"g1\",\"hitlTimeoutPolicy\":\"ABORT\"}";
        var gc = mapper.readValue(legacy, GroupConversation.class);
        assertEquals(HitlTimeoutPolicy.ABORT, gc.getHitlTimeoutPolicy());
    }
}
