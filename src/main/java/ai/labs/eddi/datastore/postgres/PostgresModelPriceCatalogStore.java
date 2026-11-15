/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.pricing.IModelPriceCatalogStore;
import ai.labs.eddi.engine.pricing.ModelPriceCatalogJson;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IModelPriceCatalogStore}. Schema is
 * auto-created on first access.
 *
 * <pre>
 * CREATE TABLE IF NOT EXISTS model_price_catalog (
 *     id       VARCHAR(32) PRIMARY KEY,
 *     revision BIGINT NOT NULL,
 *     document TEXT NOT NULL
 * );
 * </pre>
 *
 * The document column holds the same JSON the MongoDB store keeps.
 */
@ApplicationScoped
@DefaultBean
public class PostgresModelPriceCatalogStore implements IModelPriceCatalogStore {

    public static final String TABLE = "model_price_catalog";
    private static final String DEFAULT_ID = "default";

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS model_price_catalog (
                id VARCHAR(32) PRIMARY KEY,
                revision BIGINT NOT NULL,
                document TEXT NOT NULL
            )
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresModelPriceCatalogStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize the model price catalog table", e);
        }
    }

    @Override
    public Optional<ModelPriceCatalog> read() {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT document FROM model_price_catalog WHERE id = ?")) {
            ps.setString(1, DEFAULT_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ModelPriceCatalogJson.read(rs.getString(1))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the model price catalog", e);
        }
    }

    @Override
    public boolean compareAndSet(long expectedRevision, ModelPriceCatalog next) {
        ensureSchema();
        String sql = expectedRevision == 0
                ? "INSERT INTO model_price_catalog (id, revision, document) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING"
                : "UPDATE model_price_catalog SET revision = ?, document = ? WHERE id = ? AND revision = ?";
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            String json = ModelPriceCatalogJson.write(next);
            if (expectedRevision == 0) {
                ps.setString(1, DEFAULT_ID);
                ps.setLong(2, next.revision());
                ps.setString(3, json);
            } else {
                ps.setLong(1, next.revision());
                ps.setString(2, json);
                ps.setString(3, DEFAULT_ID);
                ps.setLong(4, expectedRevision);
            }
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write the model price catalog", e);
        }
    }
}
