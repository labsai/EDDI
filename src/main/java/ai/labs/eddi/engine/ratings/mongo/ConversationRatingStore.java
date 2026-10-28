/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.mongo;

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
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.setOnInsert;

/**
 * MongoDB implementation of {@link IConversationRatingStore}. Annotated
 * {@code @DefaultBean} so {@code DataStoreProducers} can select PostgreSQL
 * instead.
 * <p>
 * {@link #upsert} is a single {@code findOneAndUpdate} with {@code upsert} on
 * the unique {@code (conversationId, stepIndex)} index: {@code $set} for what
 * the rating says, {@code $setOnInsert} for {@code createdAt}, so a re-rating
 * keeps the original creation time. Two concurrent first ratings can both miss
 * and both try to insert; the loser gets a duplicate-key error (11000) and is
 * retried once, when it finds the winner's row and updates it.
 * <p>
 * Summaries run as aggregation pipelines — the rows are never loaded.
 *
 * @since 6.5.0
 */
@ApplicationScoped
@DefaultBean
public class ConversationRatingStore implements IConversationRatingStore {

    private static final Logger LOGGER = Logger.getLogger(ConversationRatingStore.class);

    static final String COLLECTION_RATINGS = "conversationratings";

    private static final int DUPLICATE_KEY_ERROR_CODE = 11000;

    private static final String ID = "_id";
    private static final String CONVERSATION_ID = "conversationId";
    private static final String STEP_INDEX = "stepIndex";
    private static final String AGENT_ID = "agentId";
    private static final String AGENT_VERSION = "agentVersion";
    private static final String ENVIRONMENT = "environment";
    private static final String CHANNEL = "channel";
    private static final String USER_ID = "userId";
    private static final String SCALE = "scale";
    private static final String SCORE = "score";
    private static final String NORMALIZED_SCORE = "normalizedScore";
    private static final String TAGS = "tags";
    private static final String COMMENT = "comment";
    private static final String CREATED_AT = "createdAt";
    private static final String UPDATED_AT = "updatedAt";
    private static final String REVIEW_STATUS = "reviewStatus";
    private static final String REVIEWED_BY = "reviewedBy";
    private static final String REVIEWED_AT = "reviewedAt";
    private static final String REVIEW_NOTE = "reviewNote";

    private final MongoCollection<Document> collection;

