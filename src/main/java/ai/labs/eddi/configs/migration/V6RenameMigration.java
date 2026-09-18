/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.RenameCollectionOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.regex.Pattern;

import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.ID_FIELD;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Filters.regex;

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

    /** The field {@link #ENVIRONMENT_REWRITES} applies to. */
    private static final String FIELD_ENVIRONMENT = "environment";

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

    /**
     * Latches once the migration is known not to be pending, so that
     * {@link #isPending()} stops reading the migration log on every call. Only ever
     * set from false to true, and only after a completed migration has been
     * observed, so a stale read cannot un-complete it.
     */
    private volatile boolean knownNotPending;

    @Inject
    public V6RenameMigration(MongoDatabase database, IMigrationLogStore migrationLogStore,
            @ConfigProperty(name = "eddi.migration.v6-rename.enabled", defaultValue = "false") boolean enabled) {
        this.database = database;
        this.migrationLogStore = migrationLogStore;
        this.enabled = enabled;
    }

    /**
     * Whether this migration has been asked for but has not completed yet — i.e.
     * whether the collections may still be under their EDDI 5 names.
     *
     * <p>
     * A caller that would read an absent config as "the config is gone" has to wait
     * for this to be false. The agent configs live in {@code bots} until this
     * migration renames them, and {@code agents} does not exist at all, so on a
     * first boot against an EDDI 5 database every deployed agent reads as deleted.
     * That is what made {@code AgentDeploymentManagement.checkDeployments()} —
     * which runs on its own ten-second schedule, not after the migrations — retire
     * the deployment rows of every agent in the database.
     * </p>
     *
     * <p>
     * Disabled means not pending: nobody asked for a rename, so the collection
     * names are whatever they already are. That distinction matters because the
     * property defaults to false, so "no completion entry" is the permanent state
     * of every installation that never needed the migration. Pending is otherwise
     * the fail-safe answer — a migration log that cannot be read leaves the
     * question open, and the caller that waits loses ten seconds where the caller
     * that proceeds deletes data.
     * </p>
     */
    public boolean isPending() {
        if (!enabled || knownNotPending) {
            return false;
        }
        try {
            if (migrationLogStore.readMigrationLog(MIGRATION_KEY) == null) {
                return true;
            }
        } catch (Exception e) {
            LOGGER.warnf("Could not read the V6 rename migration log (%s) — treating the migration as still pending", e.getMessage());
            return true;
        }
        knownNotPending = true;
        return false;
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
                    + "documents: %s. Merge them manually and start again. An empty v6 counterpart is NOT a conflict "
                    + "and is dropped automatically. Nothing was changed and the migration was NOT marked complete.",
                    String.join(", ", conflicts));
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
     * Exact document count, used for the one decision in this migration that
     * destroys data: whether a rename may drop its target. {@code
     * estimatedDocumentCount()} reads collection metadata that can be stale after
     * an unclean shutdown, and a wrongly-zero estimate would drop a populated
     * collection. Returns -1 when the count cannot be established, which callers
     * must treat as "not provably empty".
     * <p>
     * Package-private for testing.
     */
    long exactDocumentCount(String collectionName) {
        try {
            return database.getCollection(collectionName).countDocuments();
        } catch (Exception e) {
            LOGGER.warnf("  Could not count documents in %s (%s) — treating it as non-empty", collectionName, e.getMessage());
            return -1;
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
     * <p>
     * MongoDB's {@code renameCollection} fails with NamespaceExists (48) whenever
     * the target namespace merely <em>exists</em> — being empty does not help. EDDI
     * creates those v6 namespaces itself (every {@code MongoResourceStorage}
     * constructor calls {@code createIndex}, which implicitly creates the
     * collection), so booting the v6 binary once against a v5 database before
     * enabling this migration leaves empty {@code agents}, {@code workflows},
     * {@code rulesets}, … behind. Without {@code dropTarget} that leftover would
     * fail the rename, abort the run, and — because the app keeps booting and
     * re-creating those namespaces — do so identically on every restart, so the
     * migration could never complete.
     * <p>
     * We therefore drop a target we have just proven to be empty. Dropping an empty
     * namespace loses nothing (its indexes are re-created by the store on the next
     * boot), while a populated target is never dropped: it keeps
     * {@code dropTarget=false}, still fails with 48, and is reported — that case is
     * a real merge conflict which {@link #detectCollectionRenameConflicts()}
     * refuses up front.
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
            boolean targetIsProvablyEmpty = exactDocumentCount(newName) == 0;
            oldCollection.renameCollection(target, new RenameCollectionOptions().dropTarget(targetIsProvablyEmpty));
            LOGGER.infof("  Renamed collection: %s → %s", oldName, newName);
            return true;
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == NAMESPACE_EXISTS_ERROR_CODE) {
                // Target namespace already exists and holds documents, or its size could
                // not be established (an existing target proven empty is dropped as part
                // of the rename above) — this is not a skip we may shrug off, the v5
                // documents would be lost to the migration.
                LOGGER.errorf("  Cannot rename %s → %s: the target collection already exists and is not provably empty", oldName,
                        newName);
            } else {
                LOGGER.warnf("  Failed to rename collection %s → %s: %s", oldName, newName, e.getMessage());
            }
            return false;
        } catch (Exception e) {
            LOGGER.warnf("  Failed to rename collection %s → %s: %s", oldName, newName, e.getMessage());
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
     *
     * <p>
     * This is the expensive step of the migration, and it is expensive in
     * proportion to total conversation bytes rather than to the work it has to do.
     * On a real staging upgrade the startup migrations took 24 minutes, of which
     * about 20 were this method on {@code conversationmemories} alone: 195
     * documents averaging 410 KB, so 80 MB read and rewritten one document at a
     * time in Java. A read-only {@code mongodump} of the same collection took 14
     * minutes on the same cluster, so the read pass dominates. On production
     * volumes that is the difference between minutes and hours of downtime, so the
     * collection is asked up front whether it holds anything to rewrite at all.
     * </p>
     *
     * <p>
     * Be precise about what that buys, because it is less than it looks. Two of the
     * three conditions are server-side counts and cost nothing. The third — a URI
     * nested at arbitrary depth — cannot be expressed as a filter at all, so
     * proving its absence means reading the collection. So the pre-check removes
     * the rewrite pass, not the read, and a collection with nothing to rewrite was
     * already performing no writes in that pass. Removing the read as well would
     * mean moving the rewrite itself server-side ({@code updateMany} with
     * {@code $rename} and {@code $set} for the two field conditions), which is a
     * larger change than this one and is not attempted here.
     * </p>
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

        if (hasNothingToMigrate(collection, collectionName)) {
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
            Object envObj = doc.get(FIELD_ENVIRONMENT);
            if (envObj instanceof String envStr) {
                for (String[] mapping : ENVIRONMENT_REWRITES) {
                    if (envStr.equalsIgnoreCase(mapping[0])) {
                        doc.put(FIELD_ENVIRONMENT, mapping[1]);
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
     * Whether {@link #migrateEnvironments(String)} can be skipped for this
     * collection, i.e. whether nothing in it would be rewritten.
     *
     * <p>
     * The three conditions mirror, one for one, the three things the rewrite loop
     * can change, and each is derived from the same constant the loop uses so the
     * two cannot drift apart:
     * </p>
     * <ol>
     * <li>a legacy field name from {@link #FIELD_NAME_REWRITES}. The loop looks at
     * {@code doc.containsKey}, i.e. the top level only, so {@code $exists} answers
     * it exactly.</li>
     * <li>a legacy value from {@link #ENVIRONMENT_REWRITES} in {@code environment}.
     * The loop compares with {@code equalsIgnoreCase}, so the filter has to be
     * case-insensitive — a literal {@code $in} would miss {@code "Unrestricted"}
     * and silently skip real work.</li>
     * <li>a v5 URI, which no filter can express and no sample may stand in for; see
     * {@link #holdsLegacyUri}.</li>
     * </ol>
     *
     * <p>
     * The order is deliberate: the two conditions a count can answer come first and
     * short-circuit, so a collection that is obviously dirty — the common case on a
     * first boot, where every conversation still carries {@code botId} — never pays
     * for the scan behind them.
     * </p>
     *
     * <p>
     * Everything that goes wrong here answers "there is work to do": an exception,
     * an unreadable collection, a count that cannot be established. A pre-check
     * that wrongly skips is a data-migration bug — {@code runIfNeeded} would go on
     * to record the migration as complete, so the work would never be retried —
     * while one that wrongly proceeds only costs what this code already cost before
     * it existed.
     * </p>
     */
    private boolean hasNothingToMigrate(MongoCollection<Document> collection, String collectionName) {
        try {
            if (collection.countDocuments(legacyFieldNameFilter()) > 0 || collection.countDocuments(legacyEnvironmentFilter()) > 0) {
                return false;
            }

            if (holdsLegacyUri(collection)) {
                return false;
            }

            LOGGER.infof("  %s: nothing to migrate — no legacy field name, no legacy environment value and no v5 URI in "
                    + "any of its documents. Skipping the rewrite pass.", collectionName);
            return true;
        } catch (Exception e) {
            LOGGER.warnf("  Could not pre-check %s (%s) — running the full rewrite pass", collectionName, e.getMessage());
            return false;
        }
    }

    /** {@code {$or: [{botId: {$exists: true}}, …]}}, straight off the constant. */
    private static Bson legacyFieldNameFilter() {
        var clauses = new ArrayList<Bson>();
        for (String[] mapping : FIELD_NAME_REWRITES) {
            clauses.add(exists(mapping[0]));
        }
        return or(clauses);
    }

    /**
     * {@code environment} matching a legacy value, case-insensitively because the
     * loop uses {@code equalsIgnoreCase}. The value is quoted, so a rewrite whose
     * source ever contains a regex metacharacter cannot turn into a different
     * pattern. Anchored with {@code ^…$}, which over-matches a value ending in a
     * newline — over-counting only costs a rewrite pass that finds nothing.
     */
    private static Bson legacyEnvironmentFilter() {
        var clauses = new ArrayList<Bson>();
        for (String[] mapping : ENVIRONMENT_REWRITES) {
            clauses.add(regex(FIELD_ENVIRONMENT, "^" + Pattern.quote(mapping[0]) + "$", "i"));
        }
        return or(clauses);
    }

    /**
     * Whether any document in the collection holds a URI the rewrite would change.
     *
     * <p>
     * Exhaustive, and it has to be. The rewrite fires when any string at any depth
     * contains a legacy authority or store path, and MongoDB's query language
     * cannot express "some string anywhere in this document contains X": there is
     * no wildcard field path ({@code $**} is an index specification, not a
     * queryable path), {@code $regexMatch} needs a string input and no aggregation
     * expression stringifies a document of arbitrary depth, and the constructs that
     * could — {@code $where}, {@code $function} — are server-side JavaScript,
     * disabled on many deployments. So this condition cannot be answered by a
     * count, and it cannot be answered by a sample either: a legacy URI that a
     * sample missed would be skipped, {@code runIfNeeded} would then record the
     * migration as complete, and that document would never be rewritten or retried.
     * A slow migration is recoverable; that is not.
     * </p>
     *
     * <p>
     * It stops at the first document that needs rewriting, so the systematically
     * dirty collection — the common case on a first boot — pays for one document
     * rather than for the scan. The price of soundness is that a collection whose
     * only stale URI sits at the very end is read twice: once here and once by the
     * pass. That is the worst case, and it is bounded at two reads.
     * </p>
     */
    private boolean holdsLegacyUri(MongoCollection<Document> collection) {
        try (MongoCursor<Document> cursor = collection.find().iterator()) {
            while (cursor.hasNext()) {
                if (containsLegacyUri(cursor.next())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether {@link #rewriteUrisInDocument} would change anything in this value —
     * asked by running the very same {@link #rewriteUriString} over it, so the
     * pre-check cannot disagree with the rewrite about what a legacy URI is.
     */
    private boolean containsLegacyUri(Object value) {
        if (value instanceof String str) {
            return !rewriteUriString(str).equals(str);
        }
        if (value instanceof Document doc) {
            for (String key : doc.keySet()) {
                if (containsLegacyUri(doc.get(key))) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (containsLegacyUri(item)) {
                    return true;
                }
            }
            return false;
        }
        return false;
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
