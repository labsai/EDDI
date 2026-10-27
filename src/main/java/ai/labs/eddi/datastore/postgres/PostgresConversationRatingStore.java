/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.RatingStoreSupport;
import ai.labs.eddi.engine.ratings.RatingStoreSupport.BucketTagCount;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.ratings.model.RatingFilter;
import ai.labs.eddi.engine.ratings.model.RatingGroupBy;
import ai.labs.eddi.engine.ratings.model.RatingKind;
import ai.labs.eddi.engine.ratings.model.RatingSort;
import ai.labs.eddi.engine.ratings.model.RatingSummary;
import ai.labs.eddi.engine.ratings.model.RatingSummary.TagCount;
import ai.labs.eddi.engine.ratings.model.ReviewStatus;
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
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * PostgreSQL implementation of {@link IConversationRatingStore}. Annotated
 * {@code @DefaultBean} so {@code DataStoreProducers} can select it when
 * {@code eddi.datastore.type=postgres}.
 * <p>
 * {@link #upsert} is one {@code INSERT … ON CONFLICT (conversation_id,
 * step_index) DO UPDATE … RETURNING}: atomic, and the update list leaves
 * {@code id} and {@code created_at} alone, so a re-rating keeps both. The
 * conversation rating is stored at {@code step_index = -1}, never NULL —
 * PostgreSQL treats NULLs as distinct in a unique constraint, which would let a
 * conversation collect several conversation ratings.
 * <p>
 * Summaries are {@code GROUP BY} queries; the rows are never loaded. Bucket
 * keys are rendered in UTC, the same as the MongoDB store.
 *
 * @since 6.5.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresConversationRatingStore implements IConversationRatingStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresConversationRatingStore.class);

    static final String TABLE = "conversation_ratings";

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS conversation_ratings (
                id VARCHAR(64) PRIMARY KEY,
                conversation_id VARCHAR(255) NOT NULL,
                step_index INTEGER NOT NULL,
                agent_id VARCHAR(255),
                agent_version INTEGER,
                environment VARCHAR(64),
                channel VARCHAR(32),
                user_id VARCHAR(255),
                scale VARCHAR(16) NOT NULL,
                score INTEGER NOT NULL,
                normalized_score DOUBLE PRECISION NOT NULL,
                tags TEXT[] NOT NULL DEFAULT '{}',
                comment TEXT,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                review_status VARCHAR(16) NOT NULL DEFAULT 'NEW',
                reviewed_by VARCHAR(255),
                reviewed_at TIMESTAMPTZ,
                review_note TEXT,
                CONSTRAINT uq_conversation_rating UNIQUE (conversation_id, step_index)
            )
            """;

    private static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_conversation_ratings_agent_created ON conversation_ratings (agent_id, created_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_conversation_ratings_agent_version ON conversation_ratings (agent_id, agent_version)",
            "CREATE INDEX IF NOT EXISTS idx_conversation_ratings_user ON conversation_ratings (user_id)",
            // attention-count: a partial index over the untriaged ratings only.
            "CREATE INDEX IF NOT EXISTS idx_conversation_ratings_attention ON conversation_ratings (agent_id, normalized_score) "
                    + "WHERE review_status = 'NEW'"
            // (conversation_id) alone is served by the prefix of uq_conversation_rating.
    };

    private static final String COLUMNS = "id, conversation_id, step_index, agent_id, agent_version, environment, channel, user_id, "
            + "scale, score, normalized_score, tags, comment, created_at, updated_at, review_status, reviewed_by, reviewed_at, review_note";

    private static final String UPSERT = "INSERT INTO conversation_ratings (" + COLUMNS + ") "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW', NULL, NULL, NULL) "
            + "ON CONFLICT (conversation_id, step_index) DO UPDATE SET "
            + "agent_id = EXCLUDED.agent_id, agent_version = EXCLUDED.agent_version, environment = EXCLUDED.environment, "
            + "channel = EXCLUDED.channel, user_id = EXCLUDED.user_id, scale = EXCLUDED.scale, score = EXCLUDED.score, "
            + "normalized_score = EXCLUDED.normalized_score, tags = EXCLUDED.tags, comment = EXCLUDED.comment, "
            + "updated_at = EXCLUDED.updated_at, "
            // A re-rating is a new opinion: back to the triage queue.
            + "review_status = 'NEW', reviewed_by = NULL, reviewed_at = NULL, review_note = NULL "
            + "RETURNING " + COLUMNS;

    private static final String NEWEST_ORDER = " ORDER BY created_at DESC, id DESC";
    private static final String LOWEST_ORDER = " ORDER BY normalized_score ASC, created_at DESC, id DESC";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresConversationRatingStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            for (String index : CREATE_INDEXES) {
                stmt.execute(index);
            }
            schemaInitialized = true;
            LOGGER.info("PostgresConversationRatingStore schema initialized");
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize conversation_ratings table", e);
            throw new RuntimeException("Failed to initialize conversation_ratings table", e);
        }
    }

    @Override
    public ConversationRating upsert(ConversationRating rating) {
        ensureSchema();
        Instant now = Instant.now();
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(UPSERT)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, rating.conversationId());
            ps.setInt(3, rating.stepIndex());
            ps.setString(4, rating.agentId());
            setNullableInt(ps, 5, rating.agentVersion());
            ps.setString(6, rating.environment());
            ps.setString(7, rating.channel());
            ps.setString(8, rating.userId());
            ps.setString(9, rating.scale().name());
            ps.setInt(10, rating.score());
            ps.setDouble(11, rating.normalizedScore());
            ps.setArray(12, conn.createArrayOf("text", rating.tags().toArray(new String[0])));
            ps.setString(13, rating.comment());
            ps.setObject(14, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            ps.setObject(15, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("upsert returned no row");
                }
                return toRating(rs);
            }
        } catch (SQLException e) {
            LOGGER.errorf(e, "Failed to store the rating of conversation %s step %d", sanitize(rating.conversationId()), rating.stepIndex());
            throw new RuntimeException("Failed to store conversation rating", e);
        }
    }

    @Override
    public boolean delete(String conversationId, int stepIndex) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM conversation_ratings WHERE conversation_id = ? AND step_index = ?")) {
            ps.setString(1, conversationId);
            ps.setInt(2, stepIndex);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete conversation rating", e);
        }
    }

    @Override
    public List<ConversationRating> readByConversation(String conversationId) {
        ensureSchema();
        return select("SELECT " + COLUMNS + " FROM conversation_ratings WHERE conversation_id = ? ORDER BY step_index ASC",
                List.of(conversationId));
    }

    @Override
    public List<ConversationRating> query(RatingFilter filter, RatingSort sort, int index, int limit) {
        ensureSchema();
        Where where = where(filter);
        List<Object> params = new ArrayList<>(where.params());
        params.add(Math.max(0, limit));
        params.add(Math.max(0, index));
        return select("SELECT " + COLUMNS + " FROM conversation_ratings" + where.sql()
                + (sort == RatingSort.LOWEST ? LOWEST_ORDER : NEWEST_ORDER) + " LIMIT ? OFFSET ?", params);
    }

    @Override
    public long count(RatingFilter filter) {
        ensureSchema();
        Where where = where(filter);
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM conversation_ratings" + where.sql())) {
            bind(ps, where.params());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count conversation ratings", e);
        }
    }

    @Override
    public void stream(RatingFilter filter, int maxRows, Consumer<ConversationRating> sink) {
        if (maxRows <= 0) {
            return;
        }
        ensureSchema();
        Where where = where(filter);
        List<Object> params = new ArrayList<>(where.params());
        params.add(maxRows);
        String sql = "SELECT " + COLUMNS + " FROM conversation_ratings" + where.sql() + NEWEST_ORDER + " LIMIT ?";
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            // A server-side cursor needs a transaction; without one the driver
            // materialises the whole result.
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setFetchSize(1000);
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sink.accept(toRating(rs));
                    }
                }
            } finally {
                conn.rollback();
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to stream conversation ratings", e);
        }
    }

    @Override
    public List<RatingSummary> summarize(RatingFilter filter, RatingGroupBy groupBy) {
        ensureSchema();
        Where where = where(filter);
        String bucket = bucketExpression(groupBy);
        StringBuilder sql = new StringBuilder("SELECT ").append(bucket).append(" AS bucket, count(*) AS cnt, avg(normalized_score) AS avg_n, ")
                .append(countIf("scale = 'THUMBS' AND score = 1")).append(" AS up, ")
                .append(countIf("scale = 'THUMBS' AND score = 0")).append(" AS down, ");
        for (int star = 1; star <= 5; star++) {
            sql.append(countIf("scale = 'FIVE_STAR' AND score = " + star)).append(" AS s").append(star).append(", ");
        }
        sql.append(countIf("comment IS NOT NULL AND comment <> ''")).append(" AS comments FROM conversation_ratings").append(where.sql());
        if (groupBy != RatingGroupBy.NONE) {
            sql.append(" GROUP BY ").append(bucket);
        }

        Map<String, List<TagCount>> tags = topTags(where, groupBy, bucket);
        List<RatingSummary> buckets = new ArrayList<>();
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, where.params());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = groupBy == RatingGroupBy.NONE ? RatingGroupBy.ALL_BUCKET : rs.getString("bucket");
                    long[] stars = new long[5];
                    for (int star = 1; star <= 5; star++) {
                        stars[star - 1] = rs.getLong("s" + star);
                    }
                    double avg = rs.getDouble("avg_n");
                    Double average = rs.wasNull() ? null : avg;
                    buckets.add(RatingSummary.of(key, rs.getLong("cnt"), average, rs.getLong("up"), rs.getLong("down"), stars,
                            rs.getLong("comments"), tags.getOrDefault(key, List.of())));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to summarize conversation ratings", e);
        }
        return RatingStoreSupport.finish(buckets, groupBy);
    }

    private Map<String, List<TagCount>> topTags(Where where, RatingGroupBy groupBy, String bucket) {
        String sql = "SELECT " + bucket
                + " AS bucket, t.tag AS tag, count(*) AS n FROM conversation_ratings CROSS JOIN LATERAL unnest(tags) AS t(tag)"
                + where.sql() + " GROUP BY " + (groupBy == RatingGroupBy.NONE ? "t.tag" : bucket + ", t.tag");
        List<BucketTagCount> rows = new ArrayList<>();
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, where.params());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = groupBy == RatingGroupBy.NONE ? RatingGroupBy.ALL_BUCKET : rs.getString("bucket");
                    rows.add(new BucketTagCount(key, rs.getString("tag"), rs.getLong("n")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to aggregate conversation rating tags", e);
        }
        return RatingStoreSupport.topTags(rows);
    }

    @Override
    public List<ConversationRating> readByUserId(String userId, int maxRows) {
        if (userId == null || maxRows <= 0) {
            return List.of();
        }
        ensureSchema();
        return select("SELECT " + COLUMNS + " FROM conversation_ratings WHERE user_id = ?" + NEWEST_ORDER + " LIMIT ?",
                List.of(userId, maxRows));
    }

    @Override
    public long deleteByUserId(String userId) {
        if (userId == null) {
            return 0;
        }
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM conversation_ratings WHERE user_id = ?")) {
            ps.setString(1, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete conversation ratings of a user", e);
        }
    }

    @Override
    public Optional<ConversationRating> readById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        ensureSchema();
        List<ConversationRating> found = select("SELECT " + COLUMNS + " FROM conversation_ratings WHERE id = ?", List.of(id));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Optional<ConversationRating> updateReview(String id, Instant expectedUpdatedAt, ReviewStatus status, String reviewedBy,
                                                     Instant reviewedAt, String reviewNote) {
        if (id == null || expectedUpdatedAt == null || status == null) {
            return Optional.empty();
        }
        ensureSchema();
        String sql = "UPDATE conversation_ratings SET review_status = ?, reviewed_by = ?, reviewed_at = ?, review_note = ? "
                + "WHERE id = ? AND updated_at = ? RETURNING " + COLUMNS;
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, reviewedBy);
            ps.setObject(3, reviewedAt == null ? null : OffsetDateTime.ofInstant(reviewedAt, ZoneOffset.UTC));
            ps.setString(4, reviewNote);
            ps.setString(5, id);
            ps.setObject(6, OffsetDateTime.ofInstant(expectedUpdatedAt, ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toRating(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update the review of a conversation rating", e);
        }
    }

    @Override
    public long markReviewed(RatingFilter filter, String reviewedBy, Instant reviewedAt) {
        ensureSchema();
        Where where = where(filter);
        List<Object> params = new ArrayList<>();
        params.add(reviewedBy);
        params.add(OffsetDateTime.ofInstant(reviewedAt, ZoneOffset.UTC));
        params.addAll(where.params());
        String sql = "UPDATE conversation_ratings SET review_status = 'REVIEWED', reviewed_by = ?, reviewed_at = ?"
                + (where.sql().isEmpty() ? " WHERE " : where.sql() + " AND ") + "review_status = 'NEW'";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark conversation ratings reviewed", e);
        }
    }

    @Override
    public long clearReviewer(String reviewer) {
        if (reviewer == null) {
            return 0;
        }
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("UPDATE conversation_ratings SET reviewed_by = NULL WHERE reviewed_by = ?")) {
            ps.setString(1, reviewer);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear a reviewer from conversation ratings", e);
        }
    }

    // --- helpers ---

    /**
     * A WHERE clause and its parameters. The SQL is built only from fixed
     * fragments; every value is a bound parameter.
     */
    record Where(String sql, List<Object> params) {
    }

    static Where where(RatingFilter filter) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (filter != null) {
            add(clauses, params, "agent_id = ?", filter.agentId());
            add(clauses, params, "conversation_id = ?", filter.conversationId());
            add(clauses, params, "agent_version = ?", filter.agentVersion());
            add(clauses, params, "environment = ?", filter.environment());
            add(clauses, params, "created_at >= ?", filter.from() == null ? null : OffsetDateTime.ofInstant(filter.from(), ZoneOffset.UTC));
            add(clauses, params, "created_at < ?", filter.to() == null ? null : OffsetDateTime.ofInstant(filter.to(), ZoneOffset.UTC));
            add(clauses, params, "normalized_score >= ?", filter.minScore());
            add(clauses, params, "normalized_score <= ?", filter.maxScore());
            if (filter.kind() == RatingKind.CONVERSATION) {
                clauses.add("step_index = " + ConversationRating.CONVERSATION_STEP_INDEX);
            } else if (filter.kind() == RatingKind.MESSAGE) {
                clauses.add("step_index >= 0");
            }
            add(clauses, params, "? = ANY(tags)", filter.tag());
            if (Boolean.TRUE.equals(filter.hasComment())) {
                clauses.add("(comment IS NOT NULL AND comment <> '')");
            } else if (Boolean.FALSE.equals(filter.hasComment())) {
                clauses.add("(comment IS NULL OR comment = '')");
            }
            add(clauses, params, "review_status = ?", filter.reviewStatus() == null ? null : filter.reviewStatus().name());
            if (Boolean.TRUE.equals(filter.needsAttention())) {
                clauses.add("review_status = 'NEW'");
                clauses.add("(normalized_score <= ? OR (comment IS NOT NULL AND comment <> ''))");
                params.add(RatingFilter.ATTENTION_SCORE);
            }
        }
        return new Where(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), params);
    }

    private static void add(List<String> clauses, List<Object> params, String clause, Object value) {
        if (value != null) {
            clauses.add(clause);
            params.add(value);
        }
    }

    private static String bucketExpression(RatingGroupBy groupBy) {
        return switch (groupBy) {
            case NONE -> "'" + RatingGroupBy.ALL_BUCKET + "'";
            case DAY -> "to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')";
            case WEEK -> "to_char(created_at AT TIME ZONE 'UTC', 'IYYY-\"W\"IW')";
            case AGENT_VERSION -> "COALESCE(CAST(agent_version AS TEXT), '" + RatingStoreSupport.UNKNOWN_VERSION_BUCKET + "')";
            case CHANNEL -> "COALESCE(channel, '" + RatingStoreSupport.UNKNOWN_CHANNEL_BUCKET + "')";
        };
    }

    private static String countIf(String condition) {
        return "count(*) FILTER (WHERE " + condition + ")";
    }

    private List<ConversationRating> select(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            List<ConversationRating> ratings = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ratings.add(toRating(rs));
                }
            }
            return ratings;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read conversation ratings", e);
        }
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            int position = i + 1;
            if (value instanceof String s) {
                ps.setString(position, s);
            } else if (value instanceof Integer n) {
                ps.setInt(position, n);
            } else if (value instanceof Double d) {
                ps.setDouble(position, d);
            } else {
                ps.setObject(position, value);
            }
        }
    }

    private static void setNullableInt(PreparedStatement ps, int position, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(position, Types.INTEGER);
        } else {
            ps.setInt(position, value);
        }
    }

    private static ConversationRating toRating(ResultSet rs) throws SQLException {
        int version = rs.getInt("agent_version");
        Integer agentVersion = rs.wasNull() ? null : version;
        Array tagArray = rs.getArray("tags");
        List<String> tags = tagArray == null ? List.of() : Arrays.asList((String[]) tagArray.getArray());
        String scaleRaw = rs.getString("scale");
        return new ConversationRating(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getInt("step_index"),
                rs.getString("agent_id"),
                agentVersion,
                rs.getString("environment"),
                rs.getString("channel"),
                rs.getString("user_id"),
                scaleRaw == null ? Scale.THUMBS : Scale.valueOf(scaleRaw),
                rs.getInt("score"),
                rs.getDouble("normalized_score"),
                tags,
                rs.getString("comment"),
                instantOf(rs, "created_at"),
                instantOf(rs, "updated_at"),
                reviewStatusOf(rs.getString("review_status")),
                rs.getString("reviewed_by"),
                instantOf(rs, "reviewed_at"),
                rs.getString("review_note"));
    }

    private static ReviewStatus reviewStatusOf(String raw) {
        return raw == null ? ReviewStatus.NEW : ReviewStatus.valueOf(raw);
    }

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
