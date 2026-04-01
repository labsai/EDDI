package ai.labs.eddi.configs.properties.mongo;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import com.mongodb.client.result.DeleteResult;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.descending;

/**
 * MongoDB implementation of {@link IUserMemoryStore}.
 * <p>
 * All data lives in a single {@code usermemories} collection. Flat property
 * methods ({@link #readProperties}, {@link #mergeProperties},
 * {@link #deleteProperties}) operate on {@code global} entries.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
@IfBuildProfile("!postgres")
public class MongoUserMemoryStore implements IUserMemoryStore {

    private static final Logger LOGGER = Logger.getLogger(MongoUserMemoryStore.class);

    private static final String COLLECTION_MEMORIES = "usermemories";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_KEY = "key";
    private static final String FIELD_VALUE = "value";
    private static final String FIELD_CATEGORY = "category";
    private static final String FIELD_VISIBILITY = "visibility";
    private static final String FIELD_SOURCE_AGENT_ID = "sourceAgentId";
    private static final String FIELD_GROUP_IDS = "groupIds";
    private static final String FIELD_SOURCE_CONVERSATION_ID = "sourceConversationId";
    private static final String FIELD_CONFLICTED = "conflicted";
    private static final String FIELD_ACCESS_COUNT = "accessCount";
    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String FIELD_UPDATED_AT = "updatedAt";

    private final MongoCollection<Document> memoriesCollection;

    @Inject
    public MongoUserMemoryStore(MongoDatabase database) {
        RuntimeUtilities.checkNotNull(database, "database");
        this.memoriesCollection = database.getCollection(COLLECTION_MEMORIES);
        ensureIndexes();
    }

    private void ensureIndexes() {
        // Primary lookup index
        memoriesCollection
                .createIndex(
                        Indexes.compoundIndex(Indexes.ascending(FIELD_USER_ID), Indexes.ascending(FIELD_VISIBILITY),
                                Indexes.ascending(FIELD_SOURCE_AGENT_ID), Indexes.ascending(FIELD_KEY)),
                        new IndexOptions().name("idx_user_vis_agent_key").background(true));

        // Ordering index for recall
        memoriesCollection.createIndex(Indexes.compoundIndex(Indexes.ascending(FIELD_USER_ID), Indexes.descending(FIELD_UPDATED_AT)),
                new IndexOptions().name("idx_user_updated").background(true));

        // Category filtering
        memoriesCollection.createIndex(Indexes.compoundIndex(Indexes.ascending(FIELD_USER_ID), Indexes.ascending(FIELD_CATEGORY)),
                new IndexOptions().name("idx_user_category").background(true));
    }

    // === Flat property view (reads/writes global entries in usermemories) ===

    @Override
    public Properties readProperties(String userId) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(userId, FIELD_USER_ID);

        // Query all global entries for this user and convert to flat Properties map
        Bson filter = and(eq(FIELD_USER_ID, userId), eq(FIELD_VISIBILITY, Visibility.global.name()));
        Properties properties = new Properties();

        for (Document doc : memoriesCollection.find(filter)) {
            String key = doc.getString(FIELD_KEY);
            Object value = doc.get(FIELD_VALUE);
            if (key != null && value != null) {
                properties.put(key, value);
            }
        }

        return properties.isEmpty() ? null : properties;
    }

    @Override
    public void mergeProperties(String userId, Properties properties) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(userId, FIELD_USER_ID);
        RuntimeUtilities.checkNotNull(properties, "properties");
        if (properties.isEmpty()) {
            return;
        }

        // Upsert each key-value pair as a global entry in usermemories
        Instant now = Instant.now();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String key = entry.getKey();
            if ("_id".equals(key) || FIELD_USER_ID.equals(key))
                continue;

            Bson filter = and(eq(FIELD_USER_ID, userId), eq(FIELD_KEY, key), eq(FIELD_VISIBILITY, Visibility.global.name()));
            Bson update = Updates.combine(Updates.set(FIELD_VALUE, entry.getValue()), Updates.set(FIELD_UPDATED_AT, now.toString()),
                    Updates.setOnInsert(FIELD_USER_ID, userId), Updates.setOnInsert(FIELD_KEY, key),
                    Updates.setOnInsert(FIELD_VISIBILITY, Visibility.global.name()), Updates.setOnInsert(FIELD_CATEGORY, "property"),
                    Updates.setOnInsert(FIELD_CREATED_AT, now.toString()), Updates.setOnInsert(FIELD_ACCESS_COUNT, 0));

            memoriesCollection.updateOne(filter, update, new UpdateOptions().upsert(true));
        }
    }

    @Override
    public void deleteProperties(String userId) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(userId, FIELD_USER_ID);
        // Delete all global entries for this user
        memoriesCollection.deleteMany(and(eq(FIELD_USER_ID, userId), eq(FIELD_VISIBILITY, Visibility.global.name())));
    }

    // === Structured entries ===

    @Override
    public String upsert(UserMemoryEntry entry) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(entry, "entry");
        RuntimeUtilities.checkNotNull(entry.userId(), FIELD_USER_ID);
        RuntimeUtilities.checkNotNull(entry.key(), FIELD_KEY);

        Bson filter = buildUpsertFilter(entry);
        Instant now = Instant.now();

        Bson update = Updates.combine(Updates.set(FIELD_VALUE, entry.value()), Updates.set(FIELD_CATEGORY, entry.category()),
                Updates.set(FIELD_VISIBILITY, entry.visibility().name()), Updates.set(FIELD_SOURCE_AGENT_ID, entry.sourceAgentId()),
                Updates.set(FIELD_GROUP_IDS, entry.groupIds()), Updates.set(FIELD_SOURCE_CONVERSATION_ID, entry.sourceConversationId()),
                Updates.set(FIELD_CONFLICTED, entry.conflicted()), Updates.set(FIELD_UPDATED_AT, now.toString()),
                Updates.setOnInsert(FIELD_USER_ID, entry.userId()), Updates.setOnInsert(FIELD_KEY, entry.key()),
                Updates.setOnInsert(FIELD_CREATED_AT, now.toString()), Updates.setOnInsert(FIELD_ACCESS_COUNT, 0));

        var options = new UpdateOptions().upsert(true);

        // Check for cross-agent global overwrite
        if (entry.visibility() == Visibility.global) {
            Document existing = memoriesCollection.find(filter).first();
            if (existing != null) {
                String existingAgent = existing.getString(FIELD_SOURCE_AGENT_ID);
                if (existingAgent != null && !existingAgent.equals(entry.sourceAgentId())) {
                    LOGGER.infof("[MEMORY] Cross-agent global overwrite: key='%s', user='%s', " + "previous agent='%s', new agent='%s'", entry.key(),
                            entry.userId(), existingAgent, entry.sourceAgentId());
                }
            }
        }

        var result = memoriesCollection.updateOne(filter, update, options);
        if (result.getUpsertedId() != null) {
            return result.getUpsertedId().asObjectId().getValue().toHexString();
        }

        // Existing document updated — return its ID
        Document existing = memoriesCollection.find(filter).first();
        return existing != null ? existing.getObjectId("_id").toHexString() : null;
    }

    @Override
    public void deleteEntry(String entryId) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(entryId, "entryId");
        memoriesCollection.deleteOne(eq("_id", new ObjectId(entryId)));
    }

    // === Queries ===

    @Override
    public List<UserMemoryEntry> getVisibleEntries(String userId, String agentId, List<String> groupIds, String recallOrder, int maxEntries)
            throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(userId, FIELD_USER_ID);

        // Build visibility filter: self(agentId) OR group(groupIds) OR global
        List<Bson> visibilityFilters = new ArrayList<>();

        // Self: entries created by this agent for this user
        visibilityFilters.add(and(eq(FIELD_VISIBILITY, Visibility.self.name()), eq(FIELD_SOURCE_AGENT_ID, agentId)));

        // Group: entries with group visibility where groupIds overlap
        if (groupIds != null && !groupIds.isEmpty()) {
            visibilityFilters.add(and(eq(FIELD_VISIBILITY, Visibility.group.name()), in(FIELD_GROUP_IDS, groupIds)));
        }

        // Global: all global entries for this user
        visibilityFilters.add(eq(FIELD_VISIBILITY, Visibility.global.name()));

        Bson filter = and(eq(FIELD_USER_ID, userId), or(visibilityFilters));
        Bson sort = "most_accessed".equals(recallOrder) ? descending(FIELD_ACCESS_COUNT) : descending(FIELD_UPDATED_AT);

        List<UserMemoryEntry> entries = new ArrayList<>();
        for (Document doc : memoriesCollection.find(filter).sort(sort).limit(maxEntries)) {
            entries.add(documentToEntry(doc));

            // Increment access count when using most_accessed ordering
            if ("most_accessed".equals(recallOrder)) {
                memoriesCollection.updateOne(eq("_id", doc.getObjectId("_id")), Updates.inc(FIELD_ACCESS_COUNT, 1));
            }
        }
        return entries;
    }

    @Override
    public List<UserMemoryEntry> filterEntries(String userId, String query) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(userId, FIELD_USER_ID);
        if (query == null || query.isBlank()) {
            return getAllEntries(userId);
        }

        Pattern pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        Bson filter = and(eq(FIELD_USER_ID, userId), or(Filters.regex(FIELD_KEY, pattern), Filters.regex(FIELD_VALUE, pattern)));

        List<UserMemoryEntry> entries = new ArrayList<>();
        for (Document doc : memoriesCollection.find(filter).sort(descending(FIELD_UPDATED_AT))) {
            entries.add(documentToEntry(doc));
        }
        return entries;
    }

    @Override
    public List<UserMemoryEntry> getEntriesByCategory(String userId, String category) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(userId, FIELD_USER_ID);
        Bson filter = and(eq(FIELD_USER_ID, userId), eq(FIELD_CATEGORY, category));

        List<UserMemoryEntry> entries = new ArrayList<>();
        for (Document doc : memoriesCollection.find(filter).sort(descending(FIELD_UPDATED_AT))) {
            entries.add(documentToEntry(doc));
        }
        return entries;
    }

    @Override
    public Optional<UserMemoryEntry> getByKey(String userId, String key) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(userId, FIELD_USER_ID);
        RuntimeUtilities.checkNotNull(key, FIELD_KEY);
        Document doc = memoriesCollection.find(and(eq(FIELD_USER_ID, userId), eq(FIELD_KEY, key))).first();
        return doc != null ? Optional.of(documentToEntry(doc)) : Optional.empty();
    }

    @Override
    public List<UserMemoryEntry> getAllEntries(String userId) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(userId, FIELD_USER_ID);
        List<UserMemoryEntry> entries = new ArrayList<>();
        for (Document doc : memoriesCollection.find(eq(FIELD_USER_ID, userId)).sort(descending(FIELD_UPDATED_AT))) {
            entries.add(documentToEntry(doc));
        }
        return entries;
    }

    // === GDPR ===

    @Override
    public void deleteAllForUser(String userId) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(userId, FIELD_USER_ID);
        DeleteResult result = memoriesCollection.deleteMany(eq(FIELD_USER_ID, userId));
        LOGGER.infof("[MEMORY] GDPR delete-all for user '%s': %d entries removed", userId, result.getDeletedCount());
    }

    @Override
    public long countEntries(String userId) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(userId, FIELD_USER_ID);
        return memoriesCollection.countDocuments(eq(FIELD_USER_ID, userId));
    }

    // === Document conversion ===

    private Bson buildUpsertFilter(UserMemoryEntry entry) {
        if (entry.visibility() == Visibility.global) {
            // Global: single shared entry per (userId, key)
            return and(eq(FIELD_USER_ID, entry.userId()), eq(FIELD_KEY, entry.key()), eq(FIELD_VISIBILITY, Visibility.global.name()));
        }
        // Self/Group: per-agent entries
        return and(eq(FIELD_USER_ID, entry.userId()), eq(FIELD_KEY, entry.key()), eq(FIELD_SOURCE_AGENT_ID, entry.sourceAgentId()));
    }

    private UserMemoryEntry documentToEntry(Document doc) {
        String visStr = doc.getString(FIELD_VISIBILITY);
        Visibility vis;
        try {
            vis = visStr != null ? Visibility.valueOf(visStr) : Visibility.self;
        } catch (IllegalArgumentException e) {
            vis = Visibility.self;
        }

        String createdAtStr = doc.getString(FIELD_CREATED_AT);
        String updatedAtStr = doc.getString(FIELD_UPDATED_AT);

        return new UserMemoryEntry(doc.getObjectId("_id") != null ? doc.getObjectId("_id").toHexString() : null, doc.getString(FIELD_USER_ID),
                doc.getString(FIELD_KEY), doc.get(FIELD_VALUE), doc.getString(FIELD_CATEGORY), vis, doc.getString(FIELD_SOURCE_AGENT_ID),
                doc.getList(FIELD_GROUP_IDS, String.class, List.of()), doc.getString(FIELD_SOURCE_CONVERSATION_ID),
                doc.getBoolean(FIELD_CONFLICTED, false), doc.getInteger(FIELD_ACCESS_COUNT, 0),
                createdAtStr != null ? Instant.parse(createdAtStr) : null, updatedAtStr != null ? Instant.parse(updatedAtStr) : null);
    }
}
