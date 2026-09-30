/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.configs.migration.MigrationLogStore;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.utils.LogCaptureSupport.CapturedRecord;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ai.labs.eddi.utils.LogCaptureSupport.captureRecordsOf;
import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the Qute migration reports a template it cannot convert, across starts.
 *
 * <p>
 * Such a document is left unchanged and keeps the migration incomplete, so the
 * migration runs again on every start. It used to report the same document on
 * every start too, for as long as the legacy config existed. Now the first
 * start that finds it logs an ERROR, later starts list the ones already
 * reported in one WARN, and the record of what was reported lives in
 * {@code migrationlog}. Ids and values are synthetic.
 * </p>
 */
@DisplayName("V6QuteMigration — reporting unconvertible templates across starts")
class V6QuteMigrationReportingTest extends MongoTestBase {

    private static final ObjectId BROKEN_A = new ObjectId("0000000000000000000000e1");
    private static final ObjectId BROKEN_B = new ObjectId("0000000000000000000000e2");
    private static final ObjectId CLEAN = new ObjectId("0000000000000000000000e3");

    /** Builds template syntax — the shape the converter refuses. */
    private static final String UNCONVERTIBLE = "[['[[${'+'x.x'+'}]]']]";

    private static final String COMPLETE_KEY = "v6-qute-migration-complete";
    private static final String REPORTED_KEY = "v6-qute-migration-unconvertible";

    private MigrationLogStore migrationLog;

    /**
     * The real store, whose entries are under test; only the completion marker is
     * read and written as a plain document, because the test client has no codec
     * for {@link MigrationLog}.
     */
    private static final class PlainMigrationLogStore extends MigrationLogStore {
        private final MongoCollection<Document> raw;

        PlainMigrationLogStore(MongoDatabase database) {
            super(database);
            raw = database.getCollection("migrationlog");
        }

        @Override
        public MigrationLog readMigrationLog(String name) {
            return raw.find(eq("name", name)).first() == null ? null : new MigrationLog(name);
        }

        @Override
        public void createMigrationLog(MigrationLog migrationLog) {
            raw.insertOne(new Document("name", migrationLog.getName()).append("finished", true));
        }
    }

    @BeforeEach
    void clean() {
        dropCollections("apicalls", "apicalls.history", "migrationlog");
        migrationLog = new PlainMigrationLogStore(getDatabase());
    }

    private static MongoCollection<Document> apicalls() {
        return getDatabase().getCollection("apicalls");
    }

    private static Document apiCall(ObjectId id, String body) {
        return new Document("_id", id).append("_version", 1).append("httpCalls",
                List.of(new Document("name", "call").append("request", new Document("body", body))));
    }

    private static String bodyOf(ObjectId id) {
        return apicalls().find(eq("_id", id)).first().getList("httpCalls", Document.class).getFirst().get("request", Document.class)
                .getString("body");
    }

    private List<CapturedRecord> boot() {
        return captureRecordsOf(V6QuteMigration.class,
                () -> new V6QuteMigration(getDatabase(), migrationLog, new TemplateSyntaxMigrator(), true).runIfNeeded());
    }

    private static List<CapturedRecord> errors(List<CapturedRecord> records) {
        return records.stream().filter(CapturedRecord::isError).toList();
    }

    private static List<CapturedRecord> warnings(List<CapturedRecord> records) {
        return records.stream().filter(CapturedRecord::isWarning).toList();
    }

    private static List<String> reported() {
        Document record = getDatabase().getCollection("migrationlog").find(eq("name", REPORTED_KEY)).first();
        return record == null ? List.of() : record.getList("entries", String.class);
    }

    private static boolean complete() {
        return getDatabase().getCollection("migrationlog").find(eq("name", COMPLETE_KEY)).first() != null;
    }

    @Test
    @DisplayName("first start: an ERROR per refused document, naming collection, id, field and what to do")
    void firstStartLogsAnErrorPerDocument() {
        apicalls().insertOne(apiCall(BROKEN_A, UNCONVERTIBLE));
        apicalls().insertOne(apiCall(BROKEN_B, UNCONVERTIBLE));
        apicalls().insertOne(apiCall(CLEAN, "[[${properties.name}]]"));

        List<CapturedRecord> records = boot();

        List<CapturedRecord> errors = errors(records);
        assertEquals(2, errors.size(), records.toString());
        for (ObjectId id : List.of(BROKEN_A, BROKEN_B)) {
            CapturedRecord error = errors.stream().filter(record -> record.message().contains("apicalls/" + id)).findFirst()
                    .orElseThrow(() -> new AssertionError("no ERROR names " + id + ": " + records));
            assertTrue(error.message().contains("httpCalls[0].request.body"), error.message());
            assertTrue(error.message().contains("by hand") && error.message().contains("delete or retire"), error.message());
        }
        assertEquals(UNCONVERTIBLE, bodyOf(BROKEN_A));
        assertEquals("{properties.name}", bodyOf(CLEAN));
        assertEquals(List.of("apicalls/" + BROKEN_A, "apicalls/" + BROKEN_B), reported());
        assertFalse(complete());
    }

