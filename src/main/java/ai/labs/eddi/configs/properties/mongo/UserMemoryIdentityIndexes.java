/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.mongo;

import ai.labs.eddi.configs.properties.model.Property.Visibility;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

/**
 * The unique upsert identities of the {@code usermemories} collection, and the
 * startup pass that makes them enforceable.
 * <p>
 * {@link MongoUserMemoryStore#buildUpsertFilter} names two identities: one
 * shared document per {@code (userId, key)} among {@code global} entries, and
 * one document per {@code (userId, key, sourceAgentId)} among the non-global
 * ones. Until these indexes existed nothing enforced either — two first writes
 * racing for the same identity both missed the filter and both inserted, and
 * every later upsert then updated whichever of the two the server found first.
 * The partial unique indexes here are the MongoDB counterparts of PostgreSQL's
 * {@code idx_um_upsert_global} and {@code idx_um_upsert_agent}.
 * <p>
 * A deployment that already holds duplicates cannot build a unique index, so
 * the duplicates are merged first: per identity the entry with the newest
 * {@code updatedAt} survives and inherits the summed {@code accessCount} of the
 * group. The pass runs only while an index is missing — once both exist they
 * guarantee there is nothing to merge, so later startups skip the scan.
 */
final class UserMemoryIdentityIndexes {

    private static final Logger LOGGER = Logger.getLogger(UserMemoryIdentityIndexes.class);

    static final String GLOBAL_INDEX = MongoUserMemoryStore.GLOBAL_KEY_INDEX;
    static final String AGENT_INDEX = "idx_um_upsert_agent";

    private static final String FIELD_ID = "_id";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_KEY = "key";
    private static final String FIELD_VISIBILITY = "visibility";
    private static final String FIELD_SOURCE_AGENT_ID = "sourceAgentId";
    private static final String FIELD_ACCESS_COUNT = "accessCount";
    private static final String FIELD_UPDATED_AT = "updatedAt";
    private static final String FIELD_MEMBERS = "members";
    private static final int DUPLICATE_KEY_CODE = 11000;

    /**
     * The non-global visibilities, listed rather than written as
     * {@code $ne: "global"}: a partial index filter accepts {@code $in} but not
     * {@code $ne}. Every non-global value the store writes is here — an entry
     * without a visibility, which only a hand-made document can have, is outside
     * the constraint, exactly as it is outside the dedup below.
     */
    private static final List<String> NON_GLOBAL = List.of(Visibility.self.name(), Visibility.group.name());

    private UserMemoryIdentityIndexes() {
    }

    /** Membership of the global identity: the partial filter of its index. */
    static Bson globalScope() {
        return eq(FIELD_VISIBILITY, Visibility.global.name());
    }

    /** Membership of the per-agent identity: the partial filter of its index. */
    static Bson agentScope() {
        return in(FIELD_VISIBILITY, NON_GLOBAL);
    }

    /**
     * Merges any duplicates, then installs both unique indexes. Never throws: a
     * failure is logged and the store keeps working without the guarantee, as it
     * did before — refusing to start over it would turn a latent data-quality issue
     * into an outage.
     */
    static void ensure(MongoCollection<Document> collection) {
        try {
            if (indexNames(collection).containsAll(List.of(GLOBAL_INDEX, AGENT_INDEX))) {
                return;
            }
            deduplicateAndIndex(collection);
        } catch (MongoException e) {
            LOGGER.errorf(e, "[MEMORY] Could not enforce unique usermemories identities — concurrent first writes may still duplicate entries");
        }
    }

    private static void deduplicateAndIndex(MongoCollection<Document> collection) {
        try {
            deduplicate(collection);
            createIndexes(collection);
        } catch (MongoCommandException e) {
            // A writer on another node (still running a build without these indexes)
            // can insert a fresh duplicate between the merge and the index build. One
            // more pass closes that; a second failure is reported by ensure().
            if (e.getErrorCode() != DUPLICATE_KEY_CODE) {
                throw e;
            }
            LOGGER.warn("[MEMORY] A duplicate usermemories identity appeared during the index build — merging again");
            deduplicate(collection);
            createIndexes(collection);
        }
    }

