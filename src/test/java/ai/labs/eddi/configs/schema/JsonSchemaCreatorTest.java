package ai.labs.eddi.configs.schema;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.rules.model.BehaviorConfiguration;
import ai.labs.eddi.configs.apicalls.model.HttpCallsConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.dictionary.model.RegularDictionaryConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JsonSchemaCreator} — verifies that the victools-based schema
 * generator produces valid Draft 2020-12 JSON schemas for all EDDI configuration types.
 */
public class JsonSchemaCreatorTest {

    private JsonSchemaCreator schemaCreator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        schemaCreator = new JsonSchemaCreator(objectMapper);
    }

    static Stream<Class<?>> configurationClasses() {
        return Stream.of(
                AgentConfiguration.class,
                WorkflowConfiguration.class,
                BehaviorConfiguration.class,
                HttpCallsConfiguration.class,
                OutputConfigurationSet.class,
                RegularDictionaryConfiguration.class,
                PropertySetterConfiguration.class
        );
    }

    @ParameterizedTest(name = "generates valid schema for {0}")
    @MethodSource("configurationClasses")
    void generateSchema_producesValidSchema(Class<?> clazz) throws Exception {
        // Act
        String schemaJson = schemaCreator.generateSchema(clazz);

        // Assert — valid JSON
        assertNotNull(schemaJson);
        assertFalse(schemaJson.isBlank());

        JsonNode schema = objectMapper.readTree(schemaJson);

        // Must have $schema pointing to Draft 2020-12
        assertTrue(schema.has("$schema"),
                "Schema for " + clazz.getSimpleName() + " must have $schema");
        assertEquals("https://json-schema.org/draft/2020-12/schema",
                schema.get("$schema").asText());

        // Must declare type: "object"
        assertTrue(schema.has("type"),
                "Schema for " + clazz.getSimpleName() + " must have type");
        assertEquals("object", schema.get("type").asText());

        // Must have properties (even if empty, should be present)
        assertTrue(schema.has("properties"),
                "Schema for " + clazz.getSimpleName() + " must have properties");
        assertTrue(schema.get("properties").isObject());
    }

    @Test
    void generateSchema_AgentConfiguration_hasExpectedProperties() throws Exception {
        String schemaJson = schemaCreator.generateSchema(AgentConfiguration.class);
        JsonNode schema = objectMapper.readTree(schemaJson);
        JsonNode properties = schema.get("properties");

        assertTrue(properties.has("workflows"),
                "AgentConfiguration schema must have 'workflows' property");
        assertTrue(properties.has("channels"),
                "AgentConfiguration schema must have 'channels' property");
    }

    @Test
    void generateSchema_dictionaryConfiguration_hasDescriptions() throws Exception {
        String schemaJson = schemaCreator.generateSchema(RegularDictionaryConfiguration.class);
        JsonNode schema = objectMapper.readTree(schemaJson);
        JsonNode properties = schema.get("properties");

        assertTrue(properties.has("words"),
                "Dictionary schema must have 'words' property");
        assertTrue(properties.has("phrases"),
                "Dictionary schema must have 'phrases' property");
        assertTrue(properties.has("regExs"),
                "Dictionary schema must have 'regExs' property");
    }

    @Test
    void generateSchema_behaviorConfiguration_hasExpectedProperties() throws Exception {
        String schemaJson = schemaCreator.generateSchema(BehaviorConfiguration.class);
        JsonNode schema = objectMapper.readTree(schemaJson);
        JsonNode properties = schema.get("properties");

        assertTrue(properties.has("behaviorGroups"),
                "BehaviorConfiguration schema must have 'behaviorGroups' property");
    }

    @Test
    void generateSchema_httpCallsConfiguration_hasExpectedProperties() throws Exception {
        String schemaJson = schemaCreator.generateSchema(HttpCallsConfiguration.class);
        JsonNode schema = objectMapper.readTree(schemaJson);
        JsonNode properties = schema.get("properties");

        assertTrue(properties.has("targetServerUrl"),
                "HttpCallsConfiguration schema must have 'targetServerUrl' property");
        assertTrue(properties.has("httpCalls"),
                "HttpCallsConfiguration schema must have 'httpCalls' property");
    }
}
