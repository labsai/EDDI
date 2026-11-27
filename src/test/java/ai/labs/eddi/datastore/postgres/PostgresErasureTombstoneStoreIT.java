/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.gdpr.erasure.ErasureTombstoneStoreContract;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Erasure tombstone store (PostgreSQL)")
class PostgresErasureTombstoneStoreIT extends PostgresTestBase implements ErasureTombstoneStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private PostgresErasureTombstoneStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresErasureTombstoneStore(createDataSourceInstance());
        // The first call creates the schema; then start from empty tables.
        store.countPendingRemovals(null, null, null);
        truncateTables(DATA_SOURCE, "erasure_tombstones", "erasure_pending_removals", "erasure_meta");
    }

    @Override
    public IErasureTombstoneStore tombstoneStore() {
        return store;
    }

    /**
     * {@code now()} is the start of the writing transaction: of two overlapping
     * writes, the one that started first can commit last. Its {@code written_at}
     * must not move the cutoff back. The later commit is simulated by a stored
     * {@code written_at} ahead of this transaction's {@code now()}.
     */
    @Test
    void anOverlappingEarlierWriteNeverMovesTheWriteTimeBack() throws Exception {
        Instant past = Instant.now().minus(Duration.ofDays(1));
        Instant expires = Instant.now().plus(Duration.ofDays(400));
        store.putTombstones(Kind.USER, List.of("overlap"), past, expires);
        try (Connection conn = DATA_SOURCE.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE erasure_tombstones SET written_at = now() + interval '1 hour' WHERE tkey = 'overlap'");
        }
        Instant later = store.erasedAt(Kind.USER, "overlap", Instant.now()).orElseThrow();

        store.putTombstones(Kind.USER, List.of("overlap"), past, expires);

        Instant after = store.erasedAt(Kind.USER, "overlap", Instant.now()).orElseThrow();
        assertFalse(after.isBefore(later), "the cutoff moved back from " + later + " to " + after);
    }

    /**
     * The write time is taken when the row is written, not when the transaction
     * started: a tombstone write that waited for the row lock records the end of
     * that wait, so content captured during it is not before the cutoff.
     */
    @Test
    void theWriteTimeIncludesAWaitForTheRowLock() throws Exception {
        Instant past = Instant.now().minus(Duration.ofDays(1));
        Instant expires = Instant.now().plus(Duration.ofDays(400));
        store.putTombstones(Kind.USER, List.of("locked"), past, expires);
        Instant released;
        try (Connection holder = DATA_SOURCE.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement stmt = holder.createStatement()) {
                stmt.execute("SELECT 1 FROM erasure_tombstones WHERE tkey = 'locked' FOR UPDATE");
                ExecutorService writer = Executors.newSingleThreadExecutor();
                try {
                    Future<?> waiting = writer.submit(() -> store.putTombstones(Kind.USER, List.of("locked"), past, expires));
                    // Not a sleep: wait until the database says the write is blocked by this
                    // holder, or the test proves nothing.
                    // Polled on its own connection: pg_stat_activity is a per-transaction snapshot.
                    int holderPid;
                    try (ResultSet rs = stmt.executeQuery("SELECT pg_backend_pid()")) {
                        rs.next();
                        holderPid = rs.getInt(1);
                    }
                    boolean blocked = false;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!blocked && System.nanoTime() < deadline && !waiting.isDone()) {
                        try (Connection probe = DATA_SOURCE.getConnection();
                                PreparedStatement ps = probe.prepareStatement(
                                        "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))")) {
                            ps.setInt(1, holderPid);
                            try (ResultSet rs = ps.executeQuery()) {
                                rs.next();
                                blocked = rs.getInt(1) > 0;
                            }
                        }
                        if (!blocked) {
                            Thread.sleep(20);
                        }
                    }
                    if (!blocked && waiting.isDone()) {
                        waiting.get(); // a write that failed fast: its own exception, not a setup message
                    }
                    assertTrue(blocked, "setup: the tombstone write never waited for the row lock");
                    try (ResultSet rs = stmt.executeQuery("SELECT clock_timestamp()")) {
                        rs.next();
                        released = rs.getObject(1, OffsetDateTime.class).toInstant();
                    }
                    holder.commit();
                    waiting.get(10, TimeUnit.SECONDS);
                } finally {
                    writer.shutdownNow();
                }
            }
        }
        Instant cutoff = store.erasedAt(Kind.USER, "locked", Instant.now()).orElseThrow();
        assertFalse(cutoff.isBefore(released), "the cutoff " + cutoff + " is from before the lock was released at " + released);
    }

    @Test
    void theSchemaIsIdempotentAndIndexed() throws Exception {
        // A second store on the same database runs the DDL again (every node, every
        // start).
        new PostgresErasureTombstoneStore(createDataSourceInstance()).countPendingRemovals(null, null, null);
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT indexname FROM pg_indexes WHERE tablename IN ('erasure_tombstones', 'erasure_pending_removals', 'erasure_meta')")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.containsAll(List.of("erasure_tombstones_pkey", "idx_erasure_tombstones_expiry", "erasure_pending_removals_pkey",
                "idx_erasure_pending_user", "idx_erasure_pending_conversation", "idx_erasure_pending_created", "erasure_meta_pkey")),
                indexes.toString());
    }
}
