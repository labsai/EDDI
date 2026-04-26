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

        for (String colName : TEMPLATE_COLLECTIONS) {
            total += migrateCollection(colName);
            total += migrateCollection(colName + ".history");
        }

        LOGGER.infof("V6 Qute migration complete: %d documents migrated", total);
        migrationLogStore.createMigrationLog(new MigrationLog(MIGRATION_KEY));
    }

    /** Walk all documents in a collection, rewrite template strings. */
    private int migrateCollection(String colName) {
        MongoCollection<Document> col;
        try {
            col = database.getCollection(colName);
            if (col.estimatedDocumentCount() == 0) {
                return 0;
            }
        } catch (Exception e) {
            return 0;
        }

        int migrated = 0;
        for (Document doc : col.find()) {
            if (migrateDocument(doc)) {
                col.replaceOne(eq(ID_FIELD, doc.get(ID_FIELD)), doc);
                migrated++;
            }
        }
        if (migrated > 0) {
            LOGGER.infof("  %s: migrated %d documents", colName, migrated);
        }
        return migrated;
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
