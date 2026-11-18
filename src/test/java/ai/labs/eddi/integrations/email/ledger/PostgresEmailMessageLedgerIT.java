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

import static org.junit.jupiter.api.Assertions.assertEquals;

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
