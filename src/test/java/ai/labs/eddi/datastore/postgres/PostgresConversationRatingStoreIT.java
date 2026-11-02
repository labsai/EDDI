/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.ratings.ConversationRatingStoreContract;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
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

/**
 * {@link PostgresConversationRatingStore} against a real PostgreSQL
 * (Testcontainers): the shared {@link ConversationRatingStoreContract} plus the
 * schema.
 */
@DisplayName("PostgresConversationRatingStore")
class PostgresConversationRatingStoreIT extends PostgresTestBase implements ConversationRatingStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private PostgresConversationRatingStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresConversationRatingStore(createDataSourceInstance());
        // The first call creates the schema; then start from an empty table.
        store.count(null);
        truncateTables(DATA_SOURCE, PostgresConversationRatingStore.TABLE);
    }

    @Override
    public IConversationRatingStore store() {
        return store;
    }

    @Test
    void createsTheUniqueKeyAndQueryIndexes() throws Exception {
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt
                        .executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = '" + PostgresConversationRatingStore.TABLE + "'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.containsAll(List.of("uq_conversation_rating", "idx_conversation_ratings_agent_created",
                "idx_conversation_ratings_agent_version", "idx_conversation_ratings_user", "idx_conversation_ratings_attention")),
                indexes.toString());
    }
}
