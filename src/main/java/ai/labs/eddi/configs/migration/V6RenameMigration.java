/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoNamespace;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
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

import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.ID_FIELD;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.ne;

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
     * MongoDB {@code NamespaceNotFound} — the only collection-access failure that
     * means "nothing to migrate here" rather than "this collection was never read".
     */
    private static final int NAMESPACE_NOT_FOUND_ERROR_CODE = 26;

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
     * The v6 deployment key fields, i.e. the targets of
     * {@link #FIELD_NAME_REWRITES}.
     */
    private static final String FIELD_AGENT_ID = "agentId";
    private static final String FIELD_AGENT_VERSION = "agentVersion";

    private static final String COLLECTION_DEPLOYMENTS = "deployments";

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

        MigrationResult total = MigrationResult.NOTHING;

        // 0b. Rename MongoDB collections (v5 → v6 names)
        var renameFailures = renameCollections();
        if (!renameFailures.isEmpty()) {
            LOGGER.errorf("V6 rename migration aborted — these collections could not be renamed: %s. Their documents "
                    + "would not be picked up by the URI rewrite. The migration was NOT marked complete and will run "
                    + "again on the next start.", String.join(", ", renameFailures));
            return;
        }

        // 1. Rename BSON fields in agent documents (packages → workflows)
        total = total.plus(migrateAgentFields());

        // 2. Rewrite URIs in all resource + history collections
        for (String collectionName : RESOURCE_COLLECTIONS) {
            total = total.plus(migrateCollection(collectionName));
            total = total.plus(migrateCollection(collectionName + ".history"));
        }

        // 3. Rewrite resource URIs in descriptors
        total = total.plus(migrateDescriptors("descriptors"));
        total = total.plus(migrateDescriptors("descriptors.history"));

        // 4. Rewrite environment fields in deployment/conversation documents
        for (String collectionName : List.of("conversationmemories", COLLECTION_DEPLOYMENTS)) {
            total = total.plus(migrateEnvironments(collectionName));
        }

        // Every pass, not only the environment one: a collection that could not be
        // read and a document that could not be written both land here, and either
        // means this migration has not done what the completion log would claim.
        if (total.failed() > 0) {
            LOGGER.errorf("V6 rename migration migrated %d document(s), but %d could not be migrated (logged above). "
                    + "The migration was NOT marked complete and will run again on the next start.",
                    total.migrated(), total.failed());
            return;
        }

        LOGGER.infof("V6 rename migration complete: %d documents migrated", total.migrated());

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
    private MigrationResult migrateAgentFields() {
        MongoCollection<Document> collection;
        try {
            collection = collectionToScan("agents");
        } catch (UnreadableCollectionException e) {
            return unreadable("agents", e);
        }

        int migrated = 0;
        int failed = 0;
        for (Document doc : collection == null ? List.<Document>of() : collection.find()) {
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
        MongoCollection<Document> historyCollection;
        try {
            historyCollection = collectionToScan("agents.history");
        } catch (UnreadableCollectionException e) {
            MigrationResult unread = unreadable("agents.history", e);
            return new MigrationResult(migrated, failed).plus(unread);
        }
        for (Document doc : historyCollection == null ? List.<Document>of() : historyCollection.find()) {
            boolean changed = false;
            for (String[] mapping : AGENT_FIELD_RENAMES) {
                if (doc.containsKey(mapping[0])) {
                    doc.put(mapping[1], doc.get(mapping[0]));
                    doc.remove(mapping[0]);
                    changed = true;
                }
            }
            if (changed) {
                if (saveDocument(historyCollection, doc, true)) {
                    migrated++;
                } else {
                    failed++;
                }
            }
        }

        if (migrated > 0) {
            LOGGER.infof("  agents: renamed %d document fields (packages → workflows)", migrated);
        }
        return new MigrationResult(migrated, failed);
    }

    /**
     * Migrate a single collection: rewrite all URI strings in all documents.
     */
    private MigrationResult migrateCollection(String collectionName) {
        MongoCollection<Document> collection;
        try {
            collection = collectionToScan(collectionName);
        } catch (UnreadableCollectionException e) {
            return unreadable(collectionName, e);
        }
        if (collection == null) {
            return MigrationResult.NOTHING;
        }

        int migrated = 0;
        int failed = 0;
        for (Document doc : collection.find()) {
            Document rewritten = rewriteUrisInDocument(doc);
            if (rewritten != null) {
                if (saveDocument(collection, doc, collectionName.endsWith(".history"))) {
                    migrated++;
                } else {
                    failed++;
                }
            }
        }

        if (migrated > 0) {
            LOGGER.infof("  %s: migrated %d documents", collectionName, migrated);
        }
        return new MigrationResult(migrated, failed);
    }

    /**
     * Migrate descriptor documents: rewrite the 'resource' URI field. Note:
     * descriptors have no separate 'type' field — the resource URI authority (e.g.,
     * "eddi://ai.labs.behavior/...") is what identifies the type.
     */
    private MigrationResult migrateDescriptors(String collectionName) {
        MongoCollection<Document> collection;
        try {
            collection = collectionToScan(collectionName);
        } catch (UnreadableCollectionException e) {
            return unreadable(collectionName, e);
        }
        if (collection == null) {
            return MigrationResult.NOTHING;
        }

        int migrated = 0;
        int failed = 0;
        for (Document doc : collection.find()) {
            Document rewritten = rewriteUrisInDocument(doc);
            if (rewritten != null) {
                if (saveDocument(collection, doc, collectionName.endsWith(".history"))) {
                    migrated++;
                } else {
                    failed++;
                }
            }
        }

        if (migrated > 0) {
            LOGGER.infof("  %s: migrated %d descriptors", collectionName, migrated);
        }
        return new MigrationResult(migrated, failed);
    }

    /**
     * What one collection pass did.
     *
     * <p>
     * {@code failed} is what keeps the completion log unwritten. Every pass that
     * could not read its collection, or could not write a document it had
     * rewritten, has to land here — a pass that reports {@code (0, 0)} for a
     * collection nobody read is indistinguishable from one that had nothing to do,
     * and {@link #runIfNeeded()} would record the migration complete over it.
     * </p>
     */
    private record MigrationResult(int migrated, int failed) {

        /** Nothing to migrate, and nothing went wrong. */
        static final MigrationResult NOTHING = new MigrationResult(0, 0);

        /** A collection this pass could not read at all. */
        static final MigrationResult UNREADABLE = new MigrationResult(0, 1);

        MigrationResult plus(MigrationResult other) {
            return new MigrationResult(migrated + other.migrated, failed + other.failed);
        }
    }

    /** A collection that could not be read. Never leaves this class. */
    private static final class UnreadableCollectionException extends RuntimeException {
        UnreadableCollectionException(String message) {
            super(message);
        }
    }

    /**
     * The collection to scan, or {@code null} when there is nothing to scan.
     *
     * <p>
     * Every pass used to open with {@code catch (Exception e) { return 0; }}, so an
     * authorization error, a timeout or a step-down read exactly like "this
     * collection does not exist": the pass reported nothing migrated and nothing
     * failed, {@link #runIfNeeded()} saw a clean total, and the completion log was
     * written over a collection that had never been read. The documents were then
     * left in their v5 shape for good, because this migration only runs once.
     * </p>
     *
     * <p>
     * {@code NamespaceNotFound} (26) is the one failure that really does mean
     * "nothing to migrate here": only some of these names exist on any given
     * database, and while the current driver answers
     * {@code estimatedDocumentCount()} on a missing namespace with zero, others
     * raise that error instead. Treating it as a failure would leave the migration
     * permanently incomplete on a database with nothing to migrate. The same rule
     * {@code V6QuteMigration} already applies.
     * </p>
     *
     * @throws UnreadableCollectionException
     *             for every other failure, which the caller counts so the migration
     *             runs again on the next start
     */
    private MongoCollection<Document> collectionToScan(String collectionName) {
        try {
            MongoCollection<Document> collection = database.getCollection(collectionName);
            return collection.estimatedDocumentCount() == 0 ? null : collection;
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == NAMESPACE_NOT_FOUND_ERROR_CODE) {
                LOGGER.debugf("  %s: no such collection — nothing to migrate", collectionName);
                return null;
            }
            throw new UnreadableCollectionException(e.getErrorMessage());
        } catch (RuntimeException e) {
            throw new UnreadableCollectionException(e.toString());
        }
    }

    /** The result for a collection {@link #collectionToScan} could not read. */
    private static MigrationResult unreadable(String collectionName, UnreadableCollectionException e) {
        LOGGER.errorf("  %s could not be read (%s) — counted as a failure, so the migration is NOT recorded as "
                + "complete and runs again on the next start", collectionName, e.getMessage());
        return MigrationResult.UNREADABLE;
    }

    /**
     * Migrate environment fields in conversation memory and deployment documents.
     *
     * <p>
     * This is the expensive step of the migration, and its cost follows total
     * conversation bytes rather than the work it does: on a real staging upgrade it
     * was about 20 of the 24 minutes the startup migrations took, for 195 documents
     * averaging 410 KB, each read and rewritten one at a time in Java. A pre-check
     * that skips a clean collection cannot shorten that — proving there is no
     * legacy URI nested anywhere in a document means reading the document, which is
     * the whole cost. The fix, when it is made, is to move the rewrite server-side
     * ({@code updateMany} with {@code $rename} and {@code $set}), so the bytes
     * never cross the client.
     * </p>
     *
     * <p>
     * Documents are written one at a time, and one that cannot be written does not
     * stop the rest: it is logged, counted, and keeps the migration from being
     * recorded as complete, so it runs again on the next start. A deployment row
     * whose v6 key another row already holds is not such a failure — it is
     * resolved; see {@link #resolveDeploymentKeyCollision}.
     * </p>
     */
    private MigrationResult migrateEnvironments(String collectionName) {
        MongoCollection<Document> collection;
        try {
            collection = collectionToScan(collectionName);
        } catch (UnreadableCollectionException e) {
            return unreadable(collectionName, e);
        }
        if (collection == null) {
            return MigrationResult.NOTHING;
        }

        int migrated = 0;
        int failed = 0;
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

            if (!changed) {
                continue;
            }
            try {
                writeMigrated(collection, collectionName, doc);
                migrated++;
            } catch (Exception e) {
                failed++;
                LOGGER.errorf("  %s/%s could not be migrated — leaving it unchanged and continuing: %s", collectionName,
                        doc.get(ID_FIELD), e.toString());
            }
        }

        if (migrated > 0) {
            LOGGER.infof("  %s: migrated %d documents", collectionName, migrated);
        }
        return new MigrationResult(migrated, failed);
    }

    /**
     * Writes a migrated document back, resolving a deployment key collision rather
     * than failing on it.
     */
    private void writeMigrated(MongoCollection<Document> collection, String collectionName, Document doc) {
        try {
            collection.replaceOne(eq(ID_FIELD, doc.get(ID_FIELD)), doc);
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY || !COLLECTION_DEPLOYMENTS.equals(collectionName)) {
                throw e;
            }
            resolveDeploymentKeyCollision(collection, doc);
        }
    }

    /**
     * A v5 deployment row that becomes the same v6 key as a row already written.
     *
     * <p>
     * Two v5 shapes do this. {@link #ENVIRONMENT_REWRITES} maps both
     * {@code unrestricted} and {@code restricted} to {@code production}, so an
     * agent deployed to both becomes one key; and v5's own check-then-act
     * {@code setDeploymentInfo} wrote same-environment duplicates outright. The
     * unique index {@code MongoDeploymentStorage} builds when it is constructed
     * already exists by the time this migration runs, so the second write fails
     * with a duplicate-key error — and it used to fail on every boot, because the
     * first row had already been rewritten and the second never could be. The
     * migration then never completed, and with the deployment sweep waiting on it,
     * no agent was ever deployed again.
     * </p>
     *
     * <p>
     * It is resolved with the rule the store itself applies when it deduplicates:
     * one row per key, the newest by {@code _id} kept. The rows differ only in
     * status, and the newest is the operator's last word; any single row is a
     * consistent answer where two are not.
     * </p>
     *
     * <p>
     * "Newest" is decided by {@link #strictlyNewer}, not by
     * {@code ObjectId.compareTo}. An ObjectId is a 4-byte timestamp, a 5-byte
     * process-unique value and a 3-byte counter, compared in that order — so
     * {@code compareTo} orders by the <em>random process bytes</em> before the
     * counter, and for two ids created in the same second by different processes
     * the larger id can be the older row. Deleting on that basis can delete the
     * newer deployment status, which is data loss with nothing to recover it from.
     * Every case where insertion order cannot be established — a different process
     * inside the same second, or ids that are not ObjectIds at all — is left to
     * fail, and so to be counted, logged and keep the migration incomplete, rather
     * than guessed at. A migration that stops and says which two rows to reconcile
     * is recoverable; a deleted row is not.
     * </p>
     */
    private void resolveDeploymentKeyCollision(MongoCollection<Document> collection, Document doc) {
        Object id = doc.get(ID_FIELD);
        Bson sameKey = and(eq(FIELD_ENVIRONMENT, doc.get(FIELD_ENVIRONMENT)), eq(FIELD_AGENT_ID, doc.get(FIELD_AGENT_ID)),
                eq(FIELD_AGENT_VERSION, doc.get(FIELD_AGENT_VERSION)), ne(ID_FIELD, id));
        Document holder = collection.find(sameKey).first();
        if (holder == null) {
            // The row that held the key has gone since the write failed; nothing is
            // in the way any more.
            collection.replaceOne(eq(ID_FIELD, id), doc);
            return;
        }

        Object holderId = holder.get(ID_FIELD);
        if (!(id instanceof ObjectId mine) || !(holderId instanceof ObjectId theirs)) {
            throw new IllegalStateException(String.format("deployment rows %s and %s both become %s/%s/%s, and their ids are "
                    + "not ObjectIds, so which is newer cannot be told; keep one by hand", id, holderId,
                    doc.get(FIELD_ENVIRONMENT), doc.get(FIELD_AGENT_ID), doc.get(FIELD_AGENT_VERSION)));
        }

        Boolean isNewer = strictlyNewer(mine, theirs);
        if (isNewer == null) {
            throw new IllegalStateException(String.format("deployment rows %s and %s both become %s/%s/%s, and their ids were "
                    + "created in the same second by different processes, so which one the operator wrote last cannot be "
                    + "told; keep one by hand", id, holderId, doc.get(FIELD_ENVIRONMENT), doc.get(FIELD_AGENT_ID),
                    doc.get(FIELD_AGENT_VERSION)));
        }

        Object keptId;
        Object removedId;
        if (isNewer) {
            // This row is the newer one: it takes the key. Delete first — the unique
            // index will not let both exist, and if this stops in between, the row
            // being migrated is still there under its v5 names for the next start.
            collection.deleteOne(eq(ID_FIELD, holderId));
            collection.replaceOne(eq(ID_FIELD, id), doc);
            keptId = id;
            removedId = holderId;
        } else {
            collection.deleteOne(eq(ID_FIELD, id));
            keptId = holderId;
            removedId = id;
        }
        LOGGER.warnf("  deployments: rows %s and %s both become %s/%s/%s under v6 names — kept %s (the newer), removed %s",
                id, holderId, doc.get(FIELD_ENVIRONMENT), doc.get(FIELD_AGENT_ID), doc.get(FIELD_AGENT_VERSION), keptId, removedId);
    }

    /**
     * Whether {@code mine} was inserted after {@code theirs}, or {@code null} when
     * that cannot be established.
     *
     * <p>
     * An ObjectId is {@code [4 bytes timestamp][5 bytes process-unique][3 bytes
     * counter]}, and {@code compareTo} compares those twelve bytes in order. When
     * the timestamps differ that is insertion order at one-second resolution, which
     * is all this needs. Inside one second it is not: the process-unique bytes are
     * compared before the counter, so of two ids written in the same second by two
     * instances, the larger one is as likely to be the older row. The counter is
     * insertion order only within the process that issued it, which is why the
     * process bytes must match before it is consulted.
     * </p>
     *
     * <p>
     * Package-private and returning a boxed {@code Boolean} on purpose: "cannot
     * tell" is a third answer the caller has to act on by refusing, not a value it
     * can fold into a comparison.
     * </p>
     */
    static Boolean strictlyNewer(ObjectId mine, ObjectId theirs) {
        int byTime = Integer.compareUnsigned(mine.getTimestamp(), theirs.getTimestamp());
        if (byTime != 0) {
            return byTime > 0;
        }
        byte[] a = mine.toByteArray();
        byte[] b = theirs.toByteArray();
        for (int i = PROCESS_BYTES_FROM; i < PROCESS_BYTES_TO; i++) {
            if (a[i] != b[i]) {
                // Same second, different writers: concurrent by any definition
                // available here.
                return null;
            }
        }
        int mineCounter = counterOf(a);
        int theirsCounter = counterOf(b);
        return mineCounter == theirsCounter ? null : mineCounter > theirsCounter;
    }

    /** First byte of an ObjectId's 5-byte process-unique value. */
    private static final int PROCESS_BYTES_FROM = 4;
    /** One past the last byte of that value; the 3-byte counter follows. */
    private static final int PROCESS_BYTES_TO = 9;

    private static int counterOf(byte[] objectId) {
        return ((objectId[9] & 0xFF) << 16) | ((objectId[10] & 0xFF) << 8) | (objectId[11] & 0xFF);
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
     * Saves a rewritten document back to its collection.
     *
     * <p>
     * Returns whether the write actually happened. It used to return {@code void}
     * after swallowing every exception, and every caller incremented its migrated
     * count immediately afterwards — so a write that failed, and an {@code _id}
     * shape this method silently declines to handle, both counted as a document
     * migrated. The migration then recorded completion over documents still in
     * their v5 shape, and since it runs once, they stayed that way.
     * </p>
     *
     * @return {@code true} when the document was written, {@code false} when it was
     *         not — which the caller counts as a failure so the migration runs
     *         again on the next start
     */
    @SuppressWarnings("unchecked")
    private boolean saveDocument(MongoCollection<Document> collection, Document document, boolean isHistory) {
        Object idObj = document.get(ID_FIELD);
        try {
            if (isHistory) {
                if (idObj instanceof Map<?, ?>) {
                    var idMap = (Map<String, Object>) idObj;
                    var query = eq(ID_FIELD, new Document((Map<String, Object>) idMap));
                    collection.replaceOne(query, document);
                    return true;
                }
            } else if (idObj instanceof ObjectId) {
                collection.replaceOne(eq(ID_FIELD, idObj), document);
                return true;
            } else if (idObj instanceof String) {
                collection.replaceOne(eq(ID_FIELD, new ObjectId((String) idObj)), document);
                return true;
            }
            LOGGER.warnf("Not saving a migrated document: its _id is a %s, which this migration cannot address",
                    idObj == null ? "null" : idObj.getClass().getSimpleName());
            return false;
        } catch (Exception e) {
            LOGGER.warnf("Failed to save migrated document %s: %s", idObj, e.getMessage());
            return false;
        }
    }
}