    @Inject
    public ConversationRatingStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION_RATINGS);
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(CONVERSATION_ID), Indexes.ascending(STEP_INDEX)),
                new IndexOptions().name("idx_rating_key").unique(true));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(AGENT_ID), Indexes.descending(CREATED_AT)),
                new IndexOptions().name("idx_rating_agent_created"));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(AGENT_ID), Indexes.ascending(AGENT_VERSION)),
                new IndexOptions().name("idx_rating_agent_version"));
        collection.createIndex(Indexes.ascending(USER_ID), new IndexOptions().name("idx_rating_user"));
        // attention-count: NEW ratings of an agent, by score.
        collection.createIndex(
                Indexes.compoundIndex(Indexes.ascending(REVIEW_STATUS), Indexes.ascending(AGENT_ID), Indexes.ascending(NORMALIZED_SCORE)),
                new IndexOptions().name("idx_rating_attention"));
        // (conversationId) alone is served by the prefix of idx_rating_key.
    }

    @Override
    public ConversationRating upsert(ConversationRating rating) {
        Bson filter = and(eq(CONVERSATION_ID, rating.conversationId()), eq(STEP_INDEX, rating.stepIndex()));
        Date now = Date.from(Instant.now());
        Bson update = combine(
                set(AGENT_ID, rating.agentId()),
                set(AGENT_VERSION, rating.agentVersion()),
                set(ENVIRONMENT, rating.environment()),
                set(CHANNEL, rating.channel()),
                set(USER_ID, rating.userId()),
                set(SCALE, rating.scale().name()),
                set(SCORE, rating.score()),
                set(NORMALIZED_SCORE, rating.normalizedScore()),
                set(TAGS, rating.tags()),
                set(COMMENT, rating.comment()),
                set(UPDATED_AT, now),
                // A re-rating is a new opinion: back to the triage queue.
                set(REVIEW_STATUS, ReviewStatus.NEW.name()),
                set(REVIEWED_BY, null),
                set(REVIEWED_AT, null),
                set(REVIEW_NOTE, null),
                setOnInsert(CREATED_AT, now));
        var options = new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER);
        Document stored;
        try {
            stored = collection.findOneAndUpdate(filter, update, options);
        } catch (MongoException e) {
            if (e.getCode() != DUPLICATE_KEY_ERROR_CODE) {
                throw e;
            }
            // A concurrent first rating inserted between our miss and our insert; the
            // row exists now, so this attempt updates it.
            LOGGER.debugf("Concurrent first rating of %s step %d — retrying as an update", sanitize(rating.conversationId()),
                    rating.stepIndex());
            stored = collection.findOneAndUpdate(filter, update, options);
        }
        return toRating(stored);
    }

    @Override
    public boolean delete(String conversationId, int stepIndex) {
        return collection.deleteOne(and(eq(CONVERSATION_ID, conversationId), eq(STEP_INDEX, stepIndex))).getDeletedCount() > 0;
    }

    @Override
    public List<ConversationRating> readByConversation(String conversationId) {
        List<ConversationRating> ratings = new ArrayList<>();
        for (Document doc : collection.find(eq(CONVERSATION_ID, conversationId)).sort(Sorts.ascending(STEP_INDEX))) {
            ratings.add(toRating(doc));
        }
        return ratings;
    }

    @Override
    public List<ConversationRating> query(RatingFilter filter, RatingSort sort, int index, int limit) {
        List<ConversationRating> ratings = new ArrayList<>();
        for (Document doc : collection.find(toBson(filter)).sort(sortOf(sort)).skip(Math.max(0, index)).limit(Math.max(0, limit))) {
            ratings.add(toRating(doc));
        }
        return ratings;
    }

    @Override
    public long count(RatingFilter filter) {
        return collection.countDocuments(toBson(filter));
    }

    @Override
    public void stream(RatingFilter filter, int maxRows, Consumer<ConversationRating> sink) {
        if (maxRows <= 0) {
            return;
        }
        try (MongoCursor<Document> cursor = collection.find(toBson(filter)).sort(sortOf(RatingSort.NEWEST)).limit(maxRows)
                .batchSize(1000).iterator()) {
            while (cursor.hasNext()) {
                sink.accept(toRating(cursor.next()));
            }
        }
    }

    @Override
    public List<RatingSummary> summarize(RatingFilter filter, RatingGroupBy groupBy) {
        Bson match = toBson(filter);
        Object bucket = bucketExpression(groupBy);

        Document group = new Document(ID, bucket)
                .append("count", new Document("$sum", 1))
                .append("avg", new Document("$avg", "$" + NORMALIZED_SCORE))
                .append("up", countIf(scaleAndScore(Scale.THUMBS, 1)))
                .append("down", countIf(scaleAndScore(Scale.THUMBS, 0)))
                .append("comments", countIf(new Document("$gt", List.of(new Document("$ifNull", List.of("$" + COMMENT, "")), ""))));
        for (int star = 1; star <= 5; star++) {
            group.append("s" + star, countIf(scaleAndScore(Scale.FIVE_STAR, star)));
        }

        List<Bson> pipeline = List.of(new Document("$match", match), new Document("$group", group));
        Map<String, List<TagCount>> tags = topTags(match, bucket);

        List<RatingSummary> buckets = new ArrayList<>();
        for (Document row : collection.aggregate(pipeline)) {
            String key = groupBy == RatingGroupBy.NONE ? RatingGroupBy.ALL_BUCKET : String.valueOf(row.get(ID));
            long[] stars = new long[5];
            for (int star = 1; star <= 5; star++) {
                stars[star - 1] = longOf(row, "s" + star);
            }
            Number avg = row.get("avg", Number.class);
            buckets.add(RatingSummary.of(key, longOf(row, "count"), avg == null ? null : avg.doubleValue(), longOf(row, "up"),
                    longOf(row, "down"), stars, longOf(row, "comments"), tags.getOrDefault(key, List.of())));
        }
        return RatingStoreSupport.finish(buckets, groupBy);
    }

    private Map<String, List<TagCount>> topTags(Bson match, Object bucket) {
        Document group = new Document(ID, new Document("b", bucket).append("t", "$" + TAGS)).append("n", new Document("$sum", 1));
        List<Bson> pipeline = List.of(new Document("$match", match), new Document("$unwind", "$" + TAGS), new Document("$group", group));
        List<BucketTagCount> rows = new ArrayList<>();
        for (Document row : collection.aggregate(pipeline)) {
            Document id = row.get(ID, Document.class);
            Object b = id.get("b");
            rows.add(new BucketTagCount(b == null ? RatingGroupBy.ALL_BUCKET : String.valueOf(b), id.getString("t"), longOf(row, "n")));
        }
        return RatingStoreSupport.topTags(rows);
    }

    @Override
    public List<ConversationRating> readByUserId(String userId, int maxRows) {
        List<ConversationRating> ratings = new ArrayList<>();
        if (userId == null || maxRows <= 0) {
            return ratings;
        }
        for (Document doc : collection.find(eq(USER_ID, userId)).sort(sortOf(RatingSort.NEWEST)).limit(maxRows)) {
            ratings.add(toRating(doc));
        }
        return ratings;
    }

    @Override
    public long deleteByUserId(String userId) {
        if (userId == null) {
            return 0;
        }
        return collection.deleteMany(eq(USER_ID, userId)).getDeletedCount();
    }

    @Override
    public Optional<ConversationRating> readById(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            return Optional.empty();
        }
        Document doc = collection.find(eq(ID, new ObjectId(id))).first();
        return doc == null ? Optional.empty() : Optional.of(toRating(doc));
    }

    @Override
    public Optional<ConversationRating> updateReview(String id, Instant expectedUpdatedAt, ReviewStatus status, String reviewedBy,
                                                     Instant reviewedAt, String reviewNote) {
        if (id == null || !ObjectId.isValid(id) || expectedUpdatedAt == null || status == null) {
            return Optional.empty();
        }
        Bson filter = and(eq(ID, new ObjectId(id)), eq(UPDATED_AT, Date.from(expectedUpdatedAt)));
        Bson update = combine(
                set(REVIEW_STATUS, status.name()),
                set(REVIEWED_BY, reviewedBy),
                set(REVIEWED_AT, reviewedAt == null ? null : Date.from(reviewedAt)),
                set(REVIEW_NOTE, reviewNote));
        Document doc = collection.findOneAndUpdate(filter, update, new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return doc == null ? Optional.empty() : Optional.of(toRating(doc));
    }

    @Override
    public long markReviewed(RatingFilter filter, String reviewedBy, Instant reviewedAt) {
        Bson update = combine(set(REVIEW_STATUS, ReviewStatus.REVIEWED.name()), set(REVIEWED_BY, reviewedBy),
                set(REVIEWED_AT, Date.from(reviewedAt)));
        return collection.updateMany(and(toBson(filter), eq(REVIEW_STATUS, ReviewStatus.NEW.name())), update).getModifiedCount();
    }

    @Override
    public long clearReviewer(String reviewer) {
        if (reviewer == null) {
            return 0;
        }
        return collection.updateMany(eq(REVIEWED_BY, reviewer), set(REVIEWED_BY, null)).getModifiedCount();
    }

    // --- helpers ---

    private static Object bucketExpression(RatingGroupBy groupBy) {
        return switch (groupBy) {
            case NONE -> null;
            case DAY -> dateFormat("%Y-%m-%d");
            case WEEK -> dateFormat("%G-W%V");
            case AGENT_VERSION -> new Document("$ifNull",
                    List.of(new Document("$toString", "$" + AGENT_VERSION), RatingStoreSupport.UNKNOWN_VERSION_BUCKET));
            case CHANNEL -> new Document("$ifNull", List.of("$" + CHANNEL, RatingStoreSupport.UNKNOWN_CHANNEL_BUCKET));
        };
    }

    private static Document dateFormat(String format) {
        return new Document("$dateToString", new Document("format", format).append("date", "$" + CREATED_AT).append("timezone", "UTC"));
    }

    private static Document scaleAndScore(Scale scale, int score) {
        return new Document("$and", List.of(new Document("$eq", List.of("$" + SCALE, scale.name())),
                new Document("$eq", List.of("$" + SCORE, score))));
    }

    private static Document countIf(Document condition) {
        return new Document("$sum", new Document("$cond", List.of(condition, 1, 0)));
    }

    private static long longOf(Document row, String key) {
        Number value = row.get(key, Number.class);
        return value == null ? 0 : value.longValue();
    }

    private static Bson sortOf(RatingSort sort) {
        return sort == RatingSort.LOWEST
                ? Sorts.orderBy(Sorts.ascending(NORMALIZED_SCORE), Sorts.descending(CREATED_AT), Sorts.descending(ID))
                : Sorts.orderBy(Sorts.descending(CREATED_AT), Sorts.descending(ID));
    }

    static Bson toBson(RatingFilter filter) {
        List<Bson> clauses = new ArrayList<>();
        if (filter != null) {
            if (filter.agentId() != null) {
                clauses.add(eq(AGENT_ID, filter.agentId()));
            }
            if (filter.conversationId() != null) {
                clauses.add(eq(CONVERSATION_ID, filter.conversationId()));
            }
            if (filter.agentVersion() != null) {
                clauses.add(eq(AGENT_VERSION, filter.agentVersion()));
            }
            if (filter.environment() != null) {
                clauses.add(eq(ENVIRONMENT, filter.environment()));
            }
            if (filter.from() != null) {
                clauses.add(gte(CREATED_AT, Date.from(filter.from())));
            }
            if (filter.to() != null) {
                clauses.add(lt(CREATED_AT, Date.from(filter.to())));
            }
            if (filter.minScore() != null) {
                clauses.add(gte(NORMALIZED_SCORE, filter.minScore()));
            }
            if (filter.maxScore() != null) {
                clauses.add(lte(NORMALIZED_SCORE, filter.maxScore()));
            }
            if (filter.kind() == RatingKind.CONVERSATION) {
                clauses.add(eq(STEP_INDEX, ConversationRating.CONVERSATION_STEP_INDEX));
            } else if (filter.kind() == RatingKind.MESSAGE) {
                clauses.add(gte(STEP_INDEX, 0));
            }
            if (filter.tag() != null) {
                clauses.add(eq(TAGS, filter.tag()));
            }
            if (Boolean.TRUE.equals(filter.hasComment())) {
                clauses.add(and(ne(COMMENT, null), ne(COMMENT, "")));
            } else if (Boolean.FALSE.equals(filter.hasComment())) {
                clauses.add(or(eq(COMMENT, null), eq(COMMENT, "")));
            }
            if (filter.reviewStatus() != null) {
                clauses.add(eq(REVIEW_STATUS, filter.reviewStatus().name()));
            }
            if (Boolean.TRUE.equals(filter.needsAttention())) {
                clauses.add(eq(REVIEW_STATUS, ReviewStatus.NEW.name()));
                clauses.add(or(lte(NORMALIZED_SCORE, RatingFilter.ATTENTION_SCORE), and(ne(COMMENT, null), ne(COMMENT, ""))));
            }
        }
        return clauses.isEmpty() ? new Document() : and(clauses);
    }

    private static ConversationRating toRating(Document doc) {
        Object id = doc.get(ID);
        String scaleRaw = doc.getString(SCALE);
        Number version = doc.get(AGENT_VERSION, Number.class);
        Number normalized = doc.get(NORMALIZED_SCORE, Number.class);
        Number score = doc.get(SCORE, Number.class);
        Number step = doc.get(STEP_INDEX, Number.class);
        List<String> tags = doc.getList(TAGS, String.class);
        return new ConversationRating(
                id instanceof ObjectId oid ? oid.toHexString() : String.valueOf(id),
                doc.getString(CONVERSATION_ID),
                step == null ? ConversationRating.CONVERSATION_STEP_INDEX : step.intValue(),
                doc.getString(AGENT_ID),
                version == null ? null : version.intValue(),
                doc.getString(ENVIRONMENT),
                doc.getString(CHANNEL),
                doc.getString(USER_ID),
                scaleRaw == null ? Scale.THUMBS : Scale.valueOf(scaleRaw),
                score == null ? 0 : score.intValue(),
                normalized == null ? 0 : normalized.doubleValue(),
                tags,
                doc.getString(COMMENT),
                instantOf(doc, CREATED_AT),
                instantOf(doc, UPDATED_AT),
                reviewStatusOf(doc.getString(REVIEW_STATUS)),
                doc.getString(REVIEWED_BY),
                instantOf(doc, REVIEWED_AT),
                doc.getString(REVIEW_NOTE));
    }

    private static ReviewStatus reviewStatusOf(String raw) {
        return raw == null ? ReviewStatus.NEW : ReviewStatus.valueOf(raw);
    }

    private static Instant instantOf(Document doc, String field) {
        Object value = doc.get(field);
        return value instanceof Date date ? date.toInstant() : null;
    }
}