    private static void createIndexes(MongoCollection<Document> collection) {
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(FIELD_USER_ID), Indexes.ascending(FIELD_KEY)),
                new IndexOptions().name(GLOBAL_INDEX).unique(true).partialFilterExpression(globalScope()));
        collection.createIndex(
                Indexes.compoundIndex(Indexes.ascending(FIELD_USER_ID), Indexes.ascending(FIELD_KEY), Indexes.ascending(FIELD_SOURCE_AGENT_ID)),
                new IndexOptions().name(AGENT_INDEX).unique(true).partialFilterExpression(agentScope()));
    }

    /**
     * Merges every duplicated identity in the collection.
     *
     * @return the number of documents removed
     */
    static long deduplicate(MongoCollection<Document> collection) {
        long removed = deduplicate(collection, globalScope(), new Document(FIELD_USER_ID, "$" + FIELD_USER_ID).append(FIELD_KEY, "$" + FIELD_KEY));
        removed += deduplicate(collection, agentScope(), new Document(FIELD_USER_ID, "$" + FIELD_USER_ID).append(FIELD_KEY, "$" + FIELD_KEY)
                .append(FIELD_SOURCE_AGENT_ID, "$" + FIELD_SOURCE_AGENT_ID));
        if (removed > 0) {
            LOGGER.infof("[MEMORY] Merged duplicate usermemories identities: %d redundant entries removed", removed);
        }
        return removed;
    }

    private static long deduplicate(MongoCollection<Document> collection, Bson scope, Document identity) {
        // Only the fields the merge needs travel back; the values stay on the server.
        var member = new Document(FIELD_ID, "$" + FIELD_ID).append(FIELD_UPDATED_AT, "$" + FIELD_UPDATED_AT).append(FIELD_ACCESS_COUNT,
                "$" + FIELD_ACCESS_COUNT);
        var pipeline = List.of(Aggregates.match(scope),
                Aggregates.group(identity, Accumulators.push(FIELD_MEMBERS, member), Accumulators.sum("count", 1)),
                Aggregates.match(Filters.gt("count", 1)));

        long removed = 0;
        for (Document group : collection.aggregate(pipeline).allowDiskUse(true)) {
            removed += mergeGroup(collection, group.getList(FIELD_MEMBERS, Document.class));
        }
        return removed;
    }

    private static long mergeGroup(MongoCollection<Document> collection, List<Document> members) {
        // updatedAt is an Instant.toString(), whose fraction has a variable length —
        // "…:00Z" sorts after "…:00.5Z" as a string although it is earlier. Compare
        // the parsed instants; the id breaks ties so every node picks the same one.
        List<Document> newestFirst = new ArrayList<>(members);
        newestFirst.sort(Comparator.comparing((Document d) -> parseInstant(d.get(FIELD_UPDATED_AT)))
                .thenComparing(d -> d.getObjectId(FIELD_ID)).reversed());

        Document survivor = newestFirst.getFirst();
        long accessCount = 0;
        Set<ObjectId> redundant = new HashSet<>();
        for (Document m : newestFirst) {
            if (m.get(FIELD_ACCESS_COUNT) instanceof Number n) {
                accessCount += n.longValue();
            }
            if (m != survivor) {
                redundant.add(m.getObjectId(FIELD_ID));
            }
        }

        // Count first, delete second: a crash in between over-counts a recall
        // statistic on the retry, where the reverse order would lose it.
        collection.updateOne(eq(FIELD_ID, survivor.getObjectId(FIELD_ID)), Updates.set(FIELD_ACCESS_COUNT, saturatedInt(accessCount)));
        return collection.deleteMany(in(FIELD_ID, redundant)).getDeletedCount();
    }

    /** accessCount is read back as an int, so the merged sum must stay one. */
    private static int saturatedInt(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private static Instant parseInstant(Object value) {
        if (value instanceof String s) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e) {
                return Instant.MIN;
            }
        }
        return Instant.MIN;
    }

    private static Set<String> indexNames(MongoCollection<Document> collection) {
        Set<String> names = new HashSet<>();
        for (Document index : collection.listIndexes()) {
            names.add(index.getString("name"));
        }
        return names;
    }
}
