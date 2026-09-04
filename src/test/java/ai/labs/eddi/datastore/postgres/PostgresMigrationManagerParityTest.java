/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The same agent ZIP must import the same way on both backends.
 * <p>
 * {@code RestImportService.readResources} runs the three
 * {@code IDocumentMigration} transforms over every uploaded resource, and the
 * bean behind that interface is chosen by {@code eddi.datastore.type}.
 * {@link PostgresMigrationManager} used to answer {@code document -> null} for
 * all three on the grounds that "PostgreSQL starts with a clean schema" — true
 * of the startup sweep, false of the import path, where the legacy body then
 * reached the deserializer untouched. The two resulting failures were not
 * symmetrical, which is what made this hard to see:
 * <ul>
 * <li>a legacy output set <em>threw</em> (a bare string alternative has no type
 * id), was swallowed by the caller's catch and imported as a null config;</li>
 * <li>a legacy {@code targetServer} did <em>not</em> throw — it was discarded
 * as an unknown property, so the agent imported, deployed and ran with every
 * HTTP call missing its base URL.</li>
 * </ul>
 * These tests pin the transforms to the Postgres bean directly, so the
 * behaviour cannot regress to a no-op without a red test.
 */
@DisplayName("PostgresMigrationManager — import-path transform parity")
class PostgresMigrationManagerParityTest {

    private final PostgresMigrationManager postgres = new PostgresMigrationManager();

    /** Exactly how imported bodies are read: the shared, lenient recipe. */
    private static ObjectMapper productionMapper() {
        return SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
    }

    @Test
    @DisplayName("a legacy 'targetServer' is renamed, not silently dropped")
    void apiCallsTargetServerIsMigrated() throws Exception {
        var document = new Document("targetServer", "https://api.example.invalid");

        Document migrated = postgres.migrateApiCalls().migrate(document);

        assertNotNull(migrated, "a legacy httpcalls document must be reported as changed");
        assertEquals("https://api.example.invalid", migrated.get("targetServerUrl"));

        var config = productionMapper().readValue(migrated.toJson(), ApiCallsConfiguration.class);
        assertEquals("https://api.example.invalid", config.getTargetServerUrl(),
                "without the transform the agent imports and runs with a null base URL on every HTTP call");
    }

    @Test
    @DisplayName("the 'targetServerUri' spelling is migrated too")
    void apiCallsTargetServerUriIsMigrated() {
        var document = new Document("targetServerUri", "https://api.example.invalid");

        Document migrated = postgres.migrateApiCalls().migrate(document);

        assertNotNull(migrated);
        assertEquals("https://api.example.invalid", migrated.get("targetServerUrl"));
    }

    @Test
    @DisplayName("a bare-string output alternative is upgraded so the document still deserializes")
    @SuppressWarnings("unchecked")
    void outputStringAlternativeIsMigrated() throws Exception {
        // Mutable lists throughout: the transforms rewrite alternatives in place, and
        // an immutable list would make them fail into their catch and return null.
        var document = new Document("outputSet",
                new ArrayList<>(List.of(new Document("action", "greet").append("timesOccurred", 0).append("quickReplies", List.of())
                        .append("outputs", new ArrayList<>(List.of(
                                new Document("valueAlternatives", new ArrayList<Object>(List.of("Hello!")))))))));

        Document migrated = postgres.migrateOutput().migrate(document);

        assertNotNull(migrated, "a legacy output document must be reported as changed");
        var outputs = (List<Map<String, Object>>) ((List<Map<String, Object>>) migrated.get("outputSet")).getFirst().get("outputs");
        var alternatives = (List<Object>) outputs.getFirst().get("valueAlternatives");
        assertInstanceOf(TextOutputItem.class, alternatives.getFirst(),
                "an untyped string alternative must become a typed item — otherwise the read throws on a missing type id");

        var json = productionMapper().writeValueAsString(migrated);
        var config = productionMapper().readValue(json, OutputConfigurationSet.class);
        assertEquals(1, config.getOutputSet().size());
    }

    @Test
    @DisplayName("a legacy untyped property value is moved onto its typed field")
    @SuppressWarnings("unchecked")
    void propertySetterValueIsMigrated() {
        var document = new Document("setOnActions",
                new ArrayList<>(List.of(new Document("actions", List.of("*")).append("setProperties",
                        new ArrayList<>(List.of(new Document("name", "city").append("value", "Vienna")))))));

        Document migrated = postgres.migratePropertySetter().migrate(document);

        assertNotNull(migrated, "a legacy propertysetter document must be reported as changed");
        var setProperties = (List<Map<String, Object>>) ((List<Map<String, Object>>) migrated.get("setOnActions")).getFirst()
                .get("setProperties");
        assertEquals("Vienna", setProperties.getFirst().get("valueString"));
    }

    @Test
    @DisplayName("a document needing no change still reports 'unchanged'")
    void alreadyMigratedDocumentIsLeftAlone() {
        var document = new Document("targetServerUrl", "https://api.example.invalid");

        assertNull(postgres.migrateApiCalls().migrate(document),
                "returning the document would make RestImportService rewrite bodies it never had to touch");
    }
}
