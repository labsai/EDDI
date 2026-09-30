/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.ID_FIELD;
import static ai.labs.eddi.datastore.mongo.MongoResourceStorage.VERSION_FIELD;
import static com.mongodb.client.model.Filters.eq;

/**
 * Startup migration: rewrites Thymeleaf template syntax to Qute across all
 * MongoDB collections that store template strings.
 * <p>
 * Idempotent — records completion in migration_log. Controlled by
 * {@code eddi.migration.v6-qute.enabled} (default: false).
 *
 * <h2>Templates it cannot convert</h2>
 * <p>
 * A template the converter cannot convert safely — one that builds template
 * syntax, say — is left exactly as it is, and so is every other field of its
 * document. The first start that finds such a document logs it at ERROR, with
 * its collection, id, field paths and what to do about it, and records it under
 * {@link #REPORTED_KEY} in the migration log. Every later start checks it again
 * — a document fixed in the meantime is converted and leaves the record — but
 * lists the ones still refused in a single WARN instead of an ERROR each: they
 * are known, and an error that repeats on every start for the same legacy
 * config stops being read. A refused document that is not in the record yet
 * gets its own ERROR, whenever it turns up.
 * </p>
 *
 * <p>
 * <b>The migration is not marked complete while any document is refused</b>,
 * even when every one of them has been reported already. Completion means no
 * stored template is still Thymeleaf, and that is not true yet. It is also what
 * keeps the whole scan running: once complete, nothing is checked again, so a
 * refused document fixed later would never be converted, and a refused one
 * written later — restored from a backup, say — would never be reported.
 * Checking only the recorded documents instead would need a second path through
 * the same collections for the same result; the full scan covers four config
 * collections, which is cheap, and it is what an incomplete migration has
 * always cost on each start.
 * </p>
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class V6QuteMigration {

    private static final Logger LOGGER = Logger.getLogger(V6QuteMigration.class);
    private static final String MIGRATION_KEY = "v6-qute-migration-complete";

    /**
     * The migration-log record listing the documents already reported as
     * unconvertible, as {@code collection/id}. Removed once none is left.
     */
    static final String REPORTED_KEY = "v6-qute-migration-unconvertible";

    /**
     * MongoDB {@code NamespaceNotFound} — the only count failure that means
     * "nothing to migrate here" rather than "this collection was never read".
     */
    private static final int NAMESPACE_NOT_FOUND_ERROR_CODE = 26;

    /** Collections containing template strings. */
    private static final String[] TEMPLATE_COLLECTIONS = {"apicalls", "outputs", "propertysetter", "llms"};

    private static final String HISTORY_SUFFIX = ".history";

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
        Set<String> reported = readReported();
        Scan scan = new Scan(reported);

        for (String colName : TEMPLATE_COLLECTIONS) {
            for (String name : List.of(colName, colName + HISTORY_SUFFIX)) {
                migrateCollection(name, scan);
            }
        }

        if (!scan.stillRefused.isEmpty()) {
            LOGGER.warnf("V6 Qute migration: %d document(s) reported on an earlier start still hold templates that cannot "
                    + "be converted automatically, and were left unchanged again: %s. Fix or remove them to let the "
                    + "migration complete; it checks them again on every start.", scan.stillRefused.size(),
                    String.join(", ", scan.stillRefused));
        }
        recordReported(reported, scan);

        if (scan.failed > 0) {
            // Deliberately not marked complete: whatever failed is still on Thymeleaf
            // syntax and would render as literal text. Running again is safe — a
            // migrated document no longer contains Thymeleaf syntax, so it is not
            // rewritten twice — and these are config collections, so the repeated scan
            // is cheap next to shipping a half-migrated database.
            LOGGER.errorf("V6 Qute migration migrated %d document(s) with %d failure(s) (logged above, per document or "
                    + "collection). Not marking the migration complete, so it runs again on the next startup — deal "
                    + "with those first.", scan.migrated, scan.failed);
            return;
        }
        if (!scan.refused.isEmpty()) {
            // Not an error of this start: each refused document has had its ERROR, on
            // this start or an earlier one. See the class Javadoc for why this is still
            // not complete.
            LOGGER.infof("V6 Qute migration migrated %d document(s); %d left unchanged because their templates cannot be "
                    + "converted automatically (see above). Not marking the migration complete, so it checks them again "
                    + "on the next startup.", scan.migrated, scan.refused.size());
            return;
        }

        LOGGER.infof("V6 Qute migration complete: %d documents migrated", scan.migrated);
        migrationLogStore.createMigrationLog(new MigrationLog(MIGRATION_KEY));
    }

    /**
     * What one start's scan found. {@code failed} counts every failure other than a
     * refused template: an unreadable collection, a document that could not be
     * written.
     */
    private static final class Scan {
        /** The documents reported on an earlier start, or {@code null} if unknown. */
        private final Set<String> reported;
        /** Every document refused on this start, as {@code collection/id}. */
        private final Set<String> refused = new TreeSet<>();
        /** The refused documents that were already reported. */
        private final List<String> stillRefused = new ArrayList<>();
        /** Collections this start could not read — their record is kept as it was. */
        private final Set<String> unreadCollections = new HashSet<>();
        private int migrated;
        private int failed;

        private Scan(Set<String> reported) {
            this.reported = reported;
        }
    }

    /**
     * The documents reported as unconvertible on earlier starts. A record that
     * cannot be read answers {@code null}, and every refused document is then
     * reported as new: an ERROR too many is the safe way to be wrong.
     */
    private Set<String> readReported() {
        try {
            return new HashSet<>(migrationLogStore.readMigrationEntries(REPORTED_KEY));
        } catch (Exception e) {
            LOGGER.warnf("V6 Qute migration could not read which unconvertible templates it has reported before (%s) — "
                    + "reporting each one found as new", e.toString());
            return null;
        }
    }

    /**
     * Stores the documents refused on this start as the reported ones: a document
     * fixed since is dropped, a new one added. The entries of a collection this
     * start could not read are kept, since whether they are still refused is not
     * known. Written only when it changed; a failed write is logged, and costs no
     * more than those documents being reported as new on the next start.
     */
    private void recordReported(Set<String> reported, Scan scan) {
        Set<String> entries = new TreeSet<>(scan.refused);
        if (reported != null) {
            for (String entry : reported) {
                if (scan.unreadCollections.contains(collectionOf(entry))) {
                    entries.add(entry);
                }
            }
            if (entries.equals(reported)) {
                return;
            }
        }
        try {
            migrationLogStore.writeMigrationEntries(REPORTED_KEY, List.copyOf(entries));
        } catch (Exception e) {
            LOGGER.warnf("V6 Qute migration could not record which unconvertible templates it has reported (%s) — they are "
                    + "reported as new on the next start", e.toString());
        }
    }

    private static String collectionOf(String entry) {
        int slash = entry.indexOf('/');
        return slash < 0 ? entry : entry.substring(0, slash);
    }

    /**
     * How a document is named in the log and in the record: {@code collection/id},
     * with the version for a history row, whose {@code _id} holds both.
     */
    private static String documentKey(String colName, Object id) {
        if (id instanceof Document compound && compound.containsKey(ID_FIELD)) {
            return colName + "/" + compound.get(ID_FIELD) + " v" + compound.get(VERSION_FIELD);
        }
        return colName + "/" + id;
    }

    private void countFailure(String colName, Exception e, Scan scan) {
        LOGGER.errorf("V6 Qute migration could not read '%s' — it may still hold Thymeleaf templates, so the migration "
                + "is not marked complete: %s", colName, e.toString());
        scan.failed++;
        scan.unreadCollections.add(colName);
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
     *
     * <p>
     * A collection that cannot be counted is a failure, with exactly one exception:
     * {@code NamespaceNotFound}. Only some of these names exist on any given
     * database, and while the current driver answers
     * {@code estimatedDocumentCount()} on a missing namespace with zero, others
     * have raised that error instead — so treating it as a failure would leave the
     * migration permanently incomplete on a database that has nothing to migrate.
     * Everything else (an authorization error, a timeout, a server error) means the
     * collection may well hold Thymeleaf templates that nobody has looked at, and
     * swallowing it would mark the migration complete over a collection that was
     * never read.
     * </p>
     */
    private void migrateCollection(String colName, Scan scan) {
        MongoCollection<Document> col;
        try {
            col = database.getCollection(colName);
            if (col.estimatedDocumentCount() == 0) {
                return;
            }
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == NAMESPACE_NOT_FOUND_ERROR_CODE) {
                LOGGER.debugf("V6 Qute migration skipped '%s': the collection does not exist", colName);
                return;
            }
            countFailure(colName, e, scan);
            return;
        } catch (Exception e) {
            countFailure(colName, e, scan);
            return;
        }

        int migrated = 0;
        try {
            for (Document doc : col.find()) {
                try {
                    List<String> unconvertible = new ArrayList<>();
                    boolean changed = migrateDocument(doc, "", unconvertible);
                    if (!unconvertible.isEmpty()) {
                        // Nothing of this document is written, not even the fields that did
                        // convert: a half-migrated document mixes two template languages,
                        // and the one that stays behind still renders as literal text.
                        refuse(colName, doc.get(ID_FIELD), unconvertible, scan);
                        continue;
                    }
                    if (changed) {
                        col.replaceOne(eq(ID_FIELD, doc.get(ID_FIELD)), doc);
                        migrated++;
                    }
                } catch (Exception e) {
                    // The document is never written when this happens — migrateDocument
                    // mutates its in-memory copy, and the replaceOne it would have been
                    // written by is what threw or was never reached.
                    scan.failed++;
                    LOGGER.errorf("V6 Qute migration could not migrate %s/%s — leaving it unchanged and continuing: %s",
                            colName, doc.get(ID_FIELD), e.toString());
                }
            }
        } catch (Exception e) {
            // The cursor itself failed part-way: whatever it had not reached yet was
            // never looked at.
            countFailure(colName, e, scan);
        }
        if (migrated > 0) {
            LOGGER.infof("  %s: migrated %d documents", colName, migrated);
        }
        scan.migrated += migrated;
    }

    /**
     * A document left unchanged because a template of it cannot be converted
     * safely: an ERROR the first time, and a line of the start's single WARN on
     * every later one.
     */
    private void refuse(String colName, Object id, List<String> unconvertible, Scan scan) {
        String key = documentKey(colName, id);
        scan.refused.add(key);
        if (scan.reported != null && scan.reported.contains(key)) {
            scan.stillRefused.add(key);
            return;
        }
        String remedy = colName.endsWith(HISTORY_SUFFIX)
                ? "This is a stored earlier version of a config, which the API does not edit: once no deployed agent "
                        + "uses that version, correct or remove the row in the database by hand"
                : "Rewrite the template(s) as Qute by hand, or delete or retire the config. Either way the version "
                        + "refused here is kept in '" + colName + HISTORY_SUFFIX + "', which is checked too: once no "
                        + "deployed agent uses it, remove that row by hand";
        LOGGER.errorf("V6 Qute migration left %s unchanged: %d field(s) cannot be converted automatically — %s. %s. Until "
                + "then the migration is not marked complete and checks it again on every start; later starts list it in "
                + "one warning instead of repeating this error.", key, unconvertible.size(), String.join("; ", unconvertible),
                remedy);
    }

    /**
     * Recursively walk a Document, rewrite all string values.
     *
     * @param unconvertible
     *            collects a "field path: reason" entry for every string that cannot
     *            be converted, or that still holds Thymeleaf syntax after
     *            conversion; the caller then writes nothing
     */
    @SuppressWarnings("unchecked")
    private boolean migrateDocument(Document doc, String path, List<String> unconvertible) {
        boolean changed = false;
        for (String key : new ArrayList<>(doc.keySet())) {
            Object val = doc.get(key);
            String fieldPath = path.isEmpty() ? key : path + "." + key;
            if (val instanceof String strVal) {
                String migrated = migrateString(strVal, fieldPath, unconvertible);
                if (migrated != null) {
                    doc.put(key, migrated);
                    changed = true;
                }
            } else if (val instanceof Document nested) {
                changed = migrateDocument(nested, fieldPath, unconvertible) || changed;
            } else if (val instanceof List<?> list) {
                changed = migrateList((List<Object>) list, fieldPath, unconvertible) || changed;
            }
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private boolean migrateList(List<Object> list, String path, List<String> unconvertible) {
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            String itemPath = path + "[" + i + "]";
            if (item instanceof String strVal) {
                String migrated = migrateString(strVal, itemPath, unconvertible);
                if (migrated != null) {
                    list.set(i, migrated);
                    changed = true;
                }
            } else if (item instanceof Document nested) {
                changed = migrateDocument(nested, itemPath, unconvertible) || changed;
            } else if (item instanceof List<?> nested) {
                changed = migrateList((List<Object>) nested, itemPath, unconvertible) || changed;
            }
        }
        return changed;
    }

    /**
     * The converted string, or {@code null} when it is left as it is — because it
     * holds no Thymeleaf syntax, or because it cannot be converted safely, in which
     * case {@code unconvertible} records why.
     *
     * <p>
     * Two checks, because neither covers the other. The migrator refuses the shapes
     * it knows it would mangle. And a result that still holds Thymeleaf syntax was
     * not converted, whatever the reason; counting it as migrated records the
     * migration complete over a template that renders as literal text.
     * </p>
     */
    private String migrateString(String value, String fieldPath, List<String> unconvertible) {
        if (!migrator.containsThymeleafSyntax(value)) {
            return null;
        }
        String reason = migrator.unconvertibleReason(value);
        if (reason != null) {
            unconvertible.add(fieldPath + ": " + reason);
            return null;
        }
        String migrated = migrator.migrate(value);
        if (migrator.containsThymeleafDelimiters(migrated)) {
            unconvertible.add(fieldPath + ": a Thymeleaf expression is left after conversion");
            return null;
        }
        return migrated;
    }
}
