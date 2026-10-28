/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link IEmailMessageLedger}.
 * <p>
 * {@link #insertIfAbsent} is {@code INSERT … ON CONFLICT DO NOTHING} against
 * the two unique constraints. {@link #claimDue} is one {@code UPDATE} over a
 * {@code FOR UPDATE SKIP LOCKED} selection of due rows, re-checking the state
 * and due time in its own {@code WHERE}: a second sweeper skips the rows the
 * first has locked, and finds them no longer due once it commits.
 */
@ApplicationScoped
@DefaultBean
public class PostgresEmailMessageLedger implements IEmailMessageLedger {

    private static final Logger LOGGER = Logger.getLogger(PostgresEmailMessageLedger.class);

    static final String TABLE = "email_message_ledger";

    private static final String COLUMNS = "id, integration_id, kind, direction, message_id, conversation_id, source_ref, idempotency_key, "
            + "recipient, state, attempts, next_attempt_at, claim_token, provider_id, last_error, created_at, updated_at, expires_at";

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS email_message_ledger (
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

    private static final String[] INDEXES = {"CREATE INDEX IF NOT EXISTS idx_email_ledger_due ON email_message_ledger (state, next_attempt_at)",
            "CREATE INDEX IF NOT EXISTS idx_email_ledger_recipient ON email_message_ledger (recipient)",
            "CREATE INDEX IF NOT EXISTS idx_email_ledger_rate ON email_message_ledger (recipient, kind, created_at)",
            "CREATE INDEX IF NOT EXISTS idx_email_ledger_expires ON email_message_ledger (expires_at)",
            "CREATE INDEX IF NOT EXISTS idx_email_ledger_message_id ON email_message_ledger (message_id)",
            "CREATE INDEX IF NOT EXISTS idx_email_ledger_inbound_sender ON email_message_ledger (integration_id, recipient, kind, created_at)"};

    /**
     * Resolved lazily: on a MongoDB deployment the datasource bean is inactive, and
     * resolving it at construction aborts the boot (see
     * {@code PostgresOAuthStateStore}).
     */
    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresEmailMessageLedger(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private Connection connection() throws SQLException {
        createSchema();
        return dataSourceInstance.get().getConnection();
    }

    private synchronized void createSchema() throws SQLException {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            for (String index : INDEXES) {
                statement.execute(index);
            }
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to create the %s schema", TABLE);
            throw e;
        }
    }

    @Override
    public boolean insertIfAbsent(LedgerEntry entry) {
        String sql = "INSERT INTO " + TABLE + " (" + COLUMNS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, entry.id());
            statement.setString(i++, entry.integrationId());
            statement.setString(i++, entry.kind().name());
            statement.setString(i++, entry.direction().name());
            statement.setString(i++, entry.messageId());
            statement.setString(i++, entry.conversationId());
            statement.setString(i++, entry.sourceRef());
            statement.setString(i++, entry.idempotencyKey());
            statement.setString(i++, entry.recipient());
            statement.setString(i++, entry.state().name());
            statement.setInt(i++, entry.attempts());
            statement.setTimestamp(i++, timestamp(entry.nextAttemptAt()));
            statement.setString(i++, entry.claimToken());
            statement.setString(i++, IEmailMessageLedger.cap(entry.providerId(), MAX_PROVIDER_ID));
            statement.setString(i++, IEmailMessageLedger.cap(entry.lastError(), MAX_LAST_ERROR));
            statement.setTimestamp(i++, timestamp(entry.createdAt()));
            statement.setTimestamp(i++, timestamp(entry.updatedAt()));
            statement.setTimestamp(i, timestamp(entry.expiresAt()));
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write an email ledger row", e);
        }
    }

    @Override
    public Optional<LedgerEntry> findById(String id) {
        return findOne("id", id);
    }

    @Override
    public Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey) {
        return findOne("idempotency_key", idempotencyKey);
    }

    private Optional<LedgerEntry> findOne(String column, String value) {
        List<LedgerEntry> found = query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE " + column + " = ?", value);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<LedgerEntry> claimDue(Kind kind, Instant now, Instant leaseUntil, int limit) {
        String sql = "UPDATE " + TABLE + " SET claim_token = ?, next_attempt_at = ?, attempts = attempts + 1, updated_at = ? " //
                + "WHERE id IN (SELECT id FROM " + TABLE + " WHERE state = 'PENDING' AND kind = ? AND next_attempt_at <= ? " //
                + "ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED) " //
                + "AND state = 'PENDING' AND next_attempt_at <= ? RETURNING " + COLUMNS;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setTimestamp(2, timestamp(leaseUntil));
            statement.setTimestamp(3, timestamp(now));
            statement.setString(4, kind.name());
            statement.setTimestamp(5, timestamp(now));
            statement.setInt(6, limit);
            statement.setTimestamp(7, timestamp(now));
            List<LedgerEntry> claimed = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    claimed.add(toEntry(rows));
                }
            }
            return claimed;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to claim due email ledger rows", e);
        }
    }

    @Override
    public boolean complete(String id, String claimToken, State terminal, String providerId, String lastError, Instant now) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException("complete() needs a terminal state, not " + terminal);
        }
        String sql = "UPDATE " + TABLE + " SET state = ?, provider_id = ?, last_error = ?, claim_token = NULL, updated_at = ? "
                + "WHERE id = ? AND state = 'PENDING' AND claim_token = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, terminal.name());
            statement.setString(2, IEmailMessageLedger.cap(providerId, MAX_PROVIDER_ID));
            statement.setString(3, IEmailMessageLedger.cap(lastError, MAX_LAST_ERROR));
            statement.setTimestamp(4, timestamp(now));
            statement.setString(5, id);
            statement.setString(6, claimToken);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to complete an email ledger row", e);
        }
    }

    @Override
    public boolean reschedule(String id, String claimToken, Instant nextAttemptAt, String lastError, Instant now) {
        String sql = "UPDATE " + TABLE + " SET next_attempt_at = ?, last_error = ?, claim_token = NULL, updated_at = ? "
                + "WHERE id = ? AND state = 'PENDING' AND claim_token = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, timestamp(nextAttemptAt));
            statement.setString(2, IEmailMessageLedger.cap(lastError, MAX_LAST_ERROR));
            statement.setTimestamp(3, timestamp(now));
            statement.setString(4, id);
            statement.setString(5, claimToken);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reschedule an email ledger row", e);
        }
    }

    @Override
    public long countCreatedSince(String recipient, Kind kind, Instant since) {
        String sql = "SELECT COUNT(*) FROM " + TABLE + " WHERE recipient = ? AND kind = ? AND created_at >= ? AND state <> 'SUPPRESSED'";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, recipient);
            statement.setString(2, kind.name());
            statement.setTimestamp(3, timestamp(since));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count email ledger rows", e);
        }
    }

    @Override
    public long countPending() {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + TABLE + " WHERE state = 'PENDING'");
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count pending email ledger rows", e);
        }
    }

    @Override
    public long deleteExpired(Instant now) {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM " + TABLE + " WHERE expires_at <= ?")) {
            statement.setTimestamp(1, timestamp(now));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete expired email ledger rows", e);
        }
    }

    @Override
    public Optional<LedgerEntry> findByMessage(String integrationId, Direction direction, String messageId) {
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE integration_id = ? AND direction = ? AND message_id = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationId);
            statement.setString(2, direction.name());
            statement.setString(3, messageId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(toEntry(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the email ledger", e);
        }
    }

    @Override
    public Optional<LedgerEntry> findOutboundByMessageId(String messageId) {
        List<LedgerEntry> found = query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE message_id = ? AND direction = 'OUT' LIMIT 1", messageId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public boolean transition(String id, State from, State to, String conversationId, Instant nextAttemptAt, String lastError, boolean countAttempt,
                              Instant now) {
        String sql = "UPDATE " + TABLE + " SET state = ?, next_attempt_at = ?, last_error = ?, updated_at = ?, "
                + "conversation_id = COALESCE(?, conversation_id), attempts = attempts + ? WHERE id = ? AND state = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, to.name());
            statement.setTimestamp(2, timestamp(nextAttemptAt));
            statement.setString(3, lastError);
            statement.setTimestamp(4, timestamp(now));
            statement.setString(5, conversationId);
            statement.setInt(6, countAttempt ? 1 : 0);
            statement.setString(7, id);
            statement.setString(8, from.name());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to change an email ledger row", e);
        }
    }

    @Override
    public List<LedgerEntry> findDue(Kind kind, State state, Instant now, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE
                + " WHERE state = ? AND kind = ? AND next_attempt_at <= ? ORDER BY next_attempt_at LIMIT ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.name());
            statement.setString(2, kind.name());
            statement.setTimestamp(3, timestamp(now));
            statement.setInt(4, limit);
            List<LedgerEntry> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(toEntry(rows));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read due email ledger rows", e);
        }
    }

    @Override
    public long countInboundTurns(String integrationId, String sender, Instant since) {
        String sql = "SELECT COUNT(*) FROM " + TABLE + " WHERE integration_id = ? AND recipient = ? AND kind = 'TRIGGER' AND created_at >= ? "
                + "AND state IN ('TURN_SUBMITTED', 'DONE')";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, integrationId);
            statement.setString(2, sender);
            statement.setTimestamp(3, timestamp(since));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count inbound email ledger rows", e);
        }
    }

    @Override
    public List<LedgerEntry> findByRecipient(String recipient) {
        return query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE recipient = ?", recipient);
    }

    @Override
    public long deleteByRecipient(String recipient) {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM " + TABLE + " WHERE recipient = ?")) {
            statement.setString(1, recipient);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete email ledger rows", e);
        }
    }

    private List<LedgerEntry> query(String sql, String parameter) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            List<LedgerEntry> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(toEntry(rows));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the email ledger", e);
        }
    }

    private static LedgerEntry toEntry(ResultSet rows) throws SQLException {
        return new LedgerEntry(rows.getString("id"), rows.getString("integration_id"), Kind.valueOf(rows.getString("kind")),
                Direction.valueOf(rows.getString("direction")), rows.getString("message_id"), rows.getString("conversation_id"),
                rows.getString("source_ref"), rows.getString("idempotency_key"), rows.getString("recipient"), State.valueOf(rows.getString("state")),
                rows.getInt("attempts"), instant(rows.getTimestamp("next_attempt_at")), rows.getString("claim_token"), rows.getString("provider_id"),
                rows.getString("last_error"), instant(rows.getTimestamp("created_at")), instant(rows.getTimestamp("updated_at")),
                instant(rows.getTimestamp("expires_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
