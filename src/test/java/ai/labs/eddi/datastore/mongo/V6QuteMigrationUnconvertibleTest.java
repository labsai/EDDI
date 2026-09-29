/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Qute migration against a template it cannot convert safely.
 *
 * <p>
 * A legacy API-call config held a template of the shape
 * {@code [['[[${'+'x.x'+'}]]']]} — an expression producing text that is itself
 * Thymeleaf. It used to be rewritten to {@code [['+'x.x'+']]}, counted as
 * migrated, with no error logged: the call then rendered literal text. Such a
 * document must be left exactly as it was and keep the migration incomplete,
 * while the rest of the collection still converts. Ids and values are
 * synthetic.
 * </p>
 */
@DisplayName("V6QuteMigration — a template that cannot be converted safely")
class V6QuteMigrationUnconvertibleTest extends MongoTestBase {

    private static final ObjectId BROKEN = new ObjectId("0000000000000000000000e1");
    private static final ObjectId CLEAN = new ObjectId("0000000000000000000000e2");
    private static final String NESTED = "[['[[${'+'x.x'+'}]]']]";

    private IMigrationLogStore migrationLog;

    @BeforeEach
    void clean() {
        dropCollections("apicalls", "apicalls.history");
        migrationLog = mock(IMigrationLogStore.class);
        when(migrationLog.readMigrationLog(any())).thenReturn(null);
    }

    private static Document apiCall(ObjectId id, String body, String header) {
        return new Document("_id", id).append("_version", 1).append("httpCalls",
                List.of(new Document("name", "call").append("request", new Document("body", body).append("headers",
                        new Document("X-Thing", header)))));
    }

    @Test
    @DisplayName("the document is left untouched, counted as failed, and the others are still migrated")
    void unconvertibleDocumentIsLeftUntouchedAndReported() {
        var apicalls = getDatabase().getCollection("apicalls");
        Document broken = apiCall(BROKEN, NESTED, "[[${properties.header}]]");
        apicalls.insertOne(broken);
        apicalls.insertOne(apiCall(CLEAN, "[[${properties.body}]]", "fixed"));

        new V6QuteMigration(getDatabase(), migrationLog, new TemplateSyntaxMigrator(), true).runIfNeeded();

        assertEquals(broken, apicalls.find(new Document("_id", BROKEN)).first(),
                "not even the convertible field of a refused document is written");
        Document converted = apicalls.find(new Document("_id", CLEAN)).first();
        assertEquals("{properties.body}",
                converted.getList("httpCalls", Document.class).getFirst().get("request", Document.class).getString("body"));
        verify(migrationLog, never()).createMigrationLog(any());
    }

    /**
     * The check for a conversion that did not happen looks for Thymeleaf
     * delimiters. Text that merely names Thymeleaf syntax — a prompt explaining
     * {@code th:if}, say — is not a template left behind, and must not keep the
     * migration incomplete.
     */
    @Test
    @DisplayName("text that mentions th:if beside a real template is converted, and the migration completes")
    void textMentioningThymeleafIsNotAFailure() {
        var apicalls = getDatabase().getCollection("apicalls");
        apicalls.insertOne(apiCall(CLEAN, "Explain what th:if does. Hello [[${properties.name}]]", "fixed"));

        new V6QuteMigration(getDatabase(), migrationLog, new TemplateSyntaxMigrator(), true).runIfNeeded();

        Document converted = apicalls.find(new Document("_id", CLEAN)).first();
        assertEquals("Explain what th:if does. Hello {properties.name}",
                converted.getList("httpCalls", Document.class).getFirst().get("request", Document.class).getString("body"));
        verify(migrationLog).createMigrationLog(any());
    }
}
