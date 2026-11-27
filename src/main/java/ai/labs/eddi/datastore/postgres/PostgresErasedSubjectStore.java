/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.gdpr.IErasedSubjectStore;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IErasedSubjectStore}: table
 * {@code gdpr_erased_subjects (token PRIMARY KEY, erased_at)}. The write is
 * {@code ON CONFLICT DO UPDATE ... GREATEST}, so it is idempotent and two
 * concurrent erasures of one person keep the later time.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresErasedSubjectStore implements IErasedSubjectStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresErasedSubjectStore.class);

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS gdpr_erased_subjects (
                token VARCHAR(160) PRIMARY KEY,
                erased_at BIGINT NOT NULL
            )
            """;

    private static final String UPSERT = "INSERT INTO gdpr_erased_subjects (token, erased_at) VALUES (?, ?) "
            + "ON CONFLICT (token) DO UPDATE SET erased_at = GREATEST(gdpr_erased_subjects.erased_at, EXCLUDED.erased_at)";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresErasedSubjectStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
            LOGGER.info("PostgresErasedSubjectStore schema initialized");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize gdpr_erased_subjects table", e);
        }
    }

    @Override
    public void record(String token, Instant erasedAt) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(UPSERT)) {
            ps.setString(1, token);
            ps.setLong(2, erasedAt.toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record an erased subject", e);
        }
    }

    @Override
    public Optional<Instant> erasedAt(Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Optional.empty();
        }
        ensureSchema();
        List<String> values = new ArrayList<>(tokens);
        String sql = "SELECT MAX(erased_at) FROM gdpr_erased_subjects WHERE token IN (" + String.join(", ", Collections.nCopies(values.size(), "?"))
                + ")";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) {
                ps.setString(i + 1, values.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long latest = rs.getLong(1);
                    if (!rs.wasNull()) {
                        return Optional.of(Instant.ofEpochMilli(latest));
                    }
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read erased subjects", e);
        }
    }
}
