/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.directory;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IUserDirectoryStore}: one row per
 * principal in {@code user_directory}.
 * <p>
 * Case-insensitive lookups compare {@code lower(column)} against a lower-cased
 * parameter, and prefix searches use {@code LIKE} with the input's own
 * wildcards escaped — the prefix is whatever a person typed into a share box,
 * so a {@code %} in it must be a percent sign and not "anything".
 * <p>
 * Schema is auto-created on first access, following
 * {@code PostgresConnectionSettingsStore}.
 */
@ApplicationScoped
@DefaultBean
public class PostgresUserDirectoryStore implements IUserDirectoryStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresUserDirectoryStore.class);

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS user_directory (
                principal TEXT PRIMARY KEY,
                subject TEXT,
                username TEXT,
                email TEXT,
                email_verified BOOLEAN NOT NULL DEFAULT FALSE,
                display_name TEXT,
                teams TEXT[] NOT NULL DEFAULT '{}',
                first_seen TIMESTAMPTZ NOT NULL,
                last_seen TIMESTAMPTZ NOT NULL
            )
            """;

    private static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_user_directory_username ON user_directory (lower(username))",
            "CREATE INDEX IF NOT EXISTS idx_user_directory_email ON user_directory (lower(email))",
            "CREATE INDEX IF NOT EXISTS idx_user_directory_teams ON user_directory USING gin (teams)",
            "CREATE INDEX IF NOT EXISTS idx_user_directory_last_seen ON user_directory (last_seen DESC)"};

    private static final String COLUMNS = "principal, subject, username, email, email_verified, display_name, teams, first_seen, last_seen";

    static final String UPSERT = """
            INSERT INTO user_directory (principal, subject, username, email, email_verified, display_name, teams, first_seen, last_seen)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (principal) DO UPDATE SET
                subject = EXCLUDED.subject,
                username = EXCLUDED.username,
                email = EXCLUDED.email,
                email_verified = EXCLUDED.email_verified,
                display_name = EXCLUDED.display_name,
                teams = EXCLUDED.teams,
                last_seen = EXCLUDED.last_seen
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresUserDirectoryStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        synchronized (this) {
            if (schemaInitialized) {
                return;
            }
            try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
                statement.execute(CREATE_TABLE);
                for (String index : CREATE_INDEXES) {
                    statement.execute(index);
                }
                schemaInitialized = true;
                LOGGER.info("PostgresUserDirectoryStore initialized (table=user_directory)");
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to initialize the user_directory table", e);
            }
        }
    }

    @Override
    public void upsert(DirectoryUser user) {
        ensureSchema();
        Instant now = Instant.now();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, user.principal());
            statement.setString(2, user.subject());
            statement.setString(3, user.username());
            statement.setString(4, user.email());
            statement.setBoolean(5, user.emailVerified());
            statement.setString(6, user.displayName());
            statement.setArray(7, connection.createArrayOf("text", user.teams().toArray(new String[0])));
            statement.setTimestamp(8, Timestamp.from(user.firstSeen() == null ? now : user.firstSeen()));
            statement.setTimestamp(9, Timestamp.from(user.lastSeen() == null ? now : user.lastSeen()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record a directory entry", e);
        }
    }

    @Override
    public Optional<DirectoryUser> find(String principal) {
        if (principal == null) {
            return Optional.empty();
        }
        List<DirectoryUser> found = query("SELECT " + COLUMNS + " FROM user_directory WHERE principal = ?", 1, principal);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Map<String, DirectoryUser> findAll(Collection<String> principals) {
        Map<String, DirectoryUser> result = new LinkedHashMap<>();
        if (principals == null || principals.isEmpty()) {
            return result;
        }
        ensureSchema();
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(principals));
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection
                        .prepareStatement("SELECT " + COLUMNS + " FROM user_directory WHERE principal = ANY(?)")) {
            statement.setArray(1, connection.createArrayOf("text", distinct.toArray(new String[0])));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    DirectoryUser user = toUser(rows);
                    result.put(user.principal(), user);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read directory entries", e);
        }
        return result;
    }

    @Override
    public List<DirectoryUser> findByUsername(String username) {
        String lower = DirectoryUser.lower(username);
        if (lower == null || lower.isEmpty()) {
            return List.of();
        }
        return query("SELECT " + COLUMNS + " FROM user_directory WHERE lower(username) = ? ORDER BY last_seen DESC", 10, lower);
    }

    @Override
    public List<DirectoryUser> findByVerifiedEmail(String email) {
        String lower = DirectoryUser.lower(email);
        if (lower == null || lower.isEmpty()) {
            return List.of();
        }
        return query("SELECT " + COLUMNS + " FROM user_directory WHERE lower(email) = ? AND email_verified ORDER BY last_seen DESC", 10,
                lower);
    }

    @Override
    public List<DirectoryUser> search(String prefix, int limit) {
        String lower = DirectoryUser.lower(prefix);
        if (lower == null || lower.isEmpty()) {
            return query("SELECT " + COLUMNS + " FROM user_directory ORDER BY last_seen DESC", limit);
        }
        String like = escapeLike(lower) + "%";
        return query("SELECT " + COLUMNS + " FROM user_directory WHERE lower(principal) LIKE ? ESCAPE '\\' OR lower(username) LIKE ? ESCAPE '\\'"
                + " OR lower(email) LIKE ? ESCAPE '\\' OR lower(display_name) LIKE ? ESCAPE '\\' ORDER BY last_seen DESC", limit, like, like,
                like, like);
    }

    @Override
    public List<String> searchTeams(String prefix, int limit) {
        ensureSchema();
        String lower = DirectoryUser.lower(prefix);
        String like = "team:" + escapeLike(lower == null ? "" : lower) + "%";
        List<String> teams = new ArrayList<>();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT DISTINCT team FROM user_directory, unnest(teams) AS team WHERE lower(team) LIKE ? ESCAPE '\\' ORDER BY team LIMIT ?")) {
            statement.setString(1, like);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    teams.add(rows.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to search directory teams", e);
        }
        return teams;
    }

    @Override
    public boolean teamExists(String teamSubject) {
        return teamSubject != null && !query("SELECT " + COLUMNS + " FROM user_directory WHERE ? = ANY(teams)", 1, teamSubject).isEmpty();
    }

    @Override
    public List<String> teamMembers(String teamSubject, int limit) {
        if (teamSubject == null) {
            return List.of();
        }
        return query("SELECT " + COLUMNS + " FROM user_directory WHERE ? = ANY(teams) ORDER BY last_seen DESC", limit, teamSubject).stream()
                .map(DirectoryUser::principal).toList();
    }

    @Override
    public boolean delete(String principal) {
        if (principal == null) {
            return false;
        }
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM user_directory WHERE principal = ?")) {
            statement.setString(1, principal);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete a directory entry", e);
        }
    }

    private List<DirectoryUser> query(String sql, int limit, String... params) {
        ensureSchema();
        List<DirectoryUser> users = new ArrayList<>();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql + " LIMIT ?")) {
            int index = 1;
            for (String param : params) {
                statement.setString(index++, param);
            }
            statement.setInt(index, Math.max(1, limit));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    users.add(toUser(rows));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the user directory", e);
        }
        return users;
    }

    /**
     * Escapes LIKE's wildcards and its escape character, so input is matched
     * literally.
     */
    static String escapeLike(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 4);
        for (char c : raw.toCharArray()) {
            if (c == '%' || c == '_' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static DirectoryUser toUser(ResultSet row) throws SQLException {
        Timestamp first = row.getTimestamp("first_seen");
        Timestamp last = row.getTimestamp("last_seen");
        return new DirectoryUser(row.getString("principal"), row.getString("subject"), row.getString("username"), row.getString("email"),
                row.getBoolean("email_verified"), row.getString("display_name"), strings(row.getArray("teams")),
                first == null ? null : first.toInstant(), last == null ? null : last.toInstant());
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            Object values = array.getArray();
            if (values instanceof String[] strings) {
                return List.copyOf(Arrays.asList(strings));
            }
            if (values instanceof Object[] objects) {
                return Arrays.stream(objects).map(String::valueOf).toList();
            }
            return List.of();
        } finally {
            array.free();
        }
    }
}
