package ai.labs.eddi.configs.migration;

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
import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.VERSION_FIELD;
import static com.mongodb.client.model.Filters.eq;

/**
 * V6 Rename Migration — rewrites legacy eddi:// URIs, store paths, environment values,
 * and descriptor type fields across all MongoDB collections.
 * <p>
 * This migration is idempotent: it records completion in the migration_log collection
 * and will not re-run if already applied.
 * <p>
 * Controlled by the config property {@code eddi.migration.v6-rename.enabled} (default: false).
 * Set to true when migrating a v5 database to v6.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class V6RenameMigration {

    private static final Logger LOGGER = Logger.getLogger(V6RenameMigration.class);
    private static final String MIGRATION_KEY = "v6-rename-migration-complete";

    /** URI authority rewrites (old → new). Longest-first to avoid partial matches. */
    private static final String[][] URI_AUTHORITY_REWRITES = {
            {"eddi://ai.labs.regulardictionary/", "eddi://ai.labs.dictionary/"},
            {"eddi://ai.labs.httpcalls/", "eddi://ai.labs.apicalls/"},
            {"eddi://ai.labs.behavior/", "eddi://ai.labs.rules/"},
            {"eddi://ai.labs.langchain/", "eddi://ai.labs.llm/"},
            {"eddi://ai.labs.package/", "eddi://ai.labs.workflow/"},
            {"eddi://ai.labs.bot/", "eddi://ai.labs.agent/"},
    };

    /** Store path rewrites (old → new) — applied inside URI strings. */
    private static final String[][] STORE_PATH_REWRITES = {
            {"regulardictionarystore/regulardictionaries", "dictionarystore/dictionaries"},
            {"httpcallsstore/httpcalls", "apicallstore/apicalls"},
            {"behaviorstore/behaviorsets", "rulestore/rulesets"},
            {"langchainstore/langchains", "llmstore/llmconfigs"},
            {"packagestore/packages", "workflowstore/workflows"},
            {"botstore/bots", "agentstore/agents"},
    };

    /** Descriptor type field rewrites (old → new). */
    private static final String[][] DESCRIPTOR_TYPE_REWRITES = {
            {"ai.labs.regulardictionary", "ai.labs.dictionary"},
            {"ai.labs.httpcalls", "ai.labs.apicalls"},
            {"ai.labs.behavior", "ai.labs.rules"},
            {"ai.labs.langchain", "ai.labs.llm"},
            {"ai.labs.package", "ai.labs.package"}, // descriptor type stays as-is (legacy query compat)
            {"ai.labs.bot", "ai.labs.bot"},           // descriptor type stays as-is
    };

    /** Environment value rewrites. */
    private static final String[][] ENVIRONMENT_REWRITES = {
            {"unrestricted", "production"},
            {"restricted", "production"},
    };

    /** All MongoDB collections to scan for URI rewrites. */
    private static final String[] RESOURCE_COLLECTIONS = {
            "agents", "workflows", "rulesets", "apicalls", "outputs",
            "llmconfigs", "propertysetter", "dictionaries", "parsers",
            // Legacy collection names (in case they haven't been renamed yet)
            "bots", "packages", "behaviorsets", "httpcalls", "langchains",
            "regulardictionaries",
    };

    private final MongoDatabase database;
    private final MigrationLogStore migrationLogStore;
    private final boolean enabled;

    @Inject
    public V6RenameMigration(MongoDatabase database,
                             MigrationLogStore migrationLogStore,
                             @ConfigProperty(name = "eddi.migration.v6-rename.enabled",
                                     defaultValue = "false") boolean enabled) {
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

        int totalMigrated = 0;

        // 1. Rewrite URIs in all resource + history collections
        for (String collectionName : RESOURCE_COLLECTIONS) {
            totalMigrated += migrateCollection(collectionName);
            totalMigrated += migrateCollection(collectionName + ".history");
        }

        // 2. Rewrite descriptor type fields and resource URIs
        totalMigrated += migrateDescriptors("documentdescriptors");
        totalMigrated += migrateDescriptors("documentdescriptors.history");

        // 3. Rewrite environment fields in deployment/conversation documents
        totalMigrated += migrateEnvironments("conversationmemories");

        LOGGER.infof("V6 rename migration complete: %d documents migrated", totalMigrated);

        migrationLogStore.createMigrationLog(
                new ai.labs.eddi.configs.migration.model.MigrationLog(MIGRATION_KEY));
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
     * Migrate descriptor documents: rewrite the 'type' field and 'resource' URI.
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
            boolean changed = false;

            // Rewrite the 'type' field (e.g., "ai.labs.behavior" → "ai.labs.rules")
            // Note: we intentionally keep ai.labs.package and ai.labs.bot as-is for now
            // since the orphan scan depends on these descriptor types
            Object typeObj = doc.get("type");
            if (typeObj instanceof String typeStr) {
                for (String[] mapping : DESCRIPTOR_TYPE_REWRITES) {
                    if (typeStr.equals(mapping[0]) && !mapping[0].equals(mapping[1])) {
                        doc.put("type", mapping[1]);
                        changed = true;
                        break;
                    }
                }
            }

            // Rewrite URIs in the rest of the document
            Document uriRewritten = rewriteUrisInDocument(doc);
            changed = changed || uriRewritten != null;

            if (changed) {
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
     */
    private String rewriteUriString(String value) {
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
