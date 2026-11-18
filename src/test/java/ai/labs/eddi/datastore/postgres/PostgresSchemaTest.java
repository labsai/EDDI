/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresSchemaTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        PostgresSchema.resetForTests();
    }

    @Test
    void run_takesTheAdvisoryLockAroundTheWorkAndReleasesIt() throws Exception {
        PostgresSchema.run(dataSource, "step", stmt -> stmt.execute("CREATE TABLE x"));

        InOrder order = inOrder(statement);
        order.verify(statement).executeQuery(contains("set_config('lock_timeout'"));
        order.verify(statement).executeQuery(contains("pg_advisory_lock(" + PostgresSchema.lockKey("step")));
        order.verify(statement).execute("CREATE TABLE x");
        order.verify(statement).executeQuery(contains("pg_advisory_unlock(" + PostgresSchema.lockKey("step")));
        verify(connection).close();
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
        verify(statement, times(1)).executeQuery(contains("pg_advisory_lock("));
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
        verify(statement).executeQuery(contains("pg_advisory_unlock("));

        // inside the back-off the database is not touched: the same error again, from
        // the remembered one
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
        ResultSet current = mock(ResultSet.class);
        when(current.next()).thenReturn(true);
        when(current.getString(1)).thenReturn("5s");
        when(statement.executeQuery(contains("current_setting('lock_timeout')"))).thenReturn(current);

        PostgresSchema.run(dataSource, "restore-ok", stmt -> stmt.execute("CREATE TABLE x"));
        InOrder order = inOrder(statement);
        order.verify(statement).executeQuery(contains("set_config('lock_timeout', '" + PostgresSchema.LOCK_TIMEOUT_SECONDS + "s'"));
        order.verify(statement).execute("CREATE TABLE x");
        order.verify(statement).executeQuery(contains("pg_advisory_unlock("));
        order.verify(statement).executeQuery(contains("set_config('lock_timeout', '5s', false)"));

        assertThrows(SQLException.class, () -> PostgresSchema.run(dataSource, "restore-fail", stmt -> {
            throw new SQLException("boom");
        }));
        verify(statement, times(2)).executeQuery(contains("set_config('lock_timeout', '5s', false)"));
        verify(statement, never()).executeQuery(contains("set_config('lock_timeout', '0', false)"));
    }

    @Test
    void aStepThatFailsToGetItsLockStillRestoresTheTimeout() throws Exception {
        ResultSet current = mock(ResultSet.class);
        when(current.next()).thenReturn(true);
        when(current.getString(1)).thenReturn("0");
        when(statement.executeQuery(contains("current_setting('lock_timeout')"))).thenReturn(current);
        when(statement.executeQuery(contains("pg_advisory_lock("))).thenThrow(new SQLException("lock timeout", "55P03"));

        assertThrows(SQLException.class, () -> PostgresSchema.run(dataSource, "no-lock", stmt -> {
        }));

        verify(statement).executeQuery(contains("set_config('lock_timeout', '0', false)"));
    }

    @Test
    void aSessionThatCannotBeUnlockedIsDiscardedNotReturnedToThePool() throws Exception {
        // e.g. a connection in a transaction that the failed DDL aborted: every later
        // statement, the unlock included, fails with 25P02 and the session keeps the
        // lock
        when(statement.executeQuery(contains("pg_advisory_unlock("))).thenThrow(new SQLException("current transaction is aborted", "25P02"));

        SQLException thrown = assertThrows(SQLException.class, () -> PostgresSchema.run(dataSource, "aborted", stmt -> {
            throw new SQLException("boom", "42P07");
        }));

        assertEquals("42P07", thrown.getSQLState(), "the DDL's failure is what the caller sees");
        assertEquals(1, thrown.getSuppressed().length);
        verify(connection).abort(any());
    }

    @Test
    void aRestoredSessionGoesBackToThePool() throws Exception {
        PostgresSchema.run(dataSource, "clean", stmt -> stmt.execute("CREATE TABLE x"));

        verify(connection, never()).abort(any());
        verify(connection).close();
    }

    @Test
    void aSessionWhoseTimeoutCannotBeRestoredIsDiscarded() throws Exception {
        ResultSet current = mock(ResultSet.class);
        when(current.next()).thenReturn(true);
        when(current.getString(1)).thenReturn("5s");
        when(statement.executeQuery(contains("current_setting('lock_timeout')"))).thenReturn(current);
        when(statement.executeQuery(contains("set_config('lock_timeout', '5s', false)"))).thenThrow(new SQLException("connection lost", "08006"));

        PostgresSchema.run(dataSource, "unrestorable", stmt -> stmt.execute("CREATE TABLE x"));

        verify(connection).abort(any());
    }

    @Test
    void stepsOfDifferentStoresTakeDifferentLocksButOneTableOneLock() {
        assertFalse(PostgresSchema.lockKey("PostgresAuditStore") == PostgresSchema.lockKey("PostgresScheduleStore"));
        assertEquals(PostgresSchema.lockKey("PostgresResourceStorage:agents"), PostgresSchema.lockKey("PostgresResourceStorage:workflows"));
    }

    @Test
    void aFailureToGetALockFailsTheCreationWithoutRunningTheWork() throws Exception {
        when(statement.executeQuery(contains("pg_advisory_lock("))).thenThrow(new SQLException("canceling statement due to lock timeout", "55P03"));
        AtomicInteger executions = new AtomicInteger();

        assertThrows(SQLException.class, () -> PostgresSchema.runOnce(dataSource, "locked-out", stmt -> executions.incrementAndGet()));

        assertEquals(0, executions.get());
        verify(statement, never()).executeQuery(contains("pg_advisory_unlock("));
    }

    @Test
    void columnChecksAnswerFromTheCatalogue() throws Exception {
        ResultSet exists = mock(ResultSet.class);
        when(exists.next()).thenReturn(true);
        when(exists.getString(1)).thenReturn("YES");
        when(statement.executeQuery(contains("information_schema.columns"))).thenReturn(exists);

        assertFalse(PostgresSchema.columnMissing(statement, "audit_ledger", "sequence"));
        assertFalse(PostgresSchema.columnHasNotNull(statement, "audit_ledger", "agent_id"));

        when(exists.getString(1)).thenReturn("NO");
        assertTrue(PostgresSchema.columnHasNotNull(statement, "audit_ledger", "agent_id"));

        ResultSet none = mock(ResultSet.class);
        when(none.next()).thenReturn(false);
        when(statement.executeQuery(contains("information_schema.columns"))).thenReturn(none);
        assertTrue(PostgresSchema.columnMissing(statement, "audit_ledger", "sequence"));
    }

    @Test
    void widenToText_altersOnlyAVarcharColumn() throws Exception {
        ResultSet varchar = mock(ResultSet.class);
        when(varchar.next()).thenReturn(true);
        when(varchar.getString(1)).thenReturn("character varying");
        when(statement.executeQuery(contains("data_type"))).thenReturn(varchar);

        PostgresSchema.widenToText(statement, "eddi_schedules", "name");
        verify(statement).execute("ALTER TABLE eddi_schedules ALTER COLUMN name TYPE TEXT");

        when(varchar.getString(1)).thenReturn("text");
        PostgresSchema.widenToText(statement, "eddi_schedules", "name");
        verify(statement, times(1)).execute(contains("ALTER TABLE"));
    }

    @Test
    void widenToText_isBestEffort_aViewOverTheColumnDoesNotFailTheStore() throws Exception {
        ResultSet varchar = mock(ResultSet.class);
        when(varchar.next()).thenReturn(true);
        when(varchar.getString(1)).thenReturn("character varying");
        when(statement.executeQuery(contains("data_type"))).thenReturn(varchar);
        when(statement.execute(contains("ALTER TABLE"))).thenThrow(new SQLException("cannot alter type of a column used by a view or rule", "0A000"));

        assertDoesNotThrow(() -> PostgresSchema.widenToText(statement, "eddi_schedules", "name"));
        assertDoesNotThrow(() -> PostgresSchema.widenToText(statement, "eddi_schedules", "name"));
    }

    @Test
    void columnChecksRejectAnythingButAPlainIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> PostgresSchema.columnMissing(statement, "audit_ledger'; DROP TABLE x; --", "c"));
    }
}
