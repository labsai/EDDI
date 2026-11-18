/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.sql.Statement;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresSanitizingDataSourceTest {

    private static final String FFFD = "\uFFFD";

    private DataSource target;
    private Connection connection;
    private PreparedStatement statement;
    private DataSource wrapped;

    @BeforeEach
    void setUp() throws SQLException {
        target = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        when(target.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        wrapped = PostgresSanitizingDataSource.wrap(target);
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        return wrapped.getConnection().prepareStatement(sql);
    }

    @Test
    void setString_cleansPlainTextParametersOnly() throws SQLException {
        PreparedStatement ps = prepare("INSERT INTO t (a, b) VALUES (?, ?::jsonb)");
        ps.setString(1, "a\u0000b \\u0000");
        ps.setString(2, "{\"k\":\"a\\u0000b\"}");

        verify(statement).setString(1, "a" + FFFD + "b \\u0000");
        verify(statement).setString(2, "{\"k\":\"a\\uFFFDb\"}");
    }

    @Test
    void setObject_cleansStringsAndPgObjects() throws SQLException {
        PreparedStatement ps = prepare("INSERT INTO t (a, b, c) VALUES (?, ?, ?)");
        PGobject json = new PGobject();
        json.setType("jsonb");
        json.setValue("{\"k\":\"\\u0000\"}");
        PGobject text = new PGobject();
        text.setType("citext");
        text.setValue("x\u0000y");

        ps.setObject(1, "x\u0000y");
        ps.setObject(2, json);
        ps.setObject(3, text);

        verify(statement).setObject(1, "x" + FFFD + "y");
        assertEquals("{\"k\":\"\\uFFFD\"}", json.getValue());
        assertEquals("x" + FFFD + "y", text.getValue());
    }

    @Test
    void createArrayOf_cleansStringElements() throws SQLException {
        Array array = mock(Array.class);
        when(connection.createArrayOf(anyString(), any(Object[].class))).thenReturn(array);

        Array result = wrapped.getConnection().createArrayOf("text", new Object[]{"a\u0000", "b"});

        assertSame(array, result);
        ArgumentCaptor<Object[]> elements = ArgumentCaptor.forClass(Object[].class);
        verify(connection).createArrayOf(org.mockito.ArgumentMatchers.eq("text"), elements.capture());
        assertEquals("a" + FFFD, elements.getValue()[0]);
        assertEquals("b", elements.getValue()[1]);
    }

    @Test
    void nonStringParametersAndNullsPassThrough() throws SQLException {
        PreparedStatement ps = prepare("UPDATE t SET a = ?, b = ?");
        ps.setString(1, null);
        ps.setInt(2, 7);

        verify(statement).setString(1, null);
        verify(statement).setInt(2, 7);
    }

    @Test
    void exceptionsOfTheTargetAreRethrownUnchanged() throws SQLException {
        SQLException failure = new SQLException("boom", "08006");
        when(connection.prepareStatement("bad")).thenThrow(failure);

        SQLException thrown = assertThrows(SQLException.class, () -> wrapped.getConnection().prepareStatement("bad"));
        assertSame(failure, thrown);
    }

    @Test
    void statementsWithoutParametersAreNotTouched() throws SQLException {
        Statement plain = mock(Statement.class);
        when(connection.createStatement()).thenReturn(plain);

        assertSame(plain, wrapped.getConnection().createStatement());
    }

    @Test
    void wrappingIsIdempotentAndNullSafe() {
        assertSame(wrapped, PostgresSanitizingDataSource.wrap(wrapped));
        assertSame(target, PostgresSanitizingDataSource.unwrap(wrapped));
        assertSame(null, PostgresSanitizingDataSource.wrap((DataSource) null));
        assertSame(null, PostgresSanitizingDataSource.wrap((Instance<DataSource>) null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void instanceGetReturnsTheSanitisingDataSource() throws SQLException {
        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(target);

        Instance<DataSource> wrappedInstance = PostgresSanitizingDataSource.wrap(instance);
        DataSource fromInstance = wrappedInstance.get();

        assertSame(target, PostgresSanitizingDataSource.unwrap(fromInstance));
        assertSame(fromInstance, wrappedInstance.get());
        fromInstance.getConnection().prepareStatement("SELECT ?").setString(1, "\u0000");
        verify(statement).setString(1, FFFD);
    }

    @Test
    void jsonParameters_treatsPgjdbcDoubleQuestionMarkAsOneLiteralOperator() {
        // copied from PostgresResourceStorage.JSONPATH_EXISTS and
        // PostgresUserMemoryStore.buildVisibilityQuery
        BitSet containment = PostgresSanitizingDataSource
                .jsonParameters("SELECT id, version FROM resources WHERE collection_name = ? AND data @?? ?::jsonpath");
        assertFalse(containment.get(1));
        assertFalse(containment.get(2));
        assertEquals(2, countParameters("SELECT id FROM resources WHERE collection_name = ? AND data @?? ?::jsonpath"));
        BitSet visibility = PostgresSanitizingDataSource.jsonParameters(
                "SELECT * FROM usermemories WHERE user_id = ? AND (visibility = 'group' AND group_ids ??| ARRAY[?, ?]) AND value = ?::jsonb");
        assertFalse(visibility.get(1) || visibility.get(2) || visibility.get(3));
        assertTrue(visibility.get(4), "?? is one literal ?, so the cast belongs to the fourth parameter, not the sixth");
    }

    /**
     * Parameters as the parser counts them: the cast parameter appended here is the
     * last one, so its index is count + 1.
     */
    private static int countParameters(String sql) {
        return PostgresSanitizingDataSource.jsonParameters(sql + " AND x = ?::jsonb").length() - 2;
    }

    @Test
    void jsonParameters_skipsEscapeStringsAndDollarQuotedBodies() {
        BitSet escape = PostgresSanitizingDataSource.jsonParameters("SELECT E'it\\'s ?' AS x, ?::jsonb");
        assertTrue(escape.get(1), "the ? inside E'...' with a backslash-escaped quote is not a parameter");
        BitSet dollar = PostgresSanitizingDataSource.jsonParameters("DO $$ BEGIN PERFORM ?; END $$; SELECT ?::jsonb");
        assertTrue(dollar.get(1));
        BitSet tagged = PostgresSanitizingDataSource.jsonParameters("SELECT $body$ ? $body$, ?::jsonb, $1");
        assertTrue(tagged.get(1));
    }

    @Test
    void setObject_otherWithAJsonDocumentIsCleanedAsJson_pgvectorMetadata() throws SQLException {
        // langchain4j binds the metadata as setObject(i, json, Types.OTHER) with no
        // cast in the SQL
        PreparedStatement ps = prepare("INSERT INTO embeddings (embedding_id, embedding, text, metadata) VALUES (?, ?, ?, ?)");
        ps.setObject(4, "{\"source\":\"a\\u0000b\",\"k\":\"\\\\u0000\"}", Types.OTHER);
        ps.setObject(3, "{\"looks\":\"json\\u0000\"}", Types.VARCHAR);
        ps.setObject(2, "not json \\u0000", Types.OTHER);

        verify(statement).setObject(4, "{\"source\":\"a\\uFFFDb\",\"k\":\"\\\\u0000\"}", Types.OTHER);
        verify(statement).setObject(3, "{\"looks\":\"json\\u0000\"}", Types.VARCHAR);
        verify(statement).setObject(2, "not json \\u0000", Types.OTHER);
    }

    @Test
    void pgObjectsAndTextArraysAreCleanedOfEscapedNulAndLoneSurrogates() throws SQLException {
        PreparedStatement ps = prepare("INSERT INTO t (a, b) VALUES (?, ?)");
        PGobject json = new PGobject();
        json.setType("json");
        json.setValue("{\"k\":\"\\u0000 \\ud83d x \\ude00 \\ud83d\\ude00\"}");
        ps.setObject(1, json);
        assertEquals("{\"k\":\"\\uFFFD \\uFFFD x \\uFFFD \\ud83d\\ude00\"}", json.getValue());

        Array array = mock(Array.class);
        when(connection.createArrayOf(anyString(), any(Object[].class))).thenReturn(array);
        wrapped.getConnection().createArrayOf("text", new Object[]{"lone \uD83D", "pair \uD83D\uDE00", 7});
        ArgumentCaptor<Object[]> elements = ArgumentCaptor.forClass(Object[].class);
        verify(connection).createArrayOf(eq("text"), elements.capture());
        assertEquals("lone \uFFFD", elements.getValue()[0]);
        assertEquals("pair \uD83D\uDE00", elements.getValue()[1]);
        assertEquals(7, elements.getValue()[2]);
    }

    @Test
    void instanceGetTakesNoLockAndKeepsTheWrapperWhileTheDataSourceIsTheSame() {
        @SuppressWarnings("unchecked")
        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(target);
        Instance<DataSource> wrappedInstance = PostgresSanitizingDataSource.wrap(instance);

        DataSource first = wrappedInstance.get();
        for (int i = 0; i < 100; i++) {
            assertSame(first, wrappedInstance.get());
        }
        DataSource other = mock(DataSource.class);
        when(instance.get()).thenReturn(other);
        assertSame(other, PostgresSanitizingDataSource.unwrap(wrappedInstance.get()));
    }

    @Test
    void jsonParametersOf_parsesAStatementOnce() {
        String sql = "UPDATE t SET d = ?::jsonb WHERE id = ?";
        assertSame(PostgresSanitizingDataSource.jsonParametersOf(sql), PostgresSanitizingDataSource.jsonParametersOf(sql));
        assertTrue(PostgresSanitizingDataSource.jsonParametersOf(sql).get(1));
    }

    @Test
    void jsonParameters_findsCastsAndIgnoresQuotedQuestionMarks() {
        BitSet json = PostgresSanitizingDataSource.jsonParameters(
                "UPDATE t SET d = jsonb_set(d, '{a?}', ?::jsonb), n = ?, \"c?\" = CAST(? AS jsonb) -- ?\n WHERE id = ?::uuid /* ? */ AND x = ?::JSON");
        assertTrue(json.get(1));
        assertFalse(json.get(2));
        assertTrue(json.get(3));
        assertFalse(json.get(4));
        assertTrue(json.get(5));
    }
}
