/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.postgres;

import ai.labs.eddi.engine.health.HealthJson;
import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthProfileStore;
import ai.labs.eddi.engine.health.model.HealthProfile;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IHealthProfileStore}: table
 * {@code health_profiles}, primary key the agent id, the profile as JSON text.
 * Created on first use.
 */
@ApplicationScoped
@DefaultBean
public class PostgresHealthProfileStore implements IHealthProfileStore {

    static final String TABLE = "health_profiles";

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS health_profiles (
                agent_id VARCHAR(255) PRIMARY KEY,
                profile TEXT NOT NULL,
                updated_by VARCHAR(512)
            )
            """;
    static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_health_profiles_updated_by ON health_profiles (updated_by)";

    static final String UPSERT = "INSERT INTO health_profiles (agent_id, profile, updated_by) VALUES (?, ?, ?) ON CONFLICT (agent_id) DO UPDATE "
            + "SET profile = EXCLUDED.profile, updated_by = EXCLUDED.updated_by";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresHealthProfileStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_INDEX);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not create the " + TABLE + " schema", e);
        }
    }

    private Connection connection() throws SQLException {
        ensureSchema();
        return dataSourceInstance.get().getConnection();
    }

    @Override
    public Optional<HealthProfile> read(String agentId) {
        try (Connection c = connection();
                PreparedStatement s = c.prepareStatement("SELECT agent_id, profile FROM health_profiles WHERE agent_id = ?")) {
            s.setString(1, agentId);
            List<HealthProfile> found = read(s);
            return found.stream().findFirst();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read a health profile", e);
        }
    }

    @Override
    public Map<String, HealthProfile> readAll(Collection<String> agentIds) {
        Map<String, HealthProfile> found = new LinkedHashMap<>();
        if (agentIds == null || agentIds.isEmpty()) {
            return found;
        }
        try (Connection c = connection();
                PreparedStatement s = c.prepareStatement("SELECT agent_id, profile FROM health_profiles WHERE agent_id = ANY(?)")) {
            s.setArray(1, c.createArrayOf("text", agentIds.toArray(new String[0])));
            for (HealthProfile p : read(s)) {
                found.put(p.agentId(), p);
            }
            return found;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read health profiles", e);
        }
    }

    private static List<HealthProfile> read(PreparedStatement s) throws SQLException {
        List<HealthProfile> found = new ArrayList<>();
        try (ResultSet r = s.executeQuery()) {
            while (r.next()) {
                HealthProfile p = HealthJson.read(r.getString(2), HealthProfile.class);
                found.add(p.withMeta(r.getString(1), Boolean.TRUE, p.updatedAt(), p.updatedBy()));
            }
        }
        return found;
    }

    @Override
    public void write(HealthProfile profile) {
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(UPSERT)) {
            s.setString(1, profile.agentId());
            s.setString(2, HealthJson.write(profile));
            s.setString(3, profile.updatedBy());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not store a health profile", e);
        }
    }

    @Override
    public boolean delete(String agentId) {
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("DELETE FROM health_profiles WHERE agent_id = ?")) {
            s.setString(1, agentId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not delete a health profile", e);
        }
    }

    @Override
    public long clearAuthor(String userId) {
        if (userId == null) {
            return 0;
        }
        List<HealthProfile> authored;
        try (Connection c = connection();
                PreparedStatement s = c.prepareStatement("SELECT agent_id, profile FROM health_profiles WHERE updated_by = ?")) {
            s.setString(1, userId);
            authored = read(s);
        } catch (SQLException e) {
            throw new HealthStoreException("Could not clear the author of health profiles", e);
        }
        for (HealthProfile p : authored) {
            write(p.withMeta(p.agentId(), p.stored(), p.updatedAt(), null));
        }
        return authored.size();
    }
}
