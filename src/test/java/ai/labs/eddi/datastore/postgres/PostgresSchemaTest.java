/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationOnMock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PostgresSchema} against a scripted connection: every query the class
 * runs is answered by {@link #answer}, which hands out a fresh result set per
 * query (so the test can assert that each one is closed) or throws what the
 * test configured for a fragment of the SQL. What ran, and in which order, is
 * read from the recorded invocations.
 */
class PostgresSchemaTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private PreparedStatement catalogueLookup;

    /** SQL fragment -> failure, for executeQuery and execute on the statement. */
    private final Map<String, SQLException> failures = new ConcurrentHashMap<>();
    /** Every result set handed out, to check that all of them were closed. */
    private final List<ResultSet> resultSets = new CopyOnWriteArrayList<>();
    /**
     * The catalogue's row for the looked-up column (is_nullable, data_type), or
     * null.
     */
    private volatile String[] catalogueRow;

    @BeforeEach
    void setUp() throws SQLException {
        failures.clear();
        resultSets.clear();
        catalogueRow = null;
        statement = mock(Statement.class, this::answer);
        catalogueLookup = mock(PreparedStatement.class, this::answer);
        connection = mock(Connection.class, this::answer);
        dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        PostgresSchema.resetForTests();
    }

    private Object answer(InvocationOnMock invocation) throws Throwable {
        String method = invocation.getMethod().getName();
        Object mock = invocation.getMock();
        if (mock == connection) {
            return switch (method) {
                case "createStatement" -> statement;
                case "prepareStatement" -> catalogueLookup;
                default -> RETURNS_DEFAULTS.answer(invocation);
            };
        }
        if (mock == statement && method.equals("getConnection")) {
            return connection;
        }
        if ((method.equals("executeQuery") || method.equals("execute")) && invocation.getArguments().length == 1) {
            String sql = invocation.getArgument(0);
            for (var failure : failures.entrySet()) {
                if (sql.contains(failure.getKey())) {
                    throw failure.getValue();
                }
            }
        }
        if (method.equals("executeQuery")) {
            ResultSet rs = mock(ResultSet.class);
            String[] row = mock == catalogueLookup ? catalogueRow : null;
            if (row != null) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getString(1)).thenReturn(row[0]);
                when(rs.getString(2)).thenReturn(row[1]);
            }
            resultSets.add(rs);
            return rs;
        }
        return RETURNS_DEFAULTS.answer(invocation);
    }

    /** The SQL the statement ran, in order: queries and plain executes. */
    private List<String> statementSql() {
        List<String> sql = new ArrayList<>();
        for (Invocation invocation : mockingDetails(statement).getInvocations()) {
            String method = invocation.getMethod().getName();
            if ((method.equals("executeQuery") || method.equals("execute")) && invocation.getArguments().length == 1) {
                sql.add(invocation.getArgument(0));
            }
        }
        return sql;
    }

    private long count(String fragment) {
        return statementSql().stream().filter(s -> s.contains(fragment)).count();
    }

    private void assertEveryResultSetClosed() throws SQLException {
        assertFalse(resultSets.isEmpty());
        for (ResultSet rs : resultSets) {
            verify(rs).close();
        }
    }

    @Test
    void run_takesTheAdvisoryLockAroundTheWorkAndReleasesIt() throws Exception {
        PostgresSchema.run(dataSource, "step", stmt -> stmt.execute("CREATE TABLE x"));

        long key = PostgresSchema.lockKey("step");
        assertEquals(List.of(PostgresSchema.SAVE_LOCK_TIMEOUT, PostgresSchema.SET_LOCK_TIMEOUT, "SELECT pg_advisory_lock(" + key + ")",
                "CREATE TABLE x", "SELECT pg_advisory_unlock(" + key + ")", PostgresSchema.RESTORE_LOCK_TIMEOUT), statementSql());
        assertEveryResultSetClosed();
        verify(statement).close();
        verify(connection).close();
        verify(connection, never()).abort(any());
    }

    @Test
    void theLockTimeoutStatementsCarryNoValueFromOutside() {
        // the session's own value is saved and restored inside PostgreSQL; nothing read
        // back into Java is ever spliced into SQL
        assertEquals("SELECT set_config('lock_timeout', '" + PostgresSchema.LOCK_TIMEOUT_SECONDS + "s', false)", PostgresSchema.SET_LOCK_TIMEOUT);
        assertTrue(PostgresSchema.SAVE_LOCK_TIMEOUT.contains("current_setting('lock_timeout')"));
        assertTrue(PostgresSchema.RESTORE_LOCK_TIMEOUT.contains("current_setting('eddi.schema_saved_lock_timeout')"));
    }

    @Test
    void run_usesAKeyOtherThanTheStartupLock() {
        assertFalse(PostgresSchema.LOCK_KEY == PostgresStartupSchemaLock.LOCK_KEY,
                "a store creating its schema during startup must not wait for its own JVM's startup lock");
    }

    @Test
    void runOnce_runsOnceWhenManyThreadsArriveAtTheSameTime() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                PostgresSchema.runOnce(dataSource, "concurrent", stmt -> {
                    executions.incrementAndGet();
                    LockSupport.parkNanos(50_000_000L);
                });
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(1, executions.get(), "the DDL of one step runs once per JVM and database");
        assertEquals(1, count("pg_advisory_lock("));
    }

    @Test
    void runOnce_isPerDatabaseAndPerName() throws Exception {
        DataSource other = mock(DataSource.class);
        when(other.getConnection()).thenReturn(connection);
        AtomicInteger executions = new AtomicInteger();

        PostgresSchema.runOnce(dataSource, "a", stmt -> executions.incrementAndGet());
        PostgresSchema.runOnce(dataSource, "b", stmt -> executions.incrementAndGet());
        PostgresSchema.runOnce(other, "a", stmt -> executions.incrementAndGet());
        PostgresSchema.runOnce(dataSource, "a", stmt -> executions.incrementAndGet());

        assertEquals(3, executions.get());
    }

    @Test
    void runOnce_identifiesTheDatabaseThroughTheSanitisingWrapper() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        DataSource wrapped = PostgresSanitizingDataSource.wrap(dataSource);

        PostgresSchema.runOnce(dataSource, "wrapped", stmt -> executions.incrementAndGet());
        PostgresSchema.runOnce(wrapped, "wrapped", stmt -> executions.incrementAndGet());

        assertEquals(1, executions.get());
    }

    @Test
    void aFailureIsThrownReleasesTheLockAndIsRetriedAfterTheBackOff() throws Exception {
        long[] now = {0};
        PostgresSchema.clock = () -> now[0];
        AtomicInteger attempts = new AtomicInteger();
        SQLException failure = new SQLException("duplicate key value violates unique constraint pg_type_typname_nsp_index", "23505");

        SQLException thrown = assertThrows(SQLException.class, () -> PostgresSchema.runOnce(dataSource, "flaky", stmt -> {
            attempts.incrementAndGet();
            throw failure;
        }));
        assertSame(failure, thrown, "the cause reaches the caller");
        assertEquals(1, count("pg_advisory_unlock("));

        // inside the back-off the database is not touched: the same error again, from
        // the remembered message and SQLState
        SQLException refused = assertThrows(SQLException.class,
                () -> PostgresSchema.runOnce(dataSource, "flaky", stmt -> attempts.incrementAndGet()));
        assertTrue(refused.getMessage().contains(failure.getMessage()), refused.getMessage());
        assertNull(refused.getCause(), "the failed attempt's exception (and its driver objects) is not kept for the back-off");
        assertEquals("23505", refused.getSQLState());
        assertEquals(1, attempts.get(), "no request storm: the DDL did not run again");
        verify(dataSource, times(1)).getConnection();

        now[0] = TimeUnit.SECONDS.toNanos(2);
        PostgresSchema.runOnce(dataSource, "flaky", stmt -> attempts.incrementAndGet());
        assertEquals(2, attempts.get(), "after the interval the creation is tried again, and a success is remembered");

        AtomicInteger afterSuccess = new AtomicInteger();
        PostgresSchema.runOnce(dataSource, "flaky", stmt -> afterSuccess.incrementAndGet());
        assertEquals(0, afterSuccess.get());
    }

    @Test
    void theBackOffGrowsWithEveryFailure() throws Exception {
        long[] now = {0};
        PostgresSchema.clock = () -> now[0];
        AtomicInteger attempts = new AtomicInteger();
        PostgresSchema.Work failing = stmt -> {
            attempts.incrementAndGet();
            throw new SQLException("still failing", "55P03");
        };
        assertThrows(SQLException.class, () -> PostgresSchema.runOnce(dataSource, "growing", failing)); // retry at +1 s
        now[0] = TimeUnit.SECONDS.toNanos(1);
        assertThrows(SQLException.class, () -> PostgresSchema.runOnce(dataSource, "growing", failing)); // second failure: retry at +2 s
        now[0] = TimeUnit.SECONDS.toNanos(2);
        assertThrows(SQLException.class, () -> PostgresSchema.runOnce(dataSource, "growing", failing));
        assertEquals(2, attempts.get(), "one second after the second failure is inside its two-second back-off");
        now[0] = TimeUnit.SECONDS.toNanos(3);
        assertThrows(SQLException.class, () -> PostgresSchema.runOnce(dataSource, "growing", failing));
        assertEquals(3, attempts.get());
    }

    @Test
    void theSessionsLockTimeoutIsRestoredAfterASuccessAndAfterAFailure() throws Exception {
        PostgresSchema.run(dataSource, "restore-ok", stmt -> stmt.execute("CREATE TABLE x"));
        assertEquals(1, count(PostgresSchema.RESTORE_LOCK_TIMEOUT));

        assertThrows(SQLException.class, () -> PostgresSchema.run(dataSource, "restore-fail", stmt -> {
            throw new SQLException("boom");
        }));
        assertEquals(2, count(PostgresSchema.RESTORE_LOCK_TIMEOUT));
        assertEquals(0, count("set_config('lock_timeout', '0'"), "the session's own value is restored, never a fixed one");
        assertEveryResultSetClosed();
    }

    @Test
    void aStepThatFailsToGetItsLockStillRestoresTheTimeout() throws Exception {
        failures.put("pg_advisory_lock(", new SQLException("canceling statement due to lock timeout", "55P03"));
        AtomicInteger executions = new AtomicInteger();

        assertThrows(SQLException.class, () -> PostgresSchema.runOnce(dataSource, "locked-out", stmt -> executions.incrementAndGet()));

        assertEquals(0, executions.get());
        assertEquals(0, count("pg_advisory_unlock("), "a lock that was not taken is not released");
        assertEquals(1, count(PostgresSchema.RESTORE_LOCK_TIMEOUT));
        verify(connection, never()).abort(any());
    }

    @Test
    void aTimeoutThatWasNeverSetIsNotRestored() throws Exception {
        failures.put(PostgresSchema.SET_LOCK_TIMEOUT, new SQLException("connection lost", "08006"));

        assertThrows(SQLException.class, () -> PostgresSchema.run(dataSource, "no-timeout", stmt -> {
        }));

        assertEquals(0, count("pg_advisory_lock("));
        assertEquals(0, count(PostgresSchema.RESTORE_LOCK_TIMEOUT));
    }

    @Test
    void aSessionThatCannotBeUnlockedIsDiscardedNotReturnedToThePool() throws Exception {
        // e.g. a connection in a transaction that the failed DDL aborted: every later
        // statement, the unlock included, fails with 25P02 and the session keeps the
        // lock
        failures.put("pg_advisory_unlock(", new SQLException("current transaction is aborted", "25P02"));

        SQLException thrown = assertThrows(SQLException.class, () -> PostgresSchema.run(dataSource, "aborted", stmt -> {
            throw new SQLException("boom", "42P07");
        }));

        assertEquals("42P07", thrown.getSQLState(), "the DDL's failure is what the caller sees");
        assertEquals(1, thrown.getSuppressed().length);
        verify(connection).abort(any());
    }

    @Test
    void aSessionWhoseTimeoutCannotBeRestoredIsDiscarded() throws Exception {
        failures.put(PostgresSchema.RESTORE_LOCK_TIMEOUT, new SQLException("connection lost", "08006"));

        PostgresSchema.run(dataSource, "unrestorable", stmt -> stmt.execute("CREATE TABLE x"));

        verify(connection).abort(any());
    }

    @Test
    void stepsOfDifferentStoresTakeDifferentLocksButOneTableOneLock() {
        assertFalse(PostgresSchema.lockKey("PostgresAuditStore") == PostgresSchema.lockKey("PostgresScheduleStore"));
        assertEquals(PostgresSchema.lockKey("PostgresResourceStorage:agents"), PostgresSchema.lockKey("PostgresResourceStorage:workflows"));
    }

    @Test
    void columnChecksAskTheCatalogueWithBoundNames() throws Exception {
        catalogueRow = new String[]{"YES", "bigint"};
        assertFalse(PostgresSchema.columnMissing(statement, "audit_ledger", "sequence"));
        assertFalse(PostgresSchema.columnHasNotNull(statement, "audit_ledger", "agent_id"));

        catalogueRow = new String[]{"NO", "text"};
        assertTrue(PostgresSchema.columnHasNotNull(statement, "audit_ledger", "agent_id"));

        catalogueRow = null;
        assertTrue(PostgresSchema.columnMissing(statement, "audit_ledger", "sequence"));

        verify(catalogueLookup, times(4)).setString(1, "audit_ledger");
        verify(catalogueLookup, times(2)).setString(2, "sequence");
        verify(catalogueLookup, times(4)).close();
        assertEveryResultSetClosed();
        assertTrue(statementSql().isEmpty(), "no catalogue SQL is built from the names");
    }

    @Test
    void columnChecksSayMissingWhenTheCatalogueCannotBeAsked() throws Exception {
        Statement detached = mock(Statement.class);
        assertTrue(PostgresSchema.columnMissing(detached, "audit_ledger", "sequence"));
        assertTrue(PostgresSchema.columnHasNotNull(detached, "audit_ledger", "agent_id"));
        assertDoesNotThrow(() -> PostgresSchema.widenToText(detached, "eddi_schedules", "name"));
        verify(detached, never()).execute(any());
    }

    @Test
    void widenToText_altersOnlyAVarcharColumn() throws Exception {
        catalogueRow = new String[]{"YES", "character varying"};
        PostgresSchema.widenToText(statement, "eddi_schedules", "name");
        assertEquals(List.of("ALTER TABLE eddi_schedules ALTER COLUMN name TYPE TEXT"), statementSql());

        catalogueRow = new String[]{"YES", "text"};
        PostgresSchema.widenToText(statement, "eddi_schedules", "name");
        assertEquals(1, count("ALTER TABLE"));
        assertEveryResultSetClosed();
    }

    @Test
    void widenToText_isBestEffort_aViewOverTheColumnDoesNotFailTheStore() {
        catalogueRow = new String[]{"YES", "character varying"};
        failures.put("ALTER TABLE", new SQLException("cannot alter type of a column used by a view or rule", "0A000"));

        assertDoesNotThrow(() -> PostgresSchema.widenToText(statement, "eddi_schedules", "name"));
        assertDoesNotThrow(() -> PostgresSchema.widenToText(statement, "eddi_schedules", "name"));
    }

    @Test
    void identifiersArePlainLowerCaseNamesOnly() {
        assertEquals("audit_ledger", PostgresSchema.identifier("audit_ledger"));
        assertEquals("_x1", PostgresSchema.identifier("_x1"));
        String longest = "a".repeat(63);
        assertEquals(longest, PostgresSchema.identifier(longest));
        for (String bad : new String[]{null, "", "audit_ledger'; DROP TABLE x; --", "Audit", "1abc", "a b", "a\"b", "a-b", "a$b", "a.b",
                "a".repeat(64), "abc\n", "äbc"}) {
            assertThrows(IllegalArgumentException.class, () -> PostgresSchema.identifier(bad), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> PostgresSchema.columnMissing(statement, "audit_ledger'; DROP TABLE x; --", "c"));
    }
}
