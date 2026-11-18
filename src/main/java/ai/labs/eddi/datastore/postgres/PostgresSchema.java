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
 * <li><b>One advisory lock for all DDL</b> ({@link #LOCK_KEY}, distinct from
 * the startup lock so a store creating its schema during startup never waits
 * for its own JVM). Replicas wait for one another; the wait is bounded by
 * {@link #LOCK_TIMEOUT_SECONDS} and then fails instead of hanging a request.
 * <li><b>Once per JVM and database.</b> Concurrent first requests of the same
 * store run the DDL once; the others wait and find it done.
 * <li><b>Failures surface.</b> A failed creation throws {@link SQLException} to
 * the caller (after logging its cause) and is retried by the next call; it is
 * never recorded as done.
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
    static final int LOCK_TIMEOUT_SECONDS = 120;

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
            run(dataSource, name, work);
            step.done = true;
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
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            boolean locked = false;
            SQLException failure = null;
            try {
                query(statement, "SELECT set_config('lock_timeout', '" + LOCK_TIMEOUT_SECONDS + "s', false)");
                query(statement, "SELECT pg_advisory_lock(" + LOCK_KEY + ")");
                locked = true;
                query(statement, "SELECT set_config('lock_timeout', '0', false)");
                work.run(connection, statement);
            } catch (SQLException e) {
                failure = e;
                LOGGER.errorf(e, "Creating the PostgreSQL schema for %s failed (SQLState %s); the next call retries it", name, e.getSQLState());
                throw e;
            } finally {
                release(statement, locked, failure);
            }
        }
    }

    private static void release(Statement statement, boolean locked, SQLException failure) {
        try {
            if (locked) {
                query(statement, "SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
            } else {
                query(statement, "SELECT set_config('lock_timeout', '0', false)");
            }
        } catch (SQLException e) {
            // closing the connection ends the session and with it the lock
            if (failure != null) {
                failure.addSuppressed(e);
            } else {
                LOGGER.debugf("Releasing the PostgreSQL schema lock failed (the session's end releases it): %s", e.getMessage());
            }
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
     * rewrites nothing. Does nothing when the catalogue cannot say.
     */
    public static void widenToText(Statement statement, String table, String column) throws SQLException {
        boolean varchar;
        try (ResultSet rs = statement.executeQuery("SELECT data_type FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name = '" + identifier(table) + "' AND column_name = '" + identifier(column) + "'")) {
            varchar = rs != null && rs.next() && "character varying".equals(rs.getString(1));
        }
        if (varchar) {
            statement.execute("ALTER TABLE " + identifier(table) + " ALTER COLUMN " + identifier(column) + " TYPE TEXT");
        }
    }

    private static String identifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("not a plain identifier: " + name);
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
