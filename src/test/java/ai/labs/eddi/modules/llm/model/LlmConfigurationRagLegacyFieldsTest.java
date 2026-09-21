/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression guard for the removal of the never-implemented RAG knobs
 * {@code injectionStrategy} (on both {@code knowledgeBases[]} and
 * {@code ragDefaults}) and {@code contextTemplate} (on
 * {@code knowledgeBases[]}).
 *
 * <p>
 * Neither field was ever read by {@code RagContextProvider} or {@code LlmTask}
 * — retrieved context has always been appended to the system message
 * unconditionally — so they were deleted rather than wired. Deleting a
 * persisted key is only safe because every mapper that reads an LLM
 * configuration is built from
 * {@link SerializationCustomizer#configureObjectMapper} with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}. The langchain configurations that
 * carry these keys are already in customers' MongoDB/Postgres instances and in
 * exported agent ZIPs; if that flag is ever flipped on, every one of them stops
 * loading.
 * </p>
 *
 * <p>
 * <strong>This class is that tripwire.</strong> The deserialization tests fail
 * the moment {@code FAIL_ON_UNKNOWN_PROPERTIES} is enabled on the shared
 * recipe; {@link #rewriteDropsRemovedRagKeys()} fails the moment either field
 * is reintroduced onto the POJOs.
 * </p>
 *
 * <p>
 * Pure-unit (no Testcontainers): it mirrors the production mapper wiring rather
 * than booting a store, so it runs everywhere the store integration tests
 * cannot.
 * </p>
 */
class LlmConfigurationRagLegacyFieldsTest {

    /**
     * A langchain.json exactly as it may already sit in MongoDB / Postgres JSONB —
     * both RAG modes populated, and every removed key present.
     */
    private static final String LEGACY_JSON = """
            {
              "tasks": [ {
                "actions": [ "*" ],
                "id": "rag-task",
                "type": "openai",
                "knowledgeBases": [
                  {
                    "name": "product-docs",
                    "maxResults": 5,
                    "minScore": 0.7,
                    "injectionStrategy": "user_message",
                    "contextTemplate": "Context:\\n{{context}}"
                  }
                ],
                "enableWorkflowRag": true,
                "ragDefaults": {
                  "maxResults": 3,
                  "minScore": 0.5,
                  "injectionStrategy": "user_message"
                }
              } ]
            }
            """;

    /**
     * Matches the REST / Postgres-JSONB / {@code @PersistenceMapper} resource
     * mapper — all three are built from this same static recipe.
     */
    private static ObjectMapper jsonMapper() {
        return SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
    }

    /**
     * Matches the MongoDB BSON mapper built in
     * {@code PersistenceModule.buildMongoClientOptions()}.
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

    private static void assertLiveFieldsSurvived(LlmConfiguration config) {
        assertNotNull(config, "stored configuration must deserialize");
        assertEquals(1, config.tasks().size());
        var task = config.tasks().getFirst();

        assertEquals(1, task.getKnowledgeBases().size());
        var ref = task.getKnowledgeBases().getFirst();
        assertEquals("product-docs", ref.getName(), "the removed keys must not disturb sibling fields");
        assertEquals(5, ref.getMaxResults());
        assertEquals(0.7, ref.getMinScore());

        assertEquals(Boolean.TRUE, task.getEnableWorkflowRag());
        assertEquals(3, task.getRagDefaults().getMaxResults());
        assertEquals(0.5, task.getRagDefaults().getMinScore());
    }

    @Test
    @DisplayName("a stored config still carrying injectionStrategy/contextTemplate loads through the JSON mapper")
    void storedConfigWithRemovedRagKeysDeserializesViaJsonMapper() throws Exception {
        // Fails with UnrecognizedPropertyException if FAIL_ON_UNKNOWN_PROPERTIES is
        // ever enabled on SerializationCustomizer.configureObjectMapper.
        assertLiveFieldsSurvived(jsonMapper().readValue(LEGACY_JSON, LlmConfiguration.class));
    }

    @Test
    @DisplayName("a stored config still carrying injectionStrategy/contextTemplate loads through the Mongo BSON mapper")
    void storedConfigWithRemovedRagKeysDeserializesViaBsonMapper() throws Exception {
        // Round-trip the raw document (removed keys intact) into BSON first, so the
        // decoder sees exactly what an existing `llms` collection holds.
        Map<String, Object> rawDocument = new ObjectMapper().readValue(LEGACY_JSON, new TypeReference<>() {
        });
        byte[] bson = bsonMapper().writeValueAsBytes(rawDocument);

        assertLiveFieldsSurvived(bsonMapper().readValue(bson, LlmConfiguration.class));
    }

    @Test
    @DisplayName("re-saving a legacy config drops the removed keys instead of round-tripping them")
    void rewriteDropsRemovedRagKeys() throws Exception {
        var mapper = jsonMapper();
        String rewritten = mapper.writeValueAsString(mapper.readValue(LEGACY_JSON, LlmConfiguration.class));

        assertFalse(rewritten.contains("injectionStrategy"),
                "injectionStrategy was deleted because nothing honoured it; a config rewrite must not resurrect it: " + rewritten);
        assertFalse(rewritten.contains("contextTemplate"),
                "contextTemplate was deleted because nothing honoured it; a config rewrite must not resurrect it: " + rewritten);

        // Guards against the assertions above passing for the trivial wrong reason
        // (an empty / structurally broken rewrite).
        assertLiveFieldsSurvived(mapper.readValue(rewritten, LlmConfiguration.class));
    }
}