    @Test
    @DisplayName("second start: one WARN listing the reported documents, no ERROR, and still not complete")
    void secondStartWarnsOnce() {
        apicalls().insertOne(apiCall(BROKEN_A, UNCONVERTIBLE));
        apicalls().insertOne(apiCall(BROKEN_B, UNCONVERTIBLE));
        boot();

        List<CapturedRecord> records = boot();

        assertEquals(List.of(), errors(records));
        List<CapturedRecord> warnings = warnings(records);
        assertEquals(1, warnings.size(), records.toString());
        assertTrue(warnings.getFirst().message().contains("apicalls/" + BROKEN_A), warnings.getFirst().message());
        assertTrue(warnings.getFirst().message().contains("apicalls/" + BROKEN_B), warnings.getFirst().message());
        assertEquals(UNCONVERTIBLE, bodyOf(BROKEN_A));
        assertFalse(complete(), "a refused document still holds Thymeleaf, reported or not");
    }

    @Test
    @DisplayName("a document fixed between starts is converted and leaves the list; with none left, the migration completes")
    void fixedDocumentIsConvertedAndDropped() {
        apicalls().insertOne(apiCall(BROKEN_A, UNCONVERTIBLE));
        apicalls().insertOne(apiCall(BROKEN_B, UNCONVERTIBLE));
        boot();

        apicalls().replaceOne(eq("_id", BROKEN_A), apiCall(BROKEN_A, "[[${properties.fixed}]]"));
        List<CapturedRecord> second = boot();

        assertEquals("{properties.fixed}", bodyOf(BROKEN_A));
        assertEquals(List.of("apicalls/" + BROKEN_B), reported());
        assertEquals(List.of(), errors(second));
        assertEquals(1, warnings(second).size(), second.toString());
        assertFalse(warnings(second).getFirst().message().contains(BROKEN_A.toString()), second.toString());
        assertFalse(complete());

        apicalls().deleteOne(eq("_id", BROKEN_B));
        List<CapturedRecord> third = boot();

        assertEquals(List.of(), errors(third));
        assertEquals(List.of(), warnings(third));
        assertEquals(List.of(), reported());
        assertNull(getDatabase().getCollection("migrationlog").find(eq("name", REPORTED_KEY)).first(), "an empty record is removed");
        assertTrue(complete());
    }

    @Test
    @DisplayName("a new refused document on a later start gets its own ERROR; the known one stays in the WARN")
    void newDocumentOnALaterStartGetsItsError() {
        apicalls().insertOne(apiCall(BROKEN_A, UNCONVERTIBLE));
        boot();

        apicalls().insertOne(apiCall(BROKEN_B, UNCONVERTIBLE));
        List<CapturedRecord> records = boot();

        List<CapturedRecord> errors = errors(records);
        assertEquals(1, errors.size(), records.toString());
        assertTrue(errors.getFirst().message().contains("apicalls/" + BROKEN_B), errors.getFirst().message());
        List<CapturedRecord> warnings = warnings(records);
        assertEquals(1, warnings.size(), records.toString());
        assertTrue(warnings.getFirst().message().contains("apicalls/" + BROKEN_A), warnings.getFirst().message());
        assertFalse(warnings.getFirst().message().contains("apicalls/" + BROKEN_B), warnings.getFirst().message());
        assertEquals(List.of("apicalls/" + BROKEN_A, "apicalls/" + BROKEN_B), reported());
    }

    @Test
    @DisplayName("a refused history row is named with its version, and told it can only be fixed in the database")
    void historyRowIsNamedWithItsVersion() {
        getDatabase().getCollection("apicalls.history")
                .insertOne(apiCall(BROKEN_A, UNCONVERTIBLE).append("_id", new Document("_id", BROKEN_A).append("_version", 3)));

        List<CapturedRecord> records = boot();

        List<CapturedRecord> errors = errors(records);
        assertEquals(1, errors.size(), records.toString());
        assertTrue(errors.getFirst().message().contains("apicalls.history/" + BROKEN_A + " v3"), errors.getFirst().message());
        assertTrue(errors.getFirst().message().contains("in the database by hand"), errors.getFirst().message());
        assertEquals(List.of("apicalls.history/" + BROKEN_A + " v3"), reported());
        assertNotNull(getDatabase().getCollection("apicalls.history").find().first());
        assertEquals(List.of(), errors(boot()));
    }
}
