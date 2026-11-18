/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.model.VectorSpace;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.utils.LogSanitizer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knows which {@linkplain VectorSpace vector space} each external vector store
 * holds, and refuses to search or write in another one.
 *
 * <h2>Where the record is kept</h2>
 * <p>
 * In EDDI's own database, next to the ingestion state, keyed by a digest of the
 * store's <em>location</em> — store type, server parameters and table or
 * collection (see {@link KnowledgeBaseStorage#vectorSpaceLocation}) — not by
 * the knowledge base. That is the same for pgvector, MongoDB Atlas,
 * Elasticsearch, Qdrant and Chroma, so there is one mechanism to reason about.
 * Keyed by location, two knowledge bases that name the same table share one
 * record, which is right: they share vectors. The alternative for pgvector, a
 * metadata table in the vector database, was rejected: only that one store type
 * could have it (the others have no place a client may put it), it needs DDL
 * rights EDDI's vector user may not have, and a record kept in two ways is two
 * things to keep in step. The cost of this choice: a vector store wiped or
 * restored behind EDDI's back keeps its old record until the knowledge base is
 * re-embedded, which resets it.
 *
 * <h2>Knowledge bases from before this check</h2>
 * <p>
 * With no record, the configured space is adopted on first use and logged once.
 * It cannot be verified — nothing says what the existing vectors were made with
 * — so a knowledge base whose embedding settings were changed before the
 * upgrade stays as wrong as it was; it is not made worse, and it keeps working.
 *
 * <h2>The vector size</h2>
 * <p>
 * Not known from configuration, so it is measured: the first embedding of an
 * ingestion records it, and every later embedding — a query included — is
 * compared with it ({@link #verifyDimension}).
 */
@ApplicationScoped
public class VectorSpaceGuard {

    private static final Logger LOGGER = Logger.getLogger(VectorSpaceGuard.class);

    static final String KEY_PREFIX = "vector-space:";

    /** Reads of the record are cached this long; this node's own writes are not. */
    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /** pgvector's {@code dimension} parameter default (OpenAI small). */
    static final int PGVECTOR_DEFAULT_DIMENSION = 1536;

    private final IIngestionStateStore stateStore;
    private final GlobalVariableResolver variables;

    private final Cache<String, String> recorded = Caffeine.newBuilder().maximumSize(2_000).expireAfterWrite(CACHE_TTL).build();

    /** Locations whose adoption was logged already — once per process is enough. */
    private final Set<String> adoptionLogged = ConcurrentHashMap.newKeySet();

    @Inject
    public VectorSpaceGuard(IIngestionStateStore stateStore, GlobalVariableResolver variables) {
        this.stateStore = stateStore;
        this.variables = variables;
    }

    /** What a check found. */
    public record Status(boolean applicable, VectorSpace stored, VectorSpace configured, boolean matches) {
    }

    /**
     * Whether a vector space is tracked for this configuration: an external store
     * with a location. An in-memory store is replaced when its embedding
     * configuration changes (see {@code EmbeddingStoreFactory}).
     */
    public static boolean applies(String ragConfigId, RagConfiguration config) {
        return KnowledgeBaseStorage.vectorSpaceLocation(ragConfigId, config) != null;
    }

    /** The space {@code config} writes and searches in. */
    public VectorSpace configured(RagConfiguration config) {
        Map<String, String> params = config.getEmbeddingParameters();
        if (params != null && !params.isEmpty() && variables != null) {
            try {
                Map<String, String> resolved = variables.resolveAll(params);
                if (resolved != null) {
                    params = resolved;
                }
            } catch (RuntimeException e) {
                LOGGER.debugf("Comparing the configured embedding parameters as written: %s",
                        LogSanitizer.sanitize(String.valueOf(e.getMessage())));
            }
        }
        return VectorSpace.of(config, params);
    }

    /**
     * Compares the recorded space with the configured one without changing
     * anything. Used by the status endpoint and by the checks below.
     */
    public Status status(String ragConfigId, RagConfiguration config) {
        String location = KnowledgeBaseStorage.vectorSpaceLocation(ragConfigId, config);
        if (location == null) {
            return new Status(false, null, null, true);
        }
        VectorSpace configured = configured(config);
        VectorSpace stored = read(location);
        return new Status(true, stored, configured, stored == null || !configured.conflictsWith(stored));
    }

    /**
     * Refuses when the recorded space differs from the configured one; records the
     * configured one when there is none. A failure to read or write the record is
     * logged and lets the call through: an unavailable state store must not take
     * retrieval down, and nothing was found to be wrong.
     *
     * @throws VectorSpaceMismatchException
     *             on a mismatch
     */
    public void requireCompatible(String ragConfigId, RagConfiguration config) {
        String location = KnowledgeBaseStorage.vectorSpaceLocation(ragConfigId, config);
        if (location == null) {
            return;
        }
        VectorSpace configured = configured(config);
        VectorSpace stored;
        try {
            stored = read(location);
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Could not read the vector space of knowledge base %s; not checking it",
                    LogSanitizer.sanitize(ragConfigId));
            return;
        }
        if (stored == null) {
            adopt(ragConfigId, config, location, configured);
            return;
        }
        if (configured.conflictsWith(stored)) {
            throw new VectorSpaceMismatchException(mismatchMessage(ragConfigId, stored, configured));
        }
    }

    /**
     * Checks a measured vector size against the recorded one — and against the
     * table's, for pgvector — and, when {@code record} is set and the size was not
     * known, records it.
     *
     * @param actual
     *            the size of an embedding the provider just returned
     * @param record
     *            whether to write the size down; true for ingestion, which is what
     *            decides it, false for a query
     * @throws VectorSpaceMismatchException
     *             when the size is not the one this knowledge base's vectors have
     */
    public void verifyDimension(String ragConfigId, RagConfiguration config, int actual, boolean record) {
        String location = KnowledgeBaseStorage.vectorSpaceLocation(ragConfigId, config);
        if (location == null || actual <= 0) {
            return;
        }
        if ("pgvector".equals(config.getStoreType())) {
            int declared = declaredPgVectorDimension(config);
            if (declared > 0 && declared != actual) {
                throw new VectorSpaceMismatchException("The embedding model returns vectors of " + actual
                        + " dimensions, but this knowledge base's pgvector table is made for " + declared
                        + " (storeParameters.dimension"
                        + (config.getStoreParameters() != null && config.getStoreParameters().containsKey("dimension")
                                ? ""
                                : ", default " + PGVECTOR_DEFAULT_DIMENSION)
                        + "). Set storeParameters.dimension to " + actual + " and re-embed the knowledge base "
                        + "(POST /ragstore/rags/" + ragConfigId + "/reembed): its table is recreated for the new size.");
            }
        }
        VectorSpace stored;
        try {
            stored = read(location);
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Could not read the vector space of knowledge base %s; not checking its dimension",
                    LogSanitizer.sanitize(ragConfigId));
            return;
        }
        VectorSpace configured = configured(config);
        if (stored == null) {
            stored = configured;
        }
        if (stored.dimension() != null && stored.dimension() != actual) {
            throw new VectorSpaceMismatchException("The embedding model returns vectors of " + actual
                    + " dimensions, but this knowledge base's vector store holds vectors of " + stored.dimension() + " ("
                    + stored.describe() + "). Re-embed the knowledge base (POST /ragstore/rags/" + ragConfigId
                    + "/reembed) to rebuild its vectors in the new size.");
        }
        if (record && stored.dimension() == null) {
            write(location, stored.withDimension(actual));
        }
    }

    /**
     * Records the configured space as the one the store holds, with the size
     * unknown — after the vectors were removed to be made again.
     */
    public void reset(String ragConfigId, RagConfiguration config) {
        String location = KnowledgeBaseStorage.vectorSpaceLocation(ragConfigId, config);
        if (location == null) {
            return;
        }
        write(location, configured(config));
    }

    private void adopt(String ragConfigId, RagConfiguration config, String location, VectorSpace configured) {
        try {
            write(location, configured);
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Could not record the vector space of knowledge base %s", LogSanitizer.sanitize(ragConfigId));
            return;
        }
        if (adoptionLogged.add(location)) {
            LOGGER.infof("Knowledge base %s has no recorded vector space (it was created before EDDI tracked it). "
                    + "Adopting the configured one, %s; that cannot be verified against the vectors already in %s.",
                    LogSanitizer.sanitize(ragConfigId), configured.describe(), LogSanitizer.sanitize(config.getStoreType()));
        }
    }

    private VectorSpace read(String location) {
        String key = locationKey(location);
        String cached = recorded.getIfPresent(key);
        if (cached == null) {
            cached = stateStore.storeFingerprint(KEY_PREFIX + key).orElse(null);
            if (cached != null) {
                recorded.put(key, cached);
            }
        }
        return VectorSpace.decode(cached);
    }

    private void write(String location, VectorSpace space) {
        String key = locationKey(location);
        stateStore.recordStoreFingerprint(KEY_PREFIX + key, space.encode());
        recorded.put(key, space.encode());
    }

    static String mismatchMessage(String ragConfigId, VectorSpace stored, VectorSpace configured) {
        return "This knowledge base's vector store holds vectors made with " + stored.describe()
                + ", but it is now configured for " + configured.describe() + ". Vectors of different embedding "
                + "models cannot be searched together, so searching and ingesting are refused. Either change the "
                + "embedding settings back, or re-embed the knowledge base (POST /ragstore/rags/" + ragConfigId
                + "/reembed), which removes its vectors and ingests its sources again.";
    }

    private static int declaredPgVectorDimension(RagConfiguration config) {
        Map<String, String> params = config.getStoreParameters();
        String raw = params == null ? null : params.get("dimension");
        if (raw == null || raw.isBlank()) {
            return PGVECTOR_DEFAULT_DIMENSION;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            // A reference that this check cannot resolve; the store's own parsing
            // reports it.
            return -1;
        }
    }

    /** A digest, because the location can contain a connection string. */
    static String locationKey(String location) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(location.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
