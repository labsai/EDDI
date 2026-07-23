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

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the removal of the never-implemented parallel
 * tool-execution knobs {@code enableParallelExecution} and
 * {@code parallelExecutionTimeoutMs} on {@link LlmConfiguration.Task}.
 *
 * <p>
 * Neither field was ever read. Agent-mode tools are dispatched one at a time
 * through langchain4j's {@code ToolExecutor.execute(ToolExecutionRequest,
 * memoryId)}, which yields a {@code (name, jsonArguments)} pair; the
 * reflection-based machinery these flags were meant to switch on required an
 * {@code (instance, java.lang.reflect.Method, Object[])} triple that MCP, A2A
 * and dynamic tools cannot produce at all. They were therefore deleted along
 * with that machinery rather than wired.
 * </p>
 *
 * <p>
 * Deleting a persisted key is only safe because every mapper that reads an LLM
 * configuration is built from
 * {@link SerializationCustomizer#configureObjectMapper} with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}. The langchain configurations that
 * carry these keys are already in customers' MongoDB/Postgres instances and in
 * exported agent ZIPs — the Manager has been writing {@code
 * enableParallelExecution} from a checkbox since before the deletion — so if
 * that flag is ever flipped on, every one of them stops loading.
 * </p>
 *
 * <p>
 * <strong>This class is that tripwire.</strong> The deserialization tests fail
 * the moment {@code FAIL_ON_UNKNOWN_PROPERTIES} is enabled on the shared
 * recipe; {@link #rewriteDropsRemovedParallelKeys()} and
 * {@link #taskExposesNoParallelExecutionBeanProperty()} fail the moment either
 * field is reintroduced onto the POJO.
 * </p>
 *
 * <p>
 * Pure-unit (no Testcontainers): it mirrors the production mapper wiring rather
 * than booting a store, so it runs everywhere the store integration tests
 * cannot.
 * </p>
 */
class LlmConfigurationParallelExecutionLegacyFieldsTest {

    /**
     * A langchain.json exactly as the Manager's "Parallel Tool Execution" checkbox
     * has been writing it — both removed keys present, surrounded by live
     * tool-control fields that must keep populating.
     */
    private static final String LEGACY_JSON = """
            {
              "tasks": [ {
                "actions": [ "*" ],
                "id": "tool-task",
                "type": "openai",
                "enableToolCaching": true,
                "enableRateLimiting": true,
                "defaultRateLimit": 42,
                "toolRateLimits": { "calculator": 7 },
                "enableParallelExecution": true,
                "parallelExecutionTimeoutMs": 60000,
                "maxToolIterations": 5
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

        assertEquals("tool-task", task.getId(), "the removed keys must not disturb sibling fields");
        assertEquals("openai", task.getType());
        assertEquals(Boolean.TRUE, task.getEnableToolCaching());
        assertEquals(Boolean.TRUE, task.getEnableRateLimiting());
        assertEquals(42, task.getDefaultRateLimit());
        assertEquals(Map.of("calculator", 7), task.getToolRateLimits());
        assertEquals(5, task.getMaxToolIterations());
    }

    @Test
    @DisplayName("a stored config still carrying the parallel-execution keys loads through the JSON mapper")
    void storedConfigWithRemovedParallelKeysDeserializesViaJsonMapper() throws Exception {
        // Fails with UnrecognizedPropertyException if FAIL_ON_UNKNOWN_PROPERTIES is
        // ever enabled on SerializationCustomizer.configureObjectMapper.
        assertLiveFieldsSurvived(jsonMapper().readValue(LEGACY_JSON, LlmConfiguration.class));
    }

    @Test
    @DisplayName("a stored config still carrying the parallel-execution keys loads through the Mongo BSON mapper")
    void storedConfigWithRemovedParallelKeysDeserializesViaBsonMapper() throws Exception {
        // Round-trip the raw document (removed keys intact) into BSON first, so the
        // decoder sees exactly what an existing `llms` collection holds.
        Map<String, Object> rawDocument = new ObjectMapper().readValue(LEGACY_JSON, new TypeReference<>() {
        });
        byte[] bson = bsonMapper().writeValueAsBytes(rawDocument);

        assertLiveFieldsSurvived(bsonMapper().readValue(bson, LlmConfiguration.class));
    }

    @Test
    @DisplayName("re-saving a legacy config drops the removed keys instead of round-tripping them")
    void rewriteDropsRemovedParallelKeys() throws Exception {
        var mapper = jsonMapper();
        String rewritten = mapper.writeValueAsString(mapper.readValue(LEGACY_JSON, LlmConfiguration.class));

        assertFalse(rewritten.contains("enableParallelExecution"),
                "enableParallelExecution was deleted because nothing honoured it; a config rewrite must not resurrect it: " + rewritten);
        assertFalse(rewritten.contains("parallelExecutionTimeoutMs"),
                "parallelExecutionTimeoutMs was deleted because nothing honoured it; a config rewrite must not resurrect it: " + rewritten);

        // Guards against the assertions above passing for the trivial wrong reason
        // (an empty / structurally broken rewrite).
        assertLiveFieldsSurvived(mapper.readValue(rewritten, LlmConfiguration.class));
    }

    @Test
    @DisplayName("Task exposes no parallel-execution bean property to Jackson or the Manager")
    void taskExposesNoParallelExecutionBeanProperty() throws Exception {
        var names = Arrays.stream(Introspector.getBeanInfo(LlmConfiguration.Task.class).getPropertyDescriptors())
                .map(PropertyDescriptor::getName)
                .toList();

        assertFalse(names.contains("enableParallelExecution"),
                "a getter/setter pair is enough to put the knob back on the REST contract: " + names);
        assertFalse(names.contains("parallelExecutionTimeoutMs"),
                "a getter/setter pair is enough to put the knob back on the REST contract: " + names);

        // Non-vacuity guard: introspection really is seeing this class's properties.
        assertTrue(names.contains("defaultRateLimit"), "bean introspection must actually be resolving Task properties: " + names);
    }
}
