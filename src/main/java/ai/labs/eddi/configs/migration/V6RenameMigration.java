/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.ID_FIELD;
import static com.mongodb.client.model.Filters.eq;

/**
 * V6 Rename Migration — rewrites legacy eddi:// URIs, store paths, environment
 * values, and descriptor type fields across all MongoDB collections.
 * <p>
 * This migration is idempotent: it records completion in the migration_log
 * collection and will not re-run if already applied.
 * <p>
 * Controlled by the config property {@code eddi.migration.v6-rename.enabled}
 * (default: false). Set to true when migrating a v5 database to v6.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class V6RenameMigration {

    private static final Logger LOGGER = Logger.getLogger(V6RenameMigration.class);
    private static final String MIGRATION_KEY = "v6-rename-migration-complete";

    /** MongoDB {@code NamespaceExists} — renameCollection onto an existing name. */
    private static final int NAMESPACE_EXISTS_ERROR_CODE = 48;

    /**
     * URI authority rewrites (old → new). Longest-first to avoid partial matches.
     */
    private static final String[][] URI_AUTHORITY_REWRITES = {{"eddi://ai.labs.regulardictionary/", "eddi://ai.labs.dictionary/"},
            {"eddi://ai.labs.httpcalls/", "eddi://ai.labs.apicalls/"}, {"eddi://ai.labs.behavior/", "eddi://ai.labs.rules/"},
            {"eddi://ai.labs.langchain/", "eddi://ai.labs.llm/"}, {"eddi://ai.labs.package/", "eddi://ai.labs.workflow/"},
            {"eddi://ai.labs.bot/", "eddi://ai.labs.agent/"},};

    /** Store path rewrites (old → new) — applied inside URI strings. */
    private static final String[][] STORE_PATH_REWRITES = {{"regulardictionarystore/regulardictionaries", "dictionarystore/dictionaries"},
            {"httpcallsstore/httpcalls", "apicallstore/apicalls"}, {"behaviorstore/behaviorsets", "rulestore/rulesets"},
            {"langchainstore/langchains", "llmstore/llms"}, {"packagestore/packages", "workflowstore/workflows"},
            {"botstore/bots", "agentstore/agents"},};

    /**
     * MongoDB collection renames (v5 name → v6 name). Each entry also implies a
     * corresponding ".history" rename.
     */
    private static final String[][] COLLECTION_RENAMES = {{"bots", "agents"}, {"packages", "workflows"}, {"behaviorrulesets", "rulesets"},
            {"httpcalls", "apicalls"}, {"langchain", "llms"}, {"regulardictionaries", "dictionaries"},};

    /**
     * BSON field renames inside agent documents (old field → new field). Applied
     * after collection renames so we work on the "agents" collection.
     */
    private static final String[][] AGENT_FIELD_RENAMES = {{"packages", "workflows"},};

    /** Environment value rewrites. */
    private static final String[][] ENVIRONMENT_REWRITES = {{"unrestricted", "production"}, {"restricted", "production"},};

    /**
     * Field-name rewrites for deployment/conversation documents (old Java name →
     * new Java name).
     */
    private static final String[][] FIELD_NAME_REWRITES = {{"botId", "agentId"}, {"botVersion", "agentVersion"},};

    /**
     * All MongoDB collections to scan for URI rewrites. These are the NEW
     * (post-rename) v6 collection names: AgentStore → "agents" WorkflowStore →
     * "workflows" (was "packages") RuleSetStore → "rulesets" (was
     * "behaviorrulesets") ApiCallsStore → "apicalls" (was "httpcalls") OutputStore
     * → "outputs" LlmStore → "llms" (was "langchain") PropertySetterStore →
     * "propertysetter" DictionaryStore → "dictionaries" (was "regulardictionaries")
     * ParserStore → "parsers"
     */
    private static final String[] RESOURCE_COLLECTIONS = {"agents", "workflows", "rulesets", "apicalls", "outputs", "llms", "propertysetter",
            "dictionaries", "parsers",};

    private final MongoDatabase database;
    private final IMigrationLogStore migrationLogStore;
    private final boolean enabled;

    @Inject
    public V6RenameMigration(MongoDatabase database, IMigrationLogStore migrationLogStore,
            @ConfigProperty(name = "eddi.migration.v6-rename.enabled", defaultValue = "false") boolean enabled) {
        this.database = database;
        this.migrationLogStore = migrationLogStore;
        this.enabled = enabled;
    }

    /**
     * Run the v6 rename migration if enabled and not already applied.
     */
    public void runIfNeeded() {
        if (!enabled) {
            LOGGER.info("V6 rename migration is disabled (eddi.migration.v6-rename.enabled=false)");
            return;
        }

        if (migrationLogStore.readMigrationLog(MIGRATION_KEY) != null) {
            LOGGER.info("V6 rename migration already applied — skipping");
            return;
        }

        LOGGER.info("Starting V6 rename migration...");

        // 0a. Refuse to run while a v5 and its v6 counterpart both hold documents:
        // the rename would be skipped, the URI rewrite only scans v6 names, and the
        // v5 documents would be silently left behind on a migration marked complete.
        var conflicts = detectCollectionRenameConflicts();
        if (!conflicts.isEmpty()) {
            LOGGER.errorf("V6 rename migration aborted — these v5 collections and their v6 counterparts both contain "
                    + "documents: %s. Merge them manually (or drop the empty-by-mistake target) and start again. "
                    + "Nothing was changed and the migration was NOT marked complete.", String.join(", ", conflicts));
            return;
        }

        int totalMigrated = 0;

        // 0b. Rename MongoDB collections (v5 → v6 names)
        var renameFailures = renameCollections();
        if (!renameFailures.isEmpty()) {
            LOGGER.errorf("V6 rename migration aborted — these collections could not be renamed: %s. Their documents "
                    + "would not be picked up by the URI rewrite. The migration was NOT marked complete and will run "
                    + "again on the next start.", String.join(", ", renameFailures));
            return;
        }

        // 1. Rename BSON fields in agent documents (packages → workflows)
        totalMigrated += migrateAgentFields();

        // 2. Rewrite URIs in all resource + history collections
        for (String collectionName : RESOURCE_COLLECTIONS) {
            totalMigrated += migrateCollection(collectionName);
            totalMigrated += migrateCollection(collectionName + ".history");
        }

        // 3. Rewrite resource URIs in descriptors
        totalMigrated += migrateDescriptors("descriptors");
        totalMigrated += migrateDescriptors("descriptors.history");

        // 4. Rewrite environment fields in deployment/conversation documents
        totalMigrated += migrateEnvironments("conversationmemories");
        totalMigrated += migrateEnvironments("deployments");

        LOGGER.infof("V6 rename migration complete: %d documents migrated", totalMigrated);

        migrationLogStore.createMigrationLog(new MigrationLog(MIGRATION_KEY));
    }

    /**
     * Detects v5 collections that cannot be renamed because their v6 counterpart
     * already holds documents. Renaming would fail, the v5 documents would never be
     * visited by the URI rewrite (which only scans v6 names), and the migration
     * would still be recorded as complete — so we refuse to start instead.
     * <p>
     * Package-private for testing.
     *
     * @return the conflicting "v5 → v6" pairs, empty when the migration can run
     */
    List<String> detectCollectionRenameConflicts() {
        var conflicts = new ArrayList<String>();
        for (String[] mapping : COLLECTION_RENAMES) {
            addConflictIfBothPopulated(conflicts, mapping[0], mapping[1]);
            addConflictIfBothPopulated(conflicts, mapping[0] + ".history", mapping[1] + ".history");
        }
        return conflicts;
    }

    private void addConflictIfBothPopulated(List<String> conflicts, String oldName, String newName) {
        if (documentCount(oldName) > 0 && documentCount(newName) > 0) {
            conflicts.add(oldName + " → " + newName);
        }
    }

    private long documentCount(String collectionName) {
        try {
            return database.getCollection(collectionName).estimatedDocumentCount();
        } catch (Exception e) {
            // collection may not exist
            return 0;
        }
    }

    /**
     * Rename MongoDB collections from v5 names to v6 names. Each collection and its
     * ".history" counterpart are renamed. Safe to call if collections have already
     * been renamed (skips if old name doesn't exist).
     *
     * @return the "v5 → v6" pairs that hold documents but could not be renamed —
     *         empty when every rename succeeded or was unnecessary
     */
    private List<String> renameCollections() {
        var failures = new ArrayList<String>();
        for (String[] mapping : COLLECTION_RENAMES) {
            collectRenameFailure(failures, mapping[0], mapping[1]);
            collectRenameFailure(failures, mapping[0] + ".history", mapping[1] + ".history");
        }
        return failures;
    }

    private void collectRenameFailure(List<String> failures, String oldName, String newName) {
        if (!renameCollectionIfExists(oldName, newName)) {
            failures.add(oldName + " → " + newName);
        }
    }

    /**
     * Rename a single MongoDB collection if the old name exists.
     *
     * @return true if the rename succeeded or was unnecessary (source missing or
     *         empty), false if documents are still sitting under the v5 name
     */
    private boolean renameCollectionIfExists(String oldName, String newName) {
        try {
            MongoCollection<Document> oldCollection = database.getCollection(oldName);
            if (oldCollection.estimatedDocumentCount() == 0) {
                // Collection either doesn't exist or is empty — nothing to rename
                return true;
            }
            String dbName = database.getName();
            MongoNamespace target = new MongoNamespace(dbName, newName);
            oldCollection.renameCollection(target);
            LOGGER.infof("  Renamed collection: %s → %s", oldName, newName);
            return true;
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == NAMESPACE_EXISTS_ERROR_CODE) {
                return renameOntoExistingTarget(oldName, newName);
            }
            LOGGER.warnf("  Failed to rename collection %s → %s: %s", oldName, newName, e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.warnf("  Failed to rename collection %s → %s: %s", oldName, newName, e.getMessage());
            return false;
        }
    }

    /**
     * Recover from MongoDB error 48 ("target namespace exists").
     * <p>
     * MongoDB refuses {@code renameCollection} onto ANY existing namespace, empty
     * or not — and an empty v6 collection is the NORMAL state on a v5 database,
     * because every store constructor creates its collection as a side effect of
     * ensuring indexes during startup, and this migration runs shortly after.
     * Treating that as a hard failure aborted the entire migration; because the
     * abort also skips {@code createMigrationLog}, it then repeated on every
     * subsequent start, forever, and no v5 database could ever be migrated.
     * <p>
     * So make this handler agree with {@link #detectCollectionRenameConflicts()},
     * which already draws the only line that matters: an empty target holds nothing
     * anyone can lose, so drop it and retry the rename; a populated one is
     * genuinely ambiguous and must still stop the migration. If the target's size
     * cannot be established we abort as well — an unreadable count is not
     * permission to drop.
     *
     * @return true if the retried rename succeeded, false if the migration must
     *         stop
     */
    private boolean renameOntoExistingTarget(String oldName, String newName) {
        long targetCount;
        try {
            // countDocuments, not estimatedDocumentCount: this decides whether to DROP
            // a collection, and metadata-based estimates can be stale after an unclean
            // shutdown. The expected value is 0, so the scan costs nothing.
            targetCount = database.getCollection(newName).countDocuments();
        } catch (Exception e) {
            LOGGER.errorf("  Cannot rename %s → %s: the target already exists and its document count could not be read (%s)", oldName,
                    newName, e.getMessage());
            return false;
        }

        if (targetCount > 0) {
            LOGGER.errorf("  Cannot rename %s → %s: the target collection already holds %d document(s) and %s is not empty. "
                    + "Merge them manually and start again.", oldName, newName, targetCount, oldName);
            return false;
        }

        try {
            database.getCollection(newName).drop();
            database.getCollection(oldName).renameCollection(new MongoNamespace(database.getName(), newName));
            LOGGER.infof("  Renamed collection: %s → %s (dropped the empty %s that startup had created)", oldName, newName, newName);
            return true;
        } catch (Exception e) {
            LOGGER.errorf("  Cannot rename %s → %s: dropping the empty target and retrying failed (%s)", oldName, newName, e.getMessage());
            return false;
        }
    }

    /**
     * Rename BSON fields in agent documents (e.g., "packages" → "workflows"). Runs
     * after collection renames so we operate on the "agents" collection.
     */
    private int migrateAgentFields() {
        MongoCollection<Document> collection;
        try {
            collection = database.getCollection("agents");
            if (collection.estimatedDocumentCount() == 0) {
                return 0;
            }
        } catch (Exception e) {
            return 0;
        }

        int migrated = 0;
        for (Document doc : collection.find()) {
            boolean changed = false;

            for (String[] mapping : AGENT_FIELD_RENAMES) {
                if (doc.containsKey(mapping[0])) {
                    doc.put(mapping[1], doc.get(mapping[0]));
                    doc.remove(mapping[0]);
                    changed = true;
                }
            }

            if (changed) {
                var query = eq(ID_FIELD, doc.get(ID_FIELD));
                collection.replaceOne(query, doc);
                migrated++;
            }
        }

        // Also migrate history collection
        try {
            MongoCollection<Document> historyCollection = database.getCollection("agents.history");
            for (Document doc : historyCollection.find()) {
                boolean changed = false;
                for (String[] mapping : AGENT_FIELD_RENAMES) {
                    if (doc.containsKey(mapping[0])) {
                        doc.put(mapping[1], doc.get(mapping[0]));
                        doc.remove(mapping[0]);
                        changed = true;
                    }
                }
                if (changed) {
                    saveDocument(historyCollection, doc, true);
                    migrated++;
                }
            }
        } catch (Exception e) {
            // History collection may not exist
        }

        if (migrated > 0) {
            LOGGER.infof("  agents: renamed %d document fields (packages → workflows)", migrated);
        }
        return migrated;
    }

    /**
     * Migrate a single collection: rewrite all URI strings in all documents.
     */
    private int migrateCollection(String collectionName) {
        MongoCollection<Document> collection;
        try {
            collection = database.getCollection(collectionName);
            // Quick check if collection has any documents
            if (collection.estimatedDocumentCount() == 0) {
                return 0;
            }
        } catch (Exception e) {
            // Collection may not exist
            return 0;
        }

        int migrated = 0;
        for (Document doc : collection.find()) {
            Document rewritten = rewriteUrisInDocument(doc);
            if (rewritten != null) {
                saveDocument(collection, doc, collectionName.endsWith(".history"));
                migrated++;
            }
        }

        if (migrated > 0) {
            LOGGER.infof("  %s: migrated %d documents", collectionName, migrated);
        }
        return migrated;
    }

    /**
     * Migrate descriptor documents: rewrite the 'resource' URI field. Note:
     * descriptors have no separate 'type' field — the resource URI authority (e.g.,
     * "eddi://ai.labs.behavior/...") is what identifies the type.
     */
    private int migrateDescriptors(String collectionName) {
        MongoCollection<Document> collection;
        try {
            collection = database.getCollection(collectionName);
            if (collection.estimatedDocumentCount() == 0) {
                return 0;
            }
        } catch (Exception e) {
            return 0;
        }

        int migrated = 0;
        for (Document doc : collection.find()) {
            Document rewritten = rewriteUrisInDocument(doc);
            if (rewritten != null) {
                saveDocument(collection, doc, collectionName.endsWith(".history"));
                migrated++;
            }
        }

        if (migrated > 0) {
            LOGGER.infof("  %s: migrated %d descriptors", collectionName, migrated);
        }
        return migrated;
    }

    /**
     * Migrate environment fields in conversation memory documents.
     */
    private int migrateEnvironments(String collectionName) {
        MongoCollection<Document> collection;
        try {
            collection = database.getCollection(collectionName);
            if (collection.estimatedDocumentCount() == 0) {
                return 0;
            }
        } catch (Exception e) {
            return 0;
        }

        int migrated = 0;
        for (Document doc : collection.find()) {
            boolean changed = false;

            // Rename old field names (e.g., botId → agentId, botVersion → agentVersion)
            for (String[] mapping : FIELD_NAME_REWRITES) {
                if (doc.containsKey(mapping[0])) {
                    doc.put(mapping[1], doc.get(mapping[0]));
                    doc.remove(mapping[0]);
                    changed = true;
                }
            }

            // Rewrite environment field
            Object envObj = doc.get("environment");
            if (envObj instanceof String envStr) {
                for (String[] mapping : ENVIRONMENT_REWRITES) {
                    if (envStr.equalsIgnoreCase(mapping[0])) {
                        doc.put("environment", mapping[1]);
                        changed = true;
                        break;
                    }
                }
            }

            // Also rewrite any URIs in the conversation memory
            Document uriRewritten = rewriteUrisInDocument(doc);
            changed = changed || uriRewritten != null;

            if (changed) {
                var query = eq(ID_FIELD, doc.get(ID_FIELD));
                collection.replaceOne(query, doc);
                migrated++;
            }
        }

        if (migrated > 0) {
            LOGGER.infof("  %s: migrated %d conversation documents", collectionName, migrated);
        }
        return migrated;
    }

    /**
     * Recursively walk a BSON Document and rewrite any string values that contain
     * legacy eddi:// URIs or store paths.
     *
     * @return the document if any changes were made, null if no changes needed.
     */
    @SuppressWarnings("unchecked")
    private Document rewriteUrisInDocument(Document doc) {
        boolean changed = false;

        for (String key : new ArrayList<>(doc.keySet())) {
            Object val = doc.get(key);

            if (val instanceof String strVal) {
                String rewritten = rewriteUriString(strVal);
                if (!rewritten.equals(strVal)) {
                    doc.put(key, rewritten);
                    changed = true;
                }
            } else if (val instanceof Document nested) {
                Document result = rewriteUrisInDocument(nested);
                changed = changed || result != null;
            } else if (val instanceof List<?> list) {
                changed = rewriteUrisInList((List<Object>) list) || changed;
            }
        }

        return changed ? doc : null;
    }

    /**
     * Recursively walk a BSON list and rewrite URI strings.
     */
    @SuppressWarnings("unchecked")
    private boolean rewriteUrisInList(List<Object> list) {
        boolean changed = false;

        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);

            if (item instanceof String strVal) {
                String rewritten = rewriteUriString(strVal);
                if (!rewritten.equals(strVal)) {
                    list.set(i, rewritten);
                    changed = true;
                }
            } else if (item instanceof Document nested) {
                Document result = rewriteUrisInDocument(nested);
                changed = changed || result != null;
            } else if (item instanceof List<?> nestedList) {
                changed = rewriteUrisInList((List<Object>) nestedList) || changed;
            }
        }

        return changed;
    }

    /**
     * Apply all URI authority and store path rewrites to a single string value.
     * Package-private for testing.
     */
    String rewriteUriString(String value) {
        if (value == null || !value.contains("eddi://")) {
            return value;
        }

        String result = value;

        // Apply authority rewrites (longest-first)
        for (String[] mapping : URI_AUTHORITY_REWRITES) {
            result = result.replace(mapping[0], mapping[1]);
        }

        // Apply store path rewrites
        for (String[] mapping : STORE_PATH_REWRITES) {
            result = result.replace(mapping[0], mapping[1]);
        }

        return result;
    }

    /**
     * Save a document back to its collection.
     */
    @SuppressWarnings("unchecked")
    private void saveDocument(MongoCollection<Document> collection, Document document, boolean isHistory) {
        try {
            if (isHistory) {
                Object idObj = document.get(ID_FIELD);
                if (idObj instanceof Map<?, ?>) {
                    var idMap = (Map<String, Object>) idObj;
                    var query = eq(ID_FIELD, new Document((Map<String, Object>) idMap));
                    collection.replaceOne(query, document);
                }
            } else {
                Object idObj = document.get(ID_FIELD);
                if (idObj instanceof ObjectId) {
                    collection.replaceOne(eq(ID_FIELD, idObj), document);
                } else if (idObj instanceof String) {
                    collection.replaceOne(eq(ID_FIELD, new ObjectId((String) idObj)), document);
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to save migrated document: %s", e.getMessage());
        }
    }
}
