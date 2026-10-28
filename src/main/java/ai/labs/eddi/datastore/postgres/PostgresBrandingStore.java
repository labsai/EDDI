/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.branding.BrandingJson;
import ai.labs.eddi.configs.branding.IBrandingStore;
import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandDocument;
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
 * PostgreSQL implementation of {@link IBrandingStore}. Schema is auto-created
 * on first access.
 *
 * <pre>
 * CREATE TABLE IF NOT EXISTS branding (
 *     id       VARCHAR(32) PRIMARY KEY,
 *     revision BIGINT NOT NULL,
 *     document TEXT NOT NULL
 * );
 * CREATE TABLE IF NOT EXISTS branding_assets (
 *     slot         VARCHAR(32) PRIMARY KEY,
 *     hash         VARCHAR(80) NOT NULL,
 *     content_type VARCHAR(64) NOT NULL,
 *     data         BYTEA NOT NULL
 * );
 * </pre>
 *
 * The document column holds the same JSON the MongoDB store keeps, so both
 * backends read back an identical brand.
 */
@ApplicationScoped
@DefaultBean
public class PostgresBrandingStore implements IBrandingStore {

    private static final String BRAND_ID = "brand";

    private static final String CREATE_BRANDING = """
            CREATE TABLE IF NOT EXISTS branding (
                id VARCHAR(32) PRIMARY KEY,
                revision BIGINT NOT NULL,
                document TEXT NOT NULL
            )
            """;

    private static final String CREATE_ASSETS = """
            CREATE TABLE IF NOT EXISTS branding_assets (
                slot VARCHAR(32) PRIMARY KEY,
                hash VARCHAR(80) NOT NULL,
                content_type VARCHAR(64) NOT NULL,
                data BYTEA NOT NULL
            )
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresBrandingStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_BRANDING);
            stmt.execute(CREATE_ASSETS);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize the branding tables", e);
        }
    }

    @Override
    public Optional<BrandDocument> read() {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT document FROM branding WHERE id = ?")) {
            ps.setString(1, BRAND_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(BrandingJson.read(rs.getString(1))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the brand", e);
        }
    }

    @Override
    public boolean compareAndSet(long expectedRevision, BrandDocument next) {
        ensureSchema();
        String sql = expectedRevision == 0
                ? "INSERT INTO branding (id, revision, document) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING"
                : "UPDATE branding SET revision = ?, document = ? WHERE id = ? AND revision = ?";
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            String json = BrandingJson.write(next);
            if (expectedRevision == 0) {
                ps.setString(1, BRAND_ID);
                ps.setLong(2, next.revision());
                ps.setString(3, json);
            } else {
                ps.setLong(1, next.revision());
                ps.setString(2, json);
                ps.setString(3, BRAND_ID);
                ps.setLong(4, expectedRevision);
            }
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write the brand", e);
        }
    }

    @Override
    public Optional<BrandAsset> readAsset(BrandAssetSlot slot) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT hash, content_type, data FROM branding_assets WHERE slot = ?")) {
            ps.setString(1, slot.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new BrandAsset(slot, rs.getString("hash"), rs.getString("content_type"), rs.getBytes("data")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the brand image " + slot.id(), e);
        }
    }

    @Override
    public void writeAsset(BrandAsset asset) {
        ensureSchema();
        String sql = """
                INSERT INTO branding_assets (slot, hash, content_type, data) VALUES (?, ?, ?, ?)
                ON CONFLICT (slot) DO UPDATE
                SET hash = EXCLUDED.hash, content_type = EXCLUDED.content_type, data = EXCLUDED.data
                """;
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, asset.slot().id());
            ps.setString(2, asset.hash());
            ps.setString(3, asset.contentType());
            ps.setBytes(4, asset.data());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store the brand image " + asset.slot().id(), e);
        }
    }

    @Override
    public void deleteAsset(BrandAssetSlot slot) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM branding_assets WHERE slot = ?")) {
            ps.setString(1, slot.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete the brand image " + slot.id(), e);
        }
    }
}
