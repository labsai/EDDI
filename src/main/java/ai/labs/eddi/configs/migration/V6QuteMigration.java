/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.ID_FIELD;
import static com.mongodb.client.model.Filters.eq;

/**
 * Startup migration: rewrites Thymeleaf template syntax to Qute across all
 * MongoDB collections that store template strings.
 * <p>
 * Idempotent — records completion in migration_log. Controlled by
 * {@code eddi.migration.v6-qute.enabled} (default: false).
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class V6QuteMigration {

    private static final Logger LOGGER = Logger.getLogger(V6QuteMigration.class);
    private static final String MIGRATION_KEY = "v6-qute-migration-complete";

    /** Collections containing template strings. */
    private static final String[] TEMPLATE_COLLECTIONS = {"apicalls", "outputs", "propertysetter", "llms"};

    private final MongoDatabase database;
    private final IMigrationLogStore migrationLogStore;
    private final TemplateSyntaxMigrator migrator;
    private final boolean enabled;

    @Inject
    public V6QuteMigration(MongoDatabase database, IMigrationLogStore migrationLogStore, TemplateSyntaxMigrator migrator,
            @ConfigProperty(name = "eddi.migration.v6-qute.enabled", defaultValue = "false") boolean enabled) {
        this.database = database;
        this.migrationLogStore = migrationLogStore;
        this.migrator = migrator;
        this.enabled = enabled;
    }

    /** Run if enabled and not already applied. */
    public void runIfNeeded() {
        if (!enabled) {
            LOGGER.info("V6 Qute migration disabled (eddi.migration.v6-qute.enabled=false)");
            return;
        }
        if (migrationLogStore.readMigrationLog(MIGRATION_KEY) != null) {
            LOGGER.info("V6 Qute migration already applied — skipping");
            return;
        }

        LOGGER.info("Starting V6 Qute template migration...");
        int total = 0;
        int failed = 0;

        for (String colName : TEMPLATE_COLLECTIONS) {
            for (String name : List.of(colName, colName + ".history")) {
                CollectionResult result = migrateCollection(name);
                total += result.migrated();
                failed += result.failed();
            }
        }

        if (failed > 0) {
            // Deliberately not marked complete: the documents above are still on
            // Thymeleaf syntax and would render as literal text. Running again is
            // safe — a migrated document no longer contains Thymeleaf syntax, so it
            // is not rewritten twice — and these are config collections, so the
            // repeated scan is cheap next to shipping a half-migrated database.
            LOGGER.errorf("V6 Qute migration migrated %d document(s); %d could not be migrated (logged above). Not "
                    + "marking the migration complete, so it runs again on the next startup — fix or remove those "
                    + "documents first.", total, failed);
            return;
        }

        LOGGER.infof("V6 Qute migration complete: %d documents migrated", total);
        migrationLogStore.createMigrationLog(new MigrationLog(MIGRATION_KEY));
    }

    /** What one collection's pass did. */
    private record CollectionResult(int migrated, int failed) {
    }

    /**
     * Walk all documents in a collection, rewrite template strings.
     *
     * <p>
     * Each document is migrated on its own: one that cannot be migrated is logged
     * and left as it was, and the rest of the collection still goes through. This
     * loop used to have no guard, so a single malformed template anywhere in the
     * database threw out of here, out of {@code runIfNeeded}, and left every other
     * config unmigrated behind a log line that only said it would retry.
     * </p>
     */
    private CollectionResult migrateCollection(String colName) {
        MongoCollection<Document> col;
        try {
            col = database.getCollection(colName);
            if (col.estimatedDocumentCount() == 0) {
                return new CollectionResult(0, 0);
            }
        } catch (Exception e) {
            // Deliberately not counted as a failure: this is the "collection is not
            // there" path — only some of these names exist on any given database, and
            // some driver versions answer estimatedDocumentCount on a missing namespace
            // with an exception rather than with zero. Counting it would leave the
            // migration permanently incomplete on a database with nothing to migrate.
            LOGGER.debugf("V6 Qute migration skipped '%s': %s", colName, e.getMessage());
            return new CollectionResult(0, 0);
        }

        int migrated = 0;
        int failed = 0;
        for (Document doc : col.find()) {
            try {
                if (migrateDocument(doc)) {
                    col.replaceOne(eq(ID_FIELD, doc.get(ID_FIELD)), doc);
                    migrated++;
                }
            } catch (Exception e) {
                // The document is never written when this happens — migrateDocument
                // mutates its in-memory copy, and the replaceOne it would have been
                // written by is what threw or was never reached.
                failed++;
                LOGGER.errorf("V6 Qute migration could not migrate %s/%s — leaving it unchanged and continuing: %s",
                        colName, doc.get(ID_FIELD), e.toString());
            }
        }
        if (migrated > 0) {
            LOGGER.infof("  %s: migrated %d documents", colName, migrated);
        }
        return new CollectionResult(migrated, failed);
    }

    /** Recursively walk a Document, rewrite all string values. */
    @SuppressWarnings("unchecked")
    private boolean migrateDocument(Document doc) {
        boolean changed = false;
        for (String key : new ArrayList<>(doc.keySet())) {
            Object val = doc.get(key);
            if (val instanceof String strVal) {
                if (migrator.containsThymeleafSyntax(strVal)) {
                    doc.put(key, migrator.migrate(strVal));
                    changed = true;
                }
            } else if (val instanceof Document nested) {
                changed = migrateDocument(nested) || changed;
            } else if (val instanceof List<?> list) {
                changed = migrateList((List<Object>) list) || changed;
            }
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private boolean migrateList(List<Object> list) {
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof String strVal) {
                if (migrator.containsThymeleafSyntax(strVal)) {
                    list.set(i, migrator.migrate(strVal));
                    changed = true;
                }
            } else if (item instanceof Document nested) {
                changed = migrateDocument(nested) || changed;
            } else if (item instanceof List<?> nested) {
                changed = migrateList((List<Object>) nested) || changed;
            }
        }
        return changed;
    }
}
