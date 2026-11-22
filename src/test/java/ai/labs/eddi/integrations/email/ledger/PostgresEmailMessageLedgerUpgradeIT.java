/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ledger table written before {@code thread_key} existed: the store adds the
 * column and its index on first use, keeps the old rows, and counts new rows by
 * thread.
 */
class PostgresEmailMessageLedgerUpgradeIT extends PostgresTestBase {

    /** The table as it shipped before the thread key. */
    private static final String PRE_UPGRADE_TABLE = """
            CREATE TABLE email_message_ledger (
                id VARCHAR(64) PRIMARY KEY,
                integration_id VARCHAR(255) NOT NULL,
                kind VARCHAR(16) NOT NULL,
                direction VARCHAR(8) NOT NULL,
                message_id VARCHAR(512) NOT NULL,
                conversation_id VARCHAR(255),
                source_ref VARCHAR(255),
                idempotency_key VARCHAR(255) NOT NULL,
                recipient VARCHAR(320),
                state VARCHAR(32) NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at TIMESTAMP,
                claim_token VARCHAR(64),
                provider_id VARCHAR(255),
                last_error VARCHAR(512),
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP,
                expires_at TIMESTAMP NOT NULL,
                CONSTRAINT uq_email_ledger_idempotency UNIQUE (idempotency_key),
                CONSTRAINT uq_email_ledger_message UNIQUE (integration_id, direction, message_id)
            )
            """;

    @Test
    @DisplayName("a pre-upgrade table gains thread_key and its index, keeps its rows, and counts by thread")
    void upgradesInPlace() throws Exception {
        DataSource dataSource = createDataSource();
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + PostgresEmailMessageLedger.TABLE);
            statement.execute(PRE_UPGRADE_TABLE);
            Timestamp created = Timestamp.from(now.minus(Duration.ofMinutes(5)));
            Timestamp expires = Timestamp.from(now.plus(Duration.ofDays(30)));
            statement.execute("INSERT INTO email_message_ledger (id, integration_id, kind, direction, message_id, idempotency_key, recipient,"
                    + " state, attempts, created_at, expires_at) VALUES ('old-1', 'int-1', 'REPLY', 'OUT', '<old@contoso.com>', 'k-old',"
                    + " 'alice@supplier.com', 'SENT', 1, '" + created + "', '" + expires + "')");
        }

        var store = new PostgresEmailMessageLedger(createDataSourceInstance());
        assertTrue(store.insertIfAbsent(new LedgerEntry("new-1", "int-1", Kind.REPLY, Direction.OUT, "<new@contoso.com>", "conv-1", null, "k-new",
                "alice@supplier.com", State.SENT, 1, null, null, null, null, now, now, now.plus(Duration.ofDays(30)), "t-1")));

        assertNull(store.findById("old-1").orElseThrow().threadKey(), "a row written before the column has no key");
        assertEquals("t-1", store.findById("new-1").orElseThrow().threadKey());
        assertEquals(1, store.countByThreadSince("int-1", "t-1", Kind.REPLY, now.minus(Duration.ofHours(1))));
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet index = statement.executeQuery("SELECT 1 FROM pg_indexes WHERE tablename = '" + PostgresEmailMessageLedger.TABLE
                        + "' AND indexname = 'idx_email_ledger_thread'")) {
            assertTrue(index.next(), "the thread index is created on the upgraded table");
        }
    }
}
