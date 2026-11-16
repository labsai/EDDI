/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.groups.lessons.ILessonHistoryStore;
import ai.labs.eddi.engine.groups.lessons.LessonHistoryStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lesson history store against a real PostgreSQL (Testcontainers). */
@DisplayName("group_lesson_history (PostgreSQL)")
class PostgresLessonHistoryStoreIT extends PostgresTestBase implements LessonHistoryStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private PostgresLessonHistoryStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresLessonHistoryStore(createDataSourceInstance());
        // The first call creates the schema; then start from an empty table.
        store.countByGroup("none");
        truncateTables(DATA_SOURCE, "group_lesson_history");
    }

    @Override
    public ILessonHistoryStore history() {
        return store;
    }

    @Test
    void createsTheQueryIndex() throws Exception {
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'group_lesson_history'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.contains("idx_group_lesson_history_group"), indexes.toString());
    }
}
