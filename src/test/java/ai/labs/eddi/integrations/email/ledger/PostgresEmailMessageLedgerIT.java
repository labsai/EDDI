/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link EmailMessageLedgerContract} against PostgreSQL (Testcontainers). */
class PostgresEmailMessageLedgerIT extends PostgresTestBase implements EmailMessageLedgerContract, EmailLedgerLogContract {

    private static PostgresEmailMessageLedger store;
    private static DataSource dataSource;

    @BeforeAll
    static void createStore() {
        dataSource = createDataSource();
        store = new PostgresEmailMessageLedger(createDataSourceInstance());
        store.indexBuildLauncher = Runnable::run; // the late index build, synchronously
        store.countPending(); // creates the schema
    }

    @Test
    @DisplayName("a second replica on an existing schema writes while another replica's concurrent index build runs")
    void secondReplicaDoesNotQueueBehindTheBuild() throws Exception {
        // Replica A's CREATE INDEX CONCURRENTLY holds SHARE UPDATE EXCLUSIVE on the
        // table for the whole build; a plain CREATE INDEX (even IF NOT EXISTS, on an
        // index that exists) asks for SHARE, which conflicts and would queue — and
        // the ledger write after it with it. Hold that lock as the build would.
        try (Connection buildingReplica = dataSource.getConnection(); Statement lock = buildingReplica.createStatement()) {
            buildingReplica.setAutoCommit(false);
            lock.execute("LOCK TABLE " + PostgresEmailMessageLedger.TABLE + " IN SHARE UPDATE EXCLUSIVE MODE");

            var replicaB = new PostgresEmailMessageLedger(createDataSourceInstance());
            replicaB.indexBuildLauncher = task -> {
            };
            var row = EmailLedgerLogContract.inbound("int-replica", IEmailMessageLedger.State.DONE, null, EmailLedgerLogContract.T0);
            boolean inserted = CompletableFuture.supplyAsync(() -> replicaB.insertIfAbsent(row)).get(15, TimeUnit.SECONDS);

            assertTrue(inserted, "replica B initialised its schema and wrote without waiting for the build");
            buildingReplica.rollback();
        }
    }

    @Test
    @DisplayName("the mail log index is built (CONCURRENTLY, off the write path) and valid")
    void lateIndexBuilt() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(Boolean.TRUE, PostgresLateIndexes.isValid(connection, "idx_email_ledger_integration_created"));
        }
        // A second build is a no-op, not an error.
        PostgresLateIndexes.build(dataSource::getConnection, 1, PostgresEmailMessageLedger.LATE_INDEXES);
    }

    @Override
    public IEmailMessageLedger ledger() {
        return store;
    }

    @Override
    public void clear() throws Exception {
        truncateTables(dataSource, PostgresEmailMessageLedger.TABLE);
    }

    @Override
    public Set<String> storedFieldNames() throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet columns = statement.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = '"
                        + PostgresEmailMessageLedger.TABLE + "'")) {
            while (columns.next()) {
                names.add(columns.getString(1).replace("_", "").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }
}
