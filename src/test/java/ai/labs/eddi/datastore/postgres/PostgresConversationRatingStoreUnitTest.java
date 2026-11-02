/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.ratings.model.RatingFilter;
import ai.labs.eddi.engine.ratings.model.RatingGroupBy;
import ai.labs.eddi.engine.ratings.model.RatingKind;
import ai.labs.eddi.engine.ratings.model.RatingSummary;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Java side of the PostgreSQL rating store with a mocked JDBC stack: the
 * SQL it sends and how it binds values. The database semantics run in
 * {@code PostgresConversationRatingStoreIT}.
 */
@SuppressWarnings("unchecked")
@DisplayName("PostgresConversationRatingStore (mocked JDBC)")
class PostgresConversationRatingStoreUnitTest {

    private Instance<DataSource> dataSourceInstance;
    private Connection connection;
    private Statement statement;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;
    private PostgresConversationRatingStore store;

    @BeforeEach
    void setUp() throws Exception {
        dataSourceInstance = mock(Instance.class);
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        preparedStatement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        when(dataSourceInstance.get()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        store = new PostgresConversationRatingStore(dataSourceInstance);
    }

    private static ConversationRating rating() {
        return new ConversationRating(null, "conv", -1, "agent", 3, "production", "chat-ui", "user", Scale.FIVE_STAR, 4, 0.75,
                List.of("Helpful"), "fine", null, null);
    }

    @Test
    @DisplayName("creates the table with the (conversation_id, step_index) unique key and the indexes")
    void schema() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);
        store.delete("conv", 1);

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).execute(ddl.capture());
        String all = String.join("\n", ddl.getAllValues());
        assertTrue(all.contains("UNIQUE (conversation_id, step_index)"), all);
        assertTrue(all.contains("step_index INTEGER NOT NULL"), "a NULL step would defeat the unique key");
        assertTrue(all.contains("idx_conversation_ratings_agent_created"));
        assertTrue(all.contains("idx_conversation_ratings_agent_version"));
        assertTrue(all.contains("idx_conversation_ratings_user"));
        assertTrue(all.contains("idx_conversation_ratings_attention"));
        assertTrue(all.contains("WHERE review_status = 'NEW'"), "attention-count is served by a partial index");
    }

    @Test
    @DisplayName("a schema that cannot be created fails the call instead of answering empty")
    void schemaFailurePropagates() throws Exception {
        when(statement.execute(anyString())).thenThrow(new SQLException("down"));

        assertThrows(RuntimeException.class, () -> store.readByConversation("conv"));
    }

    @Test
    @DisplayName("upsert is one INSERT … ON CONFLICT that leaves id and created_at alone")
    void upsertSql() throws Exception {
        Array array = mock(Array.class);
        when(connection.createArrayOf(eq("text"), any())).thenReturn(array);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("id-1");
        when(resultSet.getString("conversation_id")).thenReturn("conv");
        when(resultSet.getInt("step_index")).thenReturn(-1);
        when(resultSet.getString("scale")).thenReturn("FIVE_STAR");
        when(resultSet.getInt("score")).thenReturn(4);
        when(resultSet.getArray("tags")).thenReturn(array);
        when(array.getArray()).thenReturn(new String[]{"Helpful"});
        when(resultSet.getObject(anyString(), eq(OffsetDateTime.class))).thenReturn(OffsetDateTime.now());

        ConversationRating stored = store.upsert(rating());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sql.capture());
        String upsert = sql.getAllValues().stream().filter(s -> s.startsWith("INSERT")).findFirst().orElseThrow();
        assertTrue(upsert.contains("ON CONFLICT (conversation_id, step_index) DO UPDATE SET"), upsert);
        String updateList = upsert.substring(upsert.indexOf("DO UPDATE SET"), upsert.indexOf("RETURNING"));
        assertFalse(updateList.contains("created_at"), "a re-rating must keep created_at");
        assertFalse(updateList.contains(" id ="), "a re-rating must keep the id");
        assertTrue(updateList.contains("review_status = 'NEW', reviewed_by = NULL, reviewed_at = NULL, review_note = NULL"),
                "a re-rating reopens the triage");
        assertEquals("id-1", stored.id());
        assertEquals(List.of("Helpful"), stored.tags());
        verify(preparedStatement).setInt(3, -1);
    }

    @Test
    @DisplayName("filter values are bound, never spliced into the SQL")
    void filterValuesAreBound() {
        String hostile = "x' OR '1'='1";
        var where = PostgresConversationRatingStore
                .where(new RatingFilter(hostile, 2, hostile, null, null, 0.1, 0.9, RatingKind.MESSAGE, hostile, false));

        assertFalse(where.sql().contains(hostile), where.sql());
        assertTrue(where.sql().contains("step_index >= 0"));
        assertTrue(where.sql().contains("(comment IS NULL OR comment = '')"));
        assertEquals(List.of(hostile, 2, hostile, 0.1, 0.9, hostile), where.params());
        assertEquals("", PostgresConversationRatingStore.where(null).sql());

        var byConversation = PostgresConversationRatingStore
                .where(new RatingFilter("a1", null, null, null, null, null, null, null, null, null, null, null, hostile));
        assertTrue(byConversation.sql().contains("conversation_id = ?"), byConversation.sql());
        assertFalse(byConversation.sql().contains(hostile));
        assertEquals(List.of("a1", hostile), byConversation.params());
    }

    @Test
    @DisplayName("summary groups in SQL by the bucket expression, rendered in UTC")
    void summaryGroupsInSql() throws Exception {
        when(resultSet.next()).thenReturn(false);

        store.summarize(RatingFilter.ofAgent("a1"), RatingGroupBy.DAY);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sql.capture());
        String summary = sql.getAllValues().stream().filter(s -> s.contains("avg(normalized_score)")).findFirst().orElseThrow();
        assertTrue(summary.contains("GROUP BY to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')"), summary);
        assertTrue(summary.contains("count(*) FILTER (WHERE scale = 'THUMBS' AND score = 1)"), summary);
    }

    @Test
    @DisplayName("an ungrouped summary of nothing is one empty bucket")
    void ungroupedSummaryWithoutRows() throws Exception {
        when(resultSet.next()).thenReturn(false);

        List<RatingSummary> buckets = store.summarize(RatingFilter.ofAgent("a1"), RatingGroupBy.NONE);

        assertEquals(1, buckets.size());
        assertEquals(0, buckets.get(0).count());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sql.capture());
        String summary = sql.getAllValues().stream().filter(s -> s.contains("avg(normalized_score)")).findFirst().orElseThrow();
        assertFalse(summary.contains("GROUP BY"), summary);
    }

    @Test
    @DisplayName("deleteByUserId ignores a null user")
    void deleteByNullUser() throws Exception {
        assertEquals(0, store.deleteByUserId(null));
        verify(dataSourceInstance, never()).get();
    }
}
