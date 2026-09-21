/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.migration.MigrationLogStore;
import ai.labs.eddi.configs.migration.MigrationManager;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
 * behaviour cannot regress to a no-op without a red test, and
 * {@link #bothManagersRunTheSameTransforms()} compares the two beans against
 * each other so a divergent transform re-inlined into {@link MigrationManager}
 * is caught too — a per-backend test in isolation would stay green while the
 * backends drift.
 * <p>
 * {@link #noOpSweepStillCompletes()} covers the other half of the bean — the
 * startup sweep that legitimately stays a no-op — including the boot line that
 * used to assert the very belief this branch disproved.
 */
@DisplayName("PostgresMigrationManager — import-path transform parity")
class PostgresMigrationManagerParityTest {

    private final PostgresMigrationManager postgres = new PostgresMigrationManager();

    /** Everything the class under test logged during one test. */
    private final List<String> bootLog = new ArrayList<>();

    private Logger sweepLogger;
    private Handler logHandler;
    private Level previousLevel;
    private boolean previousUseParentHandlers;

    @BeforeEach
    void captureTheBootLog() {
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                bootLog.add(record.getMessage());
            }

            @Override
            public void flush() {
                // nothing is buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        // src/test/resources/logging.properties sets ai.labs.eddi.level=OFF, so without
        // raising this one logger the record never reaches a handler and the assertion
        // below would pass against an empty list for the wrong reason. Detaching the
        // parent handlers keeps the boot line out of the surefire output.
        sweepLogger = Logger.getLogger(PostgresMigrationManager.class.getName());
        previousLevel = sweepLogger.getLevel();
        previousUseParentHandlers = sweepLogger.getUseParentHandlers();
        sweepLogger.setLevel(Level.ALL);
        sweepLogger.setUseParentHandlers(false);
        sweepLogger.addHandler(logHandler);
    }

    @AfterEach
    void releaseTheBootLog() {
        sweepLogger.removeHandler(logHandler);
        sweepLogger.setLevel(previousLevel);
        sweepLogger.setUseParentHandlers(previousUseParentHandlers);
    }

    /** Exactly how imported bodies are read: the shared, lenient recipe. */
    private static ObjectMapper productionMapper() {
        return SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
    }

    /**
     * The MongoDB bean, built with mocked collaborators. Only the three transform
     * getters are exercised; they are pure functions that touch neither the
     * database nor the migration log.
     */
    private static MigrationManager mongoManager() {
        return new MigrationManager(mock(MongoDatabase.class), mock(MigrationLogStore.class), true);
    }

    private static Document legacyApiCalls() {
        return new Document("targetServer", "https://api.example.invalid");
    }

    private static Document legacyOutput() {
        // Mutable lists throughout: the transforms rewrite alternatives in place, and
        // an immutable list would make them fail into their catch and return null.
        return new Document("outputSet",
                new ArrayList<>(List.of(new Document("action", "greet").append("timesOccurred", 0).append("quickReplies", List.of())
                        .append("outputs", new ArrayList<>(List.of(
                                new Document("valueAlternatives", new ArrayList<Object>(List.of("Hello!")))))))));
    }

    private static Document legacyPropertySetter() {
        return new Document("setOnActions",
                new ArrayList<>(List.of(new Document("actions", List.of("*")).append("setProperties",
                        new ArrayList<>(List.of(new Document("name", "city").append("value", "Vienna")))))));
    }

    /**
     * The actual parity assertion this class is named for: the two
     * {@code IMigrationManager} beans, asked for the same transform, must answer
     * with one that does the same thing.
     * <p>
     * The per-backend tests below pin the Postgres bean against a
     * {@code document -> null} regression, but only in isolation — nothing in them
     * would notice a divergent transform being re-inlined into
     * {@link MigrationManager}, which is exactly the state this branch undid. Each
     * transform gets its own fresh fixture because they rewrite in place.
     */
    @Test
    @DisplayName("both backends answer with the same transform, so they cannot drift apart again")
    void bothManagersRunTheSameTransforms() {
        var mongo = mongoManager();

        Document mongoApiCalls = mongo.migrateApiCalls().migrate(legacyApiCalls());
        assertNotNull(mongoApiCalls, "fixture must exercise the transform on both sides");
        assertEquals(mongoApiCalls, postgres.migrateApiCalls().migrate(legacyApiCalls()),
                "the same uploaded httpcalls body must import identically on both backends");

        Document mongoOutput = mongo.migrateOutput().migrate(legacyOutput());
        assertNotNull(mongoOutput, "fixture must exercise the transform on both sides");
        assertEquals(mongoOutput, postgres.migrateOutput().migrate(legacyOutput()),
                "the same uploaded output body must import identically on both backends");

        Document mongoPropertySetter = mongo.migratePropertySetter().migrate(legacyPropertySetter());
        assertNotNull(mongoPropertySetter, "fixture must exercise the transform on both sides");
        assertEquals(mongoPropertySetter, postgres.migratePropertySetter().migrate(legacyPropertySetter()),
                "the same uploaded propertysetter body must import identically on both backends");
    }

    /**
     * The half that legitimately stays a no-op has two obligations, and
     * {@code startMigrationIfFirstTimeRun} is two statements long, so this pins
     * both of them.
     * <ol>
     * <li>The MongoDB implementation calls back only after its collection sweep
     * finishes; the Postgres bean has no sweep to run, but {@code onComplete} is
     * what releases startup, so skipping the work must not mean skipping the
     * signal. Returning without calling it would hang every PostgreSQL deployment
     * at boot with nothing in the log to explain it.</li>
     * <li>The one line it prints is the only thing an operator ever sees about
     * legacy handling on this backend, and it must not overstate what was skipped.
     * It used to read "no MongoDB <em>migrations</em> needed", which was true of
     * the sweep and false of the bean: the three document transforms run on every
     * uploaded ZIP whichever backend is configured — the third assertion
     * demonstrates that on the same object in the same test rather than asserting
     * it by hand. That wording is exactly the belief that produced this bug, and it
     * was in the log telling the operator the transforms did not exist while they
     * silently returned null. The claim has to be scoped to the sweep.</li>
     * </ol>
     */
    @Test
    @DisplayName("the no-op sweep signals completion exactly once and scopes its no-op claim to the sweep")
    void noOpSweepStillCompletes() {
        var completions = new AtomicInteger();

        postgres.startMigrationIfFirstTimeRun(completions::incrementAndGet);

        assertEquals(1, completions.get(), "startup waits on this callback — never calling it hangs the boot, and "
                + "calling it twice would run the post-migration startup work twice");

        assertEquals(1, bootLog.size(), "the skipped sweep announces itself once, so a PostgreSQL boot log says why "
                + "no collection was rewritten at startup; saw: " + bootLog);
        var announcement = bootLog.getFirst();
        assertTrue(announcement.contains("sweep"),
                "the no-op is the startup collection sweep and the line has to name it, otherwise the reader cannot "
                        + "tell which half was skipped; saw: " + announcement);
        assertFalse(announcement.contains("migrations needed"),
                "this line must not tell the operator that no MongoDB-era migration is needed on PostgreSQL — that "
                        + "sentence is the bug this branch fixed, and the next assertion shows it is false; saw: "
                        + announcement);
        assertNotNull(postgres.migrateApiCalls().migrate(legacyApiCalls()),
                "the premise of the assertion above: migrations demonstrably ARE needed on this backend, so a boot "
                        + "line claiming otherwise would be wrong, not merely differently worded");
    }

    @Test
    @DisplayName("a legacy 'targetServer' is renamed, not silently dropped")
    void apiCallsTargetServerIsMigrated() throws Exception {
        var document = legacyApiCalls();

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
        var document = legacyOutput();

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
        var document = legacyPropertySetter();

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
