/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.mongo;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * One-time startup migration: moves all legacy {@code properties} documents
 * into the unified {@code usermemories} collection as {@code global} entries.
 * <p>
 * Idempotent: if the {@code properties} collection doesn't exist or is empty,
 * this is a no-op. After successful migration, the old collection is renamed to
 * {@code properties_migrated_v6} as a safety backup.
 * <p>
 * Only active in MongoDB mode (not Postgres — Postgres was added in v6
 * alongside the usermemories table, so there is no legacy properties table to
 * migrate).
 * <p>
 * <b>Credentials are not migrated.</b> Long-term memories are loaded into every
 * future conversation as {@code properties}, so anything copied here reaches
 * template data, and from there prompts and outbound API calls. Two kinds of
 * key are therefore left behind:
 * <ul>
 * <li>the keys in {@code eddi.migration.properties.skip-keys} — by default
 * {@code userInfo}, which EDDI 5 clients sent with every request as the
 * caller's identity (a platform bearer token beside names and ids). It was
 * never a user's memory; on one production 5.x database it held a learner's
 * token in 159 of 277 documents;</li>
 * <li>any key whose value, at any depth, {@link SecretScrubber} would redact on
 * export — the same rules, so an API key stored as a property is caught
 * too;</li>
 * <li>any key whose value, at any depth, has the shape of a known credential
 * format — a JWT, a pasted {@code Bearer}/{@code Basic} header, or a provider
 * key prefix — which the scrubber's whole-value rules do not all see.</li>
 * </ul>
 * One scrubber rule is narrowed for this migration only: the entropy heuristic
 * is not applied to an identifier-shaped value under an identifier-named field
 * ({@code courseId}, {@code course_id}, {@code id}). A random-looking id scores
 * like a key, so the rule held back ordinary memories such as
 * <code>{courseId: "…"}</code> as credentials. The name rules and the
 * known-format rules still apply to those values. A skipped key is logged by
 * name and count, never by value, and is not a failure: its value stays in
 * {@code properties_migrated_v6}, readable by an operator, and nothing is
 * loaded from there.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class PropertiesMigrationService {

    private static final Logger LOGGER = Logger.getLogger(PropertiesMigrationService.class);
    private static final String LEGACY_COLLECTION = "properties";
    private static final String BACKUP_COLLECTION = "properties_migrated_v6";

    /** The default of {@code eddi.migration.properties.skip-keys}. */
    static final String DEFAULT_SKIP_KEYS = "userInfo";

    private final MongoDatabase database;
    private final IUserMemoryStore userMemoryStore;
    private final String datastoreType;
    private final SecretScrubber secretScrubber;
    private final Set<String> skipKeys;

    @Inject
    public PropertiesMigrationService(MongoDatabase database, IUserMemoryStore userMemoryStore,
            @ConfigProperty(name = "eddi.datastore.type", defaultValue = "mongodb") String datastoreType, SecretScrubber secretScrubber,
            @ConfigProperty(name = "eddi.migration.properties.skip-keys", defaultValue = DEFAULT_SKIP_KEYS) List<String> skipKeys) {
        this.database = database;
        this.userMemoryStore = userMemoryStore;
        this.datastoreType = datastoreType;
        this.secretScrubber = secretScrubber;
        this.skipKeys = new TreeSet<>(skipKeys == null ? List.of() : skipKeys.stream().map(String::trim).filter(k -> !k.isEmpty()).toList());
    }

    /** Stands in for an identifier during the credential check only. */
    static final String IDENTIFIER_PLACEHOLDER = "identifier";

    /**
     * The shape of an identifier: letters, digits, {@code _} and {@code -}, and
     * nothing else. No dot, so a JWT never qualifies; no space, so a pasted
     * {@code Bearer …} header never does.
     */
    private static final Pattern IDENTIFIER_SHAPE = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    /**
     * Words that make an {@code …Id} name a credential rather than an identifier. A
     * session id is a bearer credential, and {@code accessKeyId} or {@code tokenId}
     * name the credential's own half.
     */
    private static final Set<String> CREDENTIAL_QUALIFIERS = Set.of("session", "auth", "token", "secret", "password", "passwd", "api",
            "key", "access", "refresh", "credential", "credentials", "private");

    /**
     * Known credential formats, found anywhere inside a string: a JWT, a pasted
     * {@code Authorization} value, and the key prefixes of common providers
     * ({@code sk-} keys, Stripe, Slack, GitHub, GitLab, AWS, Google, Hugging Face).
     * Each has a minimum body length, so an ordinary word that happens to start the
     * same way ({@code "sk-learn"}) is not one. Every quantifier is a single
     * character class, so the scan stays linear.
     */
    private static final Pattern KNOWN_CREDENTIAL_FORMAT = Pattern.compile("(?<![A-Za-z0-9])(?:"
            + "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*" // JWT
            + "|(?i:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}" // an Authorization header value
            + "|sk-[A-Za-z0-9_-]{16,}" // sk- provider keys
            + "|(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,}" // Stripe
            + "|xox[abpsre]-[A-Za-z0-9-]{10,}" // Slack
            + "|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}" // GitHub
            + "|glpat-[A-Za-z0-9_-]{20,}" // GitLab
            + "|(?:AKIA|ASIA)[A-Z0-9]{16}" // AWS access key id
            + "|AIza[A-Za-z0-9_-]{30,}" // Google API key
            + "|hf_[A-Za-z0-9]{30,}" // Hugging Face
            + ")");

    /**
     * Whether a field of this name holds an identifier: {@code id}, or a name whose
     * last word is {@code id} or {@code ids} ({@code courseId}, {@code course_id},
     * {@code courseID}, {@code courseIds}) and none of whose other words mark a
     * credential ({@code sessionId}, {@code accessKeyId}).
     */
    static boolean isIdentifierFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        List<String> words = splitWords(fieldName);
        if (words.isEmpty()) {
            return false;
        }
        String last = words.getLast();
        if (!"id".equals(last) && !"ids".equals(last)) {
            return false;
        }
        return words.subList(0, words.size() - 1).stream().noneMatch(CREDENTIAL_QUALIFIERS::contains);
    }

    /**
     * Splits {@code courseId}, {@code course_id} and {@code courseID} alike into
     * lower-case words.
     */
    private static List<String> splitWords(String name) {
        return Arrays.stream(name.split("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+")).filter(w -> !w.isEmpty())
                .map(w -> w.toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * Whether any string in {@code value}, at any depth, is a known credential
     * format.
     */
    static boolean hasKnownCredentialFormat(Object value) {
        if (value instanceof String text) {
            return KNOWN_CREDENTIAL_FORMAT.matcher(text).find();
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(PropertiesMigrationService::hasKnownCredentialFormat);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(PropertiesMigrationService::hasKnownCredentialFormat);
        }
        return false;
    }

    /**
     * {@code value} with every identifier replaced by a fixed placeholder, for the
     * scrubber's credential check only. Two kinds count:
     * <ul>
     * <li>a BSON {@code ObjectId} — an identifier by type. Serialised as extended
     * JSON it becomes <code>{"$oid": "65a1…"}</code>, a random-looking hex string
     * the scrubber's entropy rule would take for a key;</li>
     * <li>an identifier-shaped string under an identifier-named field (see
     * {@link #isIdentifierFieldName}). A 17-character alphanumeric {@code courseId}
     * scores about 4 bits per character, over the scrubber's 3.5-bit entropy
     * threshold, so it was held back as a credential.</li>
     * </ul>
     * A string in a known credential format is never replaced, whatever its field
     * is called, and {@link #hasKnownCredentialFormat} holds it back regardless.
     * List items are judged by the list's own field name, as the scrubber judges
     * them.
     *
     * @param fieldName
     *            the name {@code value} sits under
     */
    static Object withoutIdentifiers(String fieldName, Object value) {
        if (value instanceof ObjectId) {
            return "objectid";
        }
        if (value instanceof String text && isIdentifierFieldName(fieldName) && IDENTIFIER_SHAPE.matcher(text).matches()
                && !KNOWN_CREDENTIAL_FORMAT.matcher(text).find()) {
            return IDENTIFIER_PLACEHOLDER;
        }
        if (value instanceof Map<?, ?> map) {
            Document copy = new Document();
            map.forEach((k, v) -> copy.put(String.valueOf(k), withoutIdentifiers(String.valueOf(k), v)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> withoutIdentifiers(fieldName, item)).toList();
        }
        return value;
    }

    /**
     * Whether {@code key}, or the name of any field nested in {@code value}, is a
     * credential name. The scrubber alone misses {@code apiKey: {value: "…"}}: it
     * judges {@code value} by its own name, not by the credential-named field that
     * encloses it.
     */
    static boolean hasCredentialName(String key, Object value) {
        if (SecretScrubber.isCredentialFieldName(key)) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (hasCredentialName(String.valueOf(entry.getKey()), entry.getValue())) {
                    return true;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                // Every item, lists inside lists included: a name check that stops at
                // an inner list leaves the scrubber judging the credential by an inner name.
                if (hasCredentialName("", item)) {
                    return true;
                }
            }
        }
        return false;
    }

    void onStartup(@Observes StartupEvent event) {
        if (!"mongodb".equals(datastoreType)) {
            LOGGER.debug("[MIGRATION] Skipping properties migration — not in MongoDB mode");
            return;
        }
        try {
            migrateIfNeeded();
        } catch (Exception e) {
            LOGGER.error("[MIGRATION] Failed to migrate legacy properties — will retry on next startup", e);
        }
    }

    private void migrateIfNeeded() {
        // Check if legacy collection exists and has documents
        boolean collectionExists = false;
        for (String name : database.listCollectionNames()) {
            if (LEGACY_COLLECTION.equals(name)) {
                collectionExists = true;
                break;
            }
        }

        if (!collectionExists) {
            LOGGER.debug("[MIGRATION] No legacy 'properties' collection found — skipping migration");
            return;
        }

        MongoCollection<Document> legacyCollection = database.getCollection(LEGACY_COLLECTION);
        long docCount = legacyCollection.countDocuments();
        if (docCount == 0) {
            LOGGER.debug("[MIGRATION] Legacy 'properties' collection is empty — skipping migration");
            return;
        }

        LOGGER.infof("[MIGRATION] Migrating %d legacy property documents to 'usermemories'...", docCount);

        int userCount = 0;
        int entryCount = 0;
        int failedCount = 0;
        int unownedDocuments = 0;
        int keptNewerEntries = 0;
        Map<String, Integer> skippedByConfig = new TreeMap<>();
        Map<String, Integer> skippedAsCredential = new TreeMap<>();

        for (Document doc : legacyCollection.find()) {
            String userId = doc.getString("userId");
            if (userId == null) {
                // Not a failure: a document with no owner can never be migrated, on this
                // boot or any other. Counting it as one held the rename back forever,
                // re-running the migration on every startup. Its contents stay readable
                // in the backup collection the source is renamed to.
                LOGGER.warnf("[MIGRATION] Skipping document without userId: %s — it remains in '%s' after the rename.",
                        doc.getObjectId("_id"), BACKUP_COLLECTION);
                unownedDocuments++;
                continue;
            }

            for (String key : doc.keySet()) {
                // Skip MongoDB internal fields and the userId field itself
                if ("_id".equals(key) || "userId".equals(key))
                    continue;
                if (IUserMemoryStore.isReservedKey(key)) {
                    // The store refuses these, and counting the refusal as a failure
                    // would keep the legacy collection from ever being retired. A legacy
                    // property can never have been a GDPR flag, which postdates it.
                    LOGGER.warnf("[MIGRATION] Skipping legacy key='%s' for userId='%s': reserved for GDPR bookkeeping", key, userId);
                    continue;
                }

                if (skipKeys.contains(key)) {
                    skippedByConfig.merge(key, 1, Integer::sum);
                    continue;
                }
                Object value = doc.get(key);
                if (hasCredentialName(key, value) || hasKnownCredentialFormat(value)
                        || secretScrubber.containsCredential(new Document(key, withoutIdentifiers(key, value)).toJson())) {
                    skippedAsCredential.merge(key, 1, Integer::sum);
                    continue;
                }
                UserMemoryEntry entry = new UserMemoryEntry(null, // id — generated on insert
                        userId, key, value, "legacy", // category — easy to identify migrated entries
                        Visibility.global, // matches old unscoped behavior
                        null, // no sourceAgentId (was shared across all agents)
                        List.of(), // no groupIds
                        null, // no sourceConversationId
                        false, // not conflicted
                        0, // accessCount
                        null, // createdAt — set by upsert
                        null // updatedAt — set by upsert
                );

                // A user who already has a GLOBAL entry for this key wrote it after the
                // v5 data was frozen — or a previous (partial) run of this migration did.
                // Either way the existing entry wins: upserting the legacy value over it
                // replaced a newer answer with a stale one, on every retry. Only the
                // global identity counts — a self or group entry with the same key is a
                // different memory and does not stand in for the shared one. The insert
                // is atomic, so a value another node writes meanwhile cannot be lost.
                try {
                    if (userMemoryStore.insertIfAbsent(entry) != null) {
                        entryCount++;
                    } else {
                        keptNewerEntries++;
                    }
                } catch (Exception e) {
                    failedCount++;
                    LOGGER.warnf("[MIGRATION] Failed to migrate key='%s' for userId='%s': %s", key, userId, e.getMessage());
                }
            }
            userCount++;
        }

        if (!skippedByConfig.isEmpty()) {
            LOGGER.infof("[MIGRATION] Not migrated (eddi.migration.properties.skip-keys) — key: documents %s. These values stay "
                    + "in '%s' and are never loaded into a conversation.", skippedByConfig, BACKUP_COLLECTION);
        }
        if (!skippedAsCredential.isEmpty()) {
            LOGGER.warnf("[MIGRATION] Not migrated because the value holds a credential by the export scrubber's rules — "
                    + "key: documents %s. These values stay in '%s' and are never loaded into a conversation; move any that "
                    + "are needed into the secrets vault by hand.", skippedAsCredential, BACKUP_COLLECTION);
        }

        // Only retire the source once every key made it across. The loop is idempotent
        // — a key already present in usermemories is skipped — so leaving the
        // collection in place lets
        // the next boot retry the entries that failed. Renaming on a partial run made
        // the migration a permanent no-op afterwards (collectionExists is then false),
        // so a transient Mongo error on three of four hundred users silently stranded
        // those users' long-term properties in the backup collection, recoverable only
        // by renaming it back by hand.
        if (failedCount > 0) {
            LOGGER.errorf("[MIGRATION] Migrated %d entries for %d users, but %d failed. Leaving '%s' in place; "
                    + "the migration will retry on the next startup. Fix the underlying error and restart.", entryCount, userCount,
                    failedCount, LEGACY_COLLECTION);
            return;
        }

        // Rename old collection as safety backup
        try {
            // Drop backup if it exists from a previous partial run
            for (String name : database.listCollectionNames()) {
                if (BACKUP_COLLECTION.equals(name)) {
                    database.getCollection(BACKUP_COLLECTION).drop();
                    break;
                }
            }
            legacyCollection.renameCollection(new MongoNamespace(database.getName(), BACKUP_COLLECTION));
            LOGGER.infof("[MIGRATION] Complete: migrated %d entries for %d users (%d keys kept their newer usermemories value, "
                    + "%d documents had no userId). Old collection renamed to '%s'", entryCount, userCount, keptNewerEntries, unownedDocuments,
                    BACKUP_COLLECTION);
        } catch (Exception e) {
            LOGGER.warnf("[MIGRATION] Migration data written but failed to rename collection: %s", e.getMessage());
        }
    }
}
