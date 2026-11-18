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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    }

    @Test
    void run_takesTheAdvisoryLockAroundTheWorkAndReleasesIt() throws Exception {
        PostgresSchema.run(dataSource, "step", stmt -> stmt.execute("CREATE TABLE x"));

        InOrder order = inOrder(statement);
        order.verify(statement).executeQuery(contains("set_config('lock_timeout'"));
        order.verify(statement).executeQuery(contains("pg_advisory_lock(" + PostgresSchema.LOCK_KEY));
        order.verify(statement).execute("CREATE TABLE x");
        order.verify(statement).executeQuery(contains("pg_advisory_unlock(" + PostgresSchema.LOCK_KEY));
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
    void aFailureIsThrownReleasesTheLockAndIsRetriedByTheNextCall() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        SQLException failure = new SQLException("duplicate key value violates unique constraint pg_type_typname_nsp_index", "23505");

        SQLException thrown = assertThrows(SQLException.class, () -> PostgresSchema.runOnce(dataSource, "flaky", stmt -> {
            attempts.incrementAndGet();
            throw failure;
        }));
        assertSame(failure, thrown, "the cause reaches the caller");
        verify(statement).executeQuery(contains("pg_advisory_unlock("));

        PostgresSchema.runOnce(dataSource, "flaky", stmt -> attempts.incrementAndGet());
        assertEquals(2, attempts.get(), "a failed creation is not remembered as done");

        AtomicInteger afterSuccess = new AtomicInteger();
        PostgresSchema.runOnce(dataSource, "flaky", stmt -> afterSuccess.incrementAndGet());
        assertEquals(0, afterSuccess.get());
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
    void columnChecksRejectAnythingButAPlainIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> PostgresSchema.columnMissing(statement, "audit_ledger'; DROP TABLE x; --", "c"));
    }
}
