/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Runs one store's lazy schema creation: once per JVM and database, under one
 * PostgreSQL advisory lock, and without swallowing a failure.
 * <p>
 * Each PostgreSQL store creates its tables on first use. {@code CREATE TABLE IF
 * NOT EXISTS} is not safe against itself: two sessions that create the same
 * table at once both pass the existence check and the loser fails with
 * {@code duplicate key value violates unique constraint "pg_type_typname_nsp_index"}.
 * Several stores run against a fresh database at the same moment (one JVM, many
 * request threads), and replicas started together do too; the startup lock of
 * {@link PostgresStartupSchemaLock} covers only the startup phase, and several
 * stores swallowed the error and went on without their table.
 * <p>
 * {@link #runOnce} is the one place that closes this:
 * <ul>
 * <li><b>One advisory lock per step</b> (a key derived from the step's name and
 * {@link #LOCK_KEY}, distinct from the startup lock so a store creating its
 * schema during startup never waits for its own JVM). Replicas creating the
 * same tables wait for one another; stores that create different tables do not
 * wait for each other. The wait, and every lock the DDL itself waits for, is
 * bounded by {@link #LOCK_TIMEOUT_SECONDS}, after which the step fails instead
 * of stalling; the session's own {@code lock_timeout} is restored afterwards.
 * <li><b>Once per JVM and database.</b> Concurrent first requests of the same
 * store run the DDL once; the others wait and find it done.
 * <li><b>Failures surface, with back-off.</b> A failed creation throws
 * {@link SQLException} to the caller (after logging its cause) and is never
 * recorded as done. The next calls are refused with the same error without
 * touching the database for a short, growing interval (1 s, 2 s, ... 30 s), so
 * a permanently failing step is not a request storm, and then the DDL is tried
 * again.
 * </ul>
 * A store adopts it by wrapping its DDL:
 *
 * <pre>
 * PostgresSchema.runOnce(dataSourceInstance.get(), "my_table", stmt -&gt; {
 *     stmt.execute(CREATE_TABLE);
 *     stmt.execute(CREATE_INDEX);
 * });
 * </pre>
 *
 * Use {@link #columnMissing} and {@link #columnHasNotNull} to run an
 * {@code ALTER TABLE} only when it has something to do: an {@code ALTER TABLE}
 * takes an {@code ACCESS EXCLUSIVE} lock on the table even when it changes
 * nothing, which on a busy table queues behind every running query and blocks
 * every query behind it.
 * <p>
 * This is deliberately the small version of a schema migration runner: when a
 * versioned runner replaces the per-store DDL, it replaces the lambdas passed
 * here and this class's call sites, not the stores.
 */
public final class PostgresSchema {

    private static final Logger LOGGER = Logger.getLogger(PostgresSchema.class);

    /**
     * Fixed advisory-lock key shared by every EDDI node for schema creation
     * ("EDDIDDL1"). Differs from {@link PostgresStartupSchemaLock#LOCK_KEY}, which
     * a booting node holds for its whole startup phase.
     */
    static final long LOCK_KEY = 0x4544444944444C31L;

    /**
     * How long a node waits for the lock before failing the creation. Long enough
     * for another node's first-install DDL (seconds), short enough that a request
     * does not hang for ever behind a stuck session.
     */
    static final int LOCK_TIMEOUT_SECONDS = 60;

    private static final long MAX_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** Test seam for the back-off clock. */
    static LongSupplier clock = System::nanoTime;

    /**
     * Columns whose widening failed and was already reported, so it is logged once.
     */
    private static final Set<String> WIDEN_FAILURES = ConcurrentHashMap.newKeySet();

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    /** The DDL of one store, run on a statement that holds the schema lock. */
    @FunctionalInterface
    public interface Work {
        void run(Statement statement) throws SQLException;
    }

    /**
     * As {@link Work}, for DDL that also needs the connection (to ask the
     * catalogue, say).
     */
    @FunctionalInterface
    public interface ConnectionWork {
        void run(Connection connection, Statement statement) throws SQLException;
    }

    private static final class Step {
        volatile boolean done;
        int failures;
        long retryAtNanos;
        SQLException lastFailure;
    }

    /** Steps already completed or in progress, per database (weakly keyed). */
    private static final Map<DataSource, Map<String, Step>> STEPS = Collections.synchronizedMap(new WeakHashMap<>());

    private PostgresSchema() {
    }

    /**
     * Runs {@code work} once for {@code name} on this database, under the schema
     * lock. Returns at once when it has already completed in this JVM. If it fails,
     * the exception propagates, nothing is recorded, and the next call runs it
     * again.
     *
     * @param dataSource
     *            the database (the same instance identifies it for the whole JVM)
     * @param name
     *            a stable name of this step, unique per kind of schema (the table
     *            or the store)
     * @throws SQLException
     *             when the connection, the lock or the DDL failed
     */
    public static void runOnce(DataSource dataSource, String name, Work work) throws SQLException {
        Step step = STEPS.computeIfAbsent(PostgresSanitizingDataSource.unwrap(dataSource), key -> new ConcurrentHashMap<>()).computeIfAbsent(name,
                key -> new Step());
        if (step.done) {
            return;
        }
        synchronized (step) {
            if (step.done) {
                return;
            }
            long now = clock.getAsLong();
            if (step.failures > 0 && now - step.retryAtNanos < 0) {
                long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(step.retryAtNanos - now));
                throw new SQLException(
                        "Schema step " + name + " failed and is not retried for another " + seconds + " s: " + step.lastFailure.getMessage(),
                        step.lastFailure.getSQLState(), step.lastFailure);
            }
            try {
                run(dataSource, name, work);
            } catch (SQLException e) {
                step.failures++;
                step.lastFailure = e;
                step.retryAtNanos = now + Math.min(MAX_BACKOFF_NANOS, TimeUnit.SECONDS.toNanos(1L << Math.min(step.failures - 1, 5)));
                throw e;
            }
            step.done = true;
            step.failures = 0;
            step.lastFailure = null;
        }
    }

    /**
     * Like {@link #runOnce}, but runs every time it is called: for a creation that
     * is not the same each time (one table per collection, indexes a caller
     * declares) and so cannot be remembered by name. Still under the lock, and
     * still not swallowing failures.
     */
    public static void run(DataSource dataSource, String name, Work work) throws SQLException {
        run(dataSource, name, (connection, statement) -> work.run(statement));
    }

    /**
     * As {@link #run(DataSource, String, Work)}, with the connection handed to the
     * work.
     */
    public static void run(DataSource dataSource, String name, ConnectionWork work) throws SQLException {
        long key = lockKey(name);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            boolean locked = false;
            boolean timeoutSet = false;
            String savedTimeout = null;
            SQLException failure = null;
            try {
                // The connection is pooled: its own lock_timeout (a role or database default, a
                // JDBC option) is saved and put back, whatever happens.
                savedTimeout = single(statement, "SELECT current_setting('lock_timeout')");
                query(statement, "SELECT set_config('lock_timeout', '" + LOCK_TIMEOUT_SECONDS + "s', false)");
                timeoutSet = true;
                query(statement, "SELECT pg_advisory_lock(" + key + ")");
                locked = true;
                // the timeout stays in force during the DDL: an ALTER TABLE queued behind a
                // long
                // query fails cleanly instead of holding the lock and stalling the table
                work.run(connection, statement);
            } catch (SQLException e) {
                failure = e;
                LOGGER.errorf(e, "Creating the PostgreSQL schema for %s failed (SQLState %s); retried after a back-off", name, e.getSQLState());
                throw e;
            } finally {
                release(statement, locked, key, timeoutSet ? savedTimeout : null, failure);
            }
        }
    }

    /**
     * The advisory-lock key of a step: {@link #LOCK_KEY} mixed with a hash of the
     * step's name up to a colon, so {@code PostgresResourceStorage:agents} and
     * {@code PostgresResourceStorage:workflows} (one shared table) take the same
     * lock while unrelated stores do not.
     */
    static long lockKey(String name) {
        int colon = name.indexOf(':');
        String base = colon < 0 ? name : name.substring(0, colon);
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < base.length(); i++) {
            hash = (hash ^ base.charAt(i)) * 0x100000001b3L;
        }
        return LOCK_KEY ^ hash;
    }

    /** Forgets what every step has done and failed at; for tests only. */
    public static void resetForTests() {
        STEPS.clear();
        WIDEN_FAILURES.clear();
        clock = System::nanoTime;
    }

    private static void release(Statement statement, boolean locked, long key, String restoreTimeout, SQLException failure) {
        try {
            if (locked) {
                query(statement, "SELECT pg_advisory_unlock(" + key + ")");
            }
        } catch (SQLException e) {
            noteReleaseFailure(e, failure);
        }
        try {
            if (restoreTimeout != null) {
                query(statement, "SELECT set_config('lock_timeout', '" + restoreTimeout.replace("'", "''") + "', false)");
            }
        } catch (SQLException e) {
            noteReleaseFailure(e, failure);
        }
    }

    private static void noteReleaseFailure(SQLException e, SQLException failure) {
        // closing the connection ends the session and with it the lock
        if (failure != null) {
            failure.addSuppressed(e);
        } else {
            LOGGER.debugf("Releasing the PostgreSQL schema lock or restoring lock_timeout failed (the session's end releases the lock): %s",
                    e.getMessage());
        }
    }

    private static String single(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            return rs != null && rs.next() ? rs.getString(1) : null;
        }
    }

    private static void query(Statement statement, String sql) throws SQLException {
        try (ResultSet ignored = statement.executeQuery(sql)) {
            // a SELECT of one value; only its side effect matters
        }
    }

    /**
     * True unless the table's column exists. Answers "missing" when the catalogue
     * cannot say, so the {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} that
     * follows stays the safe fallback.
     */
    public static boolean columnMissing(Statement statement, String table, String column) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = '"
                + identifier(table) + "' AND column_name = '" + identifier(column) + "'")) {
            return rs == null || !rs.next();
        }
    }

    /**
     * True unless the table's column exists and already allows NULL, i.e. whether a
     * {@code DROP NOT NULL} has something to do.
     */
    public static boolean columnHasNotNull(Statement statement, String table, String column) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SELECT is_nullable FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name = '" + identifier(table) + "' AND column_name = '" + identifier(column) + "'")) {
            return rs == null || !rs.next() || !"YES".equals(rs.getString(1));
        }
    }

    /**
     * Changes a {@code VARCHAR(n)} column to {@code TEXT}, only when it still is
     * one. A length limit on a column that holds caller-chosen text turns one
     * over-long value into a failed statement (and, in a batch, into a lost batch)
     * where MongoDB stores it; the change from {@code VARCHAR(n)} to {@code TEXT}
     * rewrites nothing. Does nothing when the catalogue cannot say, and a failure
     * of the {@code ALTER} itself is reported once and does not fail the caller.
     */
    public static void widenToText(Statement statement, String table, String column) throws SQLException {
        boolean varchar;
        try (ResultSet rs = statement.executeQuery("SELECT data_type FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name = '" + identifier(table) + "' AND column_name = '" + identifier(column) + "'")) {
            varchar = rs != null && rs.next() && "character varying".equals(rs.getString(1));
        }
        if (varchar) {
            try {
                statement.execute("ALTER TABLE " + identifier(table) + " ALTER COLUMN " + identifier(column) + " TYPE TEXT");
            } catch (SQLException e) {
                // Best effort: a view or rule over the column, or a lock that did not come in
                // time, must
                // not stop the store working on the column it has. Reported once; retried on
                // the next start.
                if (WIDEN_FAILURES.add(table + "." + column)) {
                    LOGGER.warnf(
                            "Could not change %s.%s from VARCHAR to TEXT (%s); values longer than its limit will be refused until it is changed by hand",
                            table, column, e.getMessage());
                }
            }
        }
    }

    private static String identifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("not a plain identifier: " + name);
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
