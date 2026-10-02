/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where a knowledge base's vectors live, and how its chunks are told apart from
 * everyone else's.
 *
 * <h2>Why this is decided in one place</h2>
 * <p>
 * Up to 6.5.0 the vector store was addressed by the knowledge base's
 * <em>name</em>, and by whatever {@code ?kbId=} an ingestion request carried.
 * Names are not unique — {@code duplicateRag} copies them, and nothing stops
 * two teams from choosing the same one — so two knowledge bases could share one
 * store, and any editor of one knowledge base could ingest into, or with
 * {@code replace=true} delete from, another's store by naming it. Ingestion,
 * retrieval, the source pipeline and the cache key each derived the address on
 * their own, which is how they could disagree.
 *
 * <h2>The two layouts</h2>
 * <ul>
 * <li>{@link #NAMESPACE_ID} — every knowledge base created from this release
 * on. The store is addressed by the RAG configuration's <strong>id</strong>,
 * which is stable across versions and unique. The default physical location is
 * {@code eddi_kbid_<id>}, every chunk is tagged {@code kbId=<id>}, and
 * retrieval filters on that tag — so even two knowledge bases that deliberately
 * share one physical table see only their own chunks.</li>
 * <li>{@link #NAMESPACE_NAME} (or no value at all) — the 6.5.0 layout, kept so
 * that a knowledge base created before the upgrade keeps reading the table it
 * has always read. Its default physical location is derived from the name
 * exactly as 6.5.0 derived it, chunks are tagged {@code kbId=<name>}, and
 * retrieval is not filtered, because chunks written by 6.5.0 carry no id tag.
 * Switching such a knowledge base to {@code "id"} is the migration; renaming it
 * does the same automatically.</li>
 * </ul>
 */
public final class KnowledgeBaseStorage {

    /** Store addressed by the RAG configuration's id — the default for new KBs. */
    public static final String NAMESPACE_ID = "id";

    /** Store addressed by the knowledge base's name — the 6.5.0 layout. */
    public static final String NAMESPACE_NAME = "name";

    /** Chunk metadata: the knowledge base a chunk belongs to. */
    public static final String METADATA_KB_ID = "kbId";

    /**
     * Every default physical name EDDI derives starts with this. An explicitly
     * configured name may not, or it could address another knowledge base's default
     * store.
     */
    public static final String RESERVED_PREFIX = "eddi_kb";

    static final String LEGACY_PREFIX = "eddi_kb_";
    static final String ID_PREFIX = "eddi_kbid_";

    private static final int MAX_PG_IDENTIFIER_LENGTH = 63;
    private static final Pattern UNSAFE_IDENTIFIER_CHARS = Pattern.compile("[^a-z0-9_]");

    private KnowledgeBaseStorage() {
    }

    /** Whether the configuration uses the per-id layout. */
    public static boolean usesIdNamespace(RagConfiguration config) {
        return config != null && NAMESPACE_ID.equals(config.getStoreNamespace());
    }

    /**
     * The storeParameters key that names the physical location for this store type,
     * or {@code null} for a store without one ({@code in-memory}).
     */
    public static String physicalNameParameter(String storeType) {
        if (storeType == null) {
            return null;
        }
        return switch (storeType) {
            case "pgvector" -> "table";
            case "mongodb-atlas", "qdrant", "chroma" -> "collectionName";
            case "elasticsearch" -> "indexName";
            default -> null;
        };
    }

    /** The explicitly configured physical name, or {@code null}. */
    public static String explicitPhysicalName(RagConfiguration config) {
        if (config == null) {
            return null;
        }
        String key = physicalNameParameter(config.getStoreType());
        Map<String, String> params = config.getStoreParameters();
        if (key == null || params == null) {
            return null;
        }
        String value = params.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The physical location the knowledge base's vectors are stored in: the
     * explicit name when one is configured, otherwise the default for its layout.
     * {@code null} for {@code in-memory}, whose store is the cached object itself.
     *
     * @param ragConfigId
     *            the RAG configuration's id (version-independent)
     */
    public static String physicalName(String ragConfigId, RagConfiguration config) {
        String explicit = explicitPhysicalName(config);
        if (explicit != null) {
            return explicit;
        }
        return defaultPhysicalName(ragConfigId, config);
    }

    /**
     * The default physical name, before any explicit storeParameters override. The
     * legacy derivations are reproduced byte for byte — they differ by store type
     * because 6.5.0 sanitised each one differently, and an upgraded knowledge base
     * has to land on the table it was written to.
     */
    public static String defaultPhysicalName(String ragConfigId, RagConfiguration config) {
        String storeType = config.getStoreType();
        if (physicalNameParameter(storeType) == null) {
            return null;
        }
        if (usesIdNamespace(config)) {
            requireId(ragConfigId);
            return truncate(ID_PREFIX + sanitize(ragConfigId), storeType);
        }
        String name = requireName(config);
        return switch (storeType) {
            case "pgvector" -> legacyTableName(name);
            case "mongodb-atlas" -> LEGACY_PREFIX + name;
            case "elasticsearch" -> LEGACY_PREFIX + legacySanitize(name);
            default -> legacyCollectionName(name);
        };
    }

    /**
     * The value every chunk of this knowledge base carries under
     * {@link #METADATA_KB_ID}: the id in the per-id layout, the name in the legacy
     * one (which is what 6.5.0 wrote, so {@code replace=true} keeps superseding
     * documents ingested before the upgrade).
     */
    public static String chunkKbId(String ragConfigId, RagConfiguration config) {
        if (usesIdNamespace(config)) {
            return requireId(ragConfigId);
        }
        return requireName(config);
    }

    /**
     * Whether retrieval restricts results to chunks tagged with this knowledge
     * base's id. Only in the per-id layout: chunks a 6.5.0 instance wrote carry no
     * id tag, and filtering on it would hide every one of them.
     */
    public static boolean filtersRetrieval(RagConfiguration config) {
        return usesIdNamespace(config);
    }

    /**
     * Identifies where the vectors of this knowledge base live, for telling whether
     * an update moved them (and the source state has to be forgotten so the next
     * run repopulates the new location).
     */
    public static String locationKey(String ragConfigId, RagConfiguration config) {
        if (config == null) {
            return null;
        }
        String physical = config.getStoreType() != null && physicalNameParameter(config.getStoreType()) != null
                ? physicalNameOrNull(ragConfigId, config)
                : null;
        return config.getStoreType() + "|" + (usesIdNamespace(config) ? NAMESPACE_ID : NAMESPACE_NAME + ":" + config.getName())
                + "|" + physical;
    }

    private static String physicalNameOrNull(String ragConfigId, RagConfiguration config) {
        try {
            return physicalName(ragConfigId, config);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Collision key for an explicitly named location: the store type plus the name,
     * compared case-insensitively. Deliberately ignores host and database —
     * comparing raw connection strings would miss {@code localhost} versus
     * {@code 127.0.0.1}, and a false positive only asks for EDIT on both knowledge
     * bases.
     */
    public static String collisionKey(RagConfiguration config, String physicalName) {
        return config.getStoreType() + "|" + physicalName.toLowerCase(Locale.ROOT);
    }

    /** Whether an explicitly configured name falls inside EDDI's own namespace. */
    public static boolean isReservedName(String physicalName) {
        return physicalName != null && physicalName.trim().toLowerCase(Locale.ROOT).startsWith(RESERVED_PREFIX);
    }

    /** The pgvector table name 6.5.0 derived from a knowledge base name. */
    public static String legacyTableName(String kbId) {
        String result = LEGACY_PREFIX + legacySanitize(kbId);
        return result.length() > MAX_PG_IDENTIFIER_LENGTH ? result.substring(0, MAX_PG_IDENTIFIER_LENGTH) : result;
    }

    /**
     * The Qdrant/Chroma collection name 6.5.0 derived from a knowledge base name.
     */
    public static String legacyCollectionName(String kbId) {
        String name = LEGACY_PREFIX + legacySanitize(kbId);
        // A scan from the end, not the "_+$" regex it replaces: an unanchored-start
        // "_+$" retries at every underscore, quadratic on a name that is a long run of
        // them (CodeQL java/polynomial-redos), and the name is operator input.
        int end = name.length();
        while (end > 0 && name.charAt(end - 1) == '_') {
            end--;
        }
        return name.substring(0, end);
    }

    private static String sanitize(String value) {
        return UNSAFE_IDENTIFIER_CHARS.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("_");
    }

    /**
     * 6.5.0 lowercased with the JVM's default locale. Kept, so that an instance
     * running under, say, a Turkish locale keeps finding the tables it created.
     */
    private static String legacySanitize(String value) {
        return UNSAFE_IDENTIFIER_CHARS.matcher(value.toLowerCase()).replaceAll("_");
    }

    private static String truncate(String name, String storeType) {
        return "pgvector".equals(storeType) && name.length() > MAX_PG_IDENTIFIER_LENGTH
                ? name.substring(0, MAX_PG_IDENTIFIER_LENGTH)
                : name;
    }

    private static String requireId(String ragConfigId) {
        if (ragConfigId == null || ragConfigId.isBlank()) {
            throw new IllegalArgumentException("The knowledge base has no id, and its id is what its vector store is keyed by");
        }
        return ragConfigId;
    }

    private static String requireName(RagConfiguration config) {
        String name = config.getName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("The knowledge base has no name, and with storeNamespace '" + NAMESPACE_NAME
                    + "' its name is what the vector store is keyed by");
        }
        return name;
    }
}
