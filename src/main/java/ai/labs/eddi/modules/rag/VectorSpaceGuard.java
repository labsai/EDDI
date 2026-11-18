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
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.utils.LogSanitizer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
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
 * Knows which {@linkplain VectorSpace vector space} each knowledge base's
 * vectors are in, and refuses to search or write in another one.
 *
 * <h2>Two records, because two different things are true</h2>
 * <ul>
 * <li><b>Which model made the vectors</b> is a fact about a <em>knowledge
 * base</em>: retrieval and deletion are filtered by the chunk tag, so two
 * knowledge bases that share one table can hold different models without
 * touching each other. It is recorded per store <em>location</em> and <em>chunk
 * tag</em> ({@link KnowledgeBaseStorage#chunkKbId}) — the id in the per-id
 * layout, the name in the 6.5.0 layout, where two same-named knowledge bases
 * share the tag and so, by definition, one record.</li>
 * <li><b>The vector size</b> is a fact about the <em>table</em>: its column has
 * one. It is recorded per location, and for pgvector checked against the real
 * column ({@code atttypmod}), which nothing can contradict.</li>
 * </ul>
 * Both live in EDDI's own database, next to the ingestion state, keyed by a
 * digest. The alternative for pgvector, a metadata table in the vector
 * database, was rejected: no other store has an equivalent place and it needs
 * DDL rights.
 *
 * <h2>Knowledge bases from before this check</h2>
 * <p>
 * With no record, a knowledge base adopts the space it is configured for on its
 * first use, under its own key — so one knowledge base's adoption never tells
 * another to re-embed. That cannot be verified against the vectors already
 * there. It is not done when the configured space cannot be resolved
 * ({@code ${vars:…}} that did not resolve): the call is refused, retryable.
 */
@ApplicationScoped
public class VectorSpaceGuard {

    private static final Logger LOGGER = Logger.getLogger(VectorSpaceGuard.class);

    static final String MODEL_PREFIX = "vector-space:model:";
    static final String SIZE_PREFIX = "vector-space:size:";

    /** Counter of refusals, tagged by reason only (never by knowledge base). */
    public static final String REFUSED_METRIC = "eddi.rag.vector_space.refused";

    /** Reads of a record are cached this long; this node's own writes are not. */
    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /** A refusal is logged at most this often per knowledge base. */
    static final Duration LOG_INTERVAL = Duration.ofMinutes(1);

    /** pgvector's {@code dimension} parameter default (OpenAI small). */
    public static final int PGVECTOR_DEFAULT_DIMENSION = 1536;

    private final IIngestionStateStore stateStore;
    private final GlobalVariableResolver variables;

    /**
     * The real column size of a pgvector table. Null in tests that build the guard
     * by hand.
     */
    @Inject
    EmbeddingStoreFactory storeFactory;

    @Inject
    MeterRegistry meterRegistry;

    private final Cache<String, String> recorded = Caffeine.newBuilder().maximumSize(4_000).expireAfterWrite(CACHE_TTL).build();
    private final Cache<String, Boolean> recentlyLogged = Caffeine.newBuilder().maximumSize(2_000).expireAfterWrite(LOG_INTERVAL).build();

    /** Locations whose adoption was logged already — once per process is enough. */
    private final Set<String> adoptionLogged = ConcurrentHashMap.newKeySet();

    /** Knowledge bases being re-embedded by this instance. */
    private final Set<String> underMaintenance = ConcurrentHashMap.newKeySet();

    @Inject
    public VectorSpaceGuard(IIngestionStateStore stateStore, GlobalVariableResolver variables) {
        this.stateStore = stateStore;
        this.variables = variables;
    }

    /**
     * Gives the guard the factory that reads a pgvector table's real column size;
     * for tests built by hand.
     */
    public void useStoreFactory(EmbeddingStoreFactory factory) {
        this.storeFactory = factory;
    }

    /** What a check found. */
    public record Status(boolean applicable, VectorSpace stored, VectorSpace configured, boolean matches) {
    }

    /**
     * Whether a vector space is tracked for this configuration: an external store
     * with a location and a chunk tag. An in-memory store is replaced when its
     * embedding configuration changes (see {@code EmbeddingStoreFactory}).
     */
    public static boolean applies(String ragConfigId, RagConfiguration config) {
        return identityOf(ragConfigId, config) != null;
    }

    private record Identity(String location, String chunkKbId) {
        String modelKey() {
            return MODEL_PREFIX + digest(location + "\n" + chunkKbId);
        }

        String sizeKey() {
            return SIZE_PREFIX + digest(location);
        }
    }

    private static Identity identityOf(String ragConfigId, RagConfiguration config) {
        String location = KnowledgeBaseStorage.vectorSpaceLocation(ragConfigId, config);
        if (location == null) {
            return null;
        }
        try {
            return new Identity(location, KnowledgeBaseStorage.chunkKbId(ragConfigId, config));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The space {@code config} writes and searches in.
     *
     * @throws OperatorFailureException
     *             when a global variable it names cannot be resolved right now:
     *             nothing is judged, adopted or reset on text that is not the
     *             configuration's real value
     */
    public VectorSpace configured(RagConfiguration config) {
        Map<String, String> params = config.getEmbeddingParameters();
        if (params != null && !params.isEmpty()) {
            Map<String, String> resolved;
            try {
                resolved = variables == null ? params : variables.resolveAll(params);
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not resolve the embedding parameters of knowledge base '%s': %s",
                        LogSanitizer.sanitize(config.getName()), LogSanitizer.sanitize(String.valueOf(e.getMessage())));
                throw unresolved();
            }
            if (resolved != null) {
                params = resolved;
            }
            for (String key : new String[]{"model", "modelName", "deploymentName", "taskType", "dimensions", "outputDimensionality"}) {
                String value = params.get(key);
                if (value != null && value.contains("${")) {
                    throw unresolved();
                }
            }
        }
        return VectorSpace.of(config, params);
    }

    private static OperatorFailureException unresolved() {
        return new OperatorFailureException("This knowledge base's embedding settings name a global variable that could not be "
                + "resolved right now, so its embedding space cannot be checked. Nothing was changed; try again.");
    }

    /**
     * Compares the recorded space with the configured one without changing
     * anything. Used by the status endpoint.
     */
    public Status status(String ragConfigId, RagConfiguration config) {
        Identity identity = identityOf(ragConfigId, config);
        if (identity == null) {
            return new Status(false, null, null, true);
        }
        VectorSpace configured = configured(config);
        VectorSpace stored = stored(identity);
        return new Status(true, stored, configured, stored == null || !configured.conflictsWith(stored));
    }

    private VectorSpace stored(Identity identity) {
        VectorSpace model = VectorSpace.decode(read(identity.modelKey()));
        if (model == null) {
            return null;
        }
        return model.withDimension(readSize(identity));
    }

    private Integer readSize(Identity identity) {
        String raw = read(identity.sizeKey());
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Refuses when the recorded space differs from the configured one; records the
     * configured one when there is none. A failure to read or write the record is
     * logged and lets the call through: an unavailable state store must not take
     * retrieval down, and nothing was found to be wrong.
     *
     * @throws VectorSpaceMismatchException
     *             on a mismatch, or while this knowledge base is being re-embedded
     * @throws OperatorFailureException
     *             when the configured space cannot be resolved
     */
    public void requireCompatible(String ragConfigId, RagConfiguration config) {
        Identity identity = identityOf(ragConfigId, config);
        if (identity == null) {
            return;
        }
        if (underMaintenance.contains(ragConfigId)) {
            refused("maintenance");
            throw new VectorSpaceMismatchException("This knowledge base is being re-embedded right now. Its vectors are removed and "
                    + "rebuilt, so searching and ingesting wait until the runs it started have filled it again.");
        }
        VectorSpace configured = configured(config);
        VectorSpace stored;
        try {
            stored = stored(identity);
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Could not read the vector space of knowledge base %s; not checking it", LogSanitizer.sanitize(ragConfigId));
            return;
        }
        if (stored == null) {
            adopt(ragConfigId, config, identity, configured);
            return;
        }
        if (configured.conflictsWith(stored)) {
            refused(configured.sameModelAs(stored) ? "dimension" : "space");
            throw new VectorSpaceMismatchException(mismatchMessage(ragConfigId, stored, configured));
        }
    }

    /**
     * Checks a measured vector size against what holds the vectors — the table's
     * real column for pgvector, the size recorded for the location otherwise — and,
     * when {@code record} is set and no size was known, records it.
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
        Identity identity = identityOf(ragConfigId, config);
        if (identity == null || actual <= 0) {
            return;
        }
        if ("pgvector".equals(config.getStoreType())) {
            int declared = declaredPgVectorDimension(config);
            Integer real = realColumnDimension(ragConfigId, config);
            int expected = real != null ? real : declared;
            if (expected > 0 && expected != actual) {
                refused("dimension");
                throw new VectorSpaceMismatchException("The embedding model returns vectors of " + actual + " dimensions, but this "
                        + "knowledge base's pgvector table " + (real != null ? "was made for " + real : "is configured for " + declared)
                        + " (storeParameters.dimension" + (config.getStoreParameters() != null && config.getStoreParameters().containsKey("dimension")
                                ? ""
                                : ", default " + PGVECTOR_DEFAULT_DIMENSION)
                        + "). Set storeParameters.dimension to " + actual + " and re-embed the knowledge base (POST /ragstore/rags/"
                        + ragConfigId + "/reembed): the table is recreated for the new size when no other knowledge base keeps rows in it; "
                        + "otherwise give this knowledge base a table of its own.");
            }
        }
        Integer size;
        try {
            size = readSize(identity);
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Could not read the vector size of knowledge base %s; not checking it", LogSanitizer.sanitize(ragConfigId));
            return;
        }
        if (size != null && size != actual) {
            refused("dimension");
            throw new VectorSpaceMismatchException("The embedding model returns vectors of " + actual + " dimensions, but this "
                    + "knowledge base's vector store holds vectors of " + size + ". Re-embed the knowledge base (POST /ragstore/rags/"
                    + ragConfigId + "/reembed) to rebuild its vectors in the new size; a store shared with other knowledge bases keeps "
                    + "one size, so give this one a location of its own if the others stay.");
        }
        if (record && size == null) {
            write(identity.sizeKey(), Integer.toString(actual));
        }
    }

    private Integer realColumnDimension(String ragConfigId, RagConfiguration config) {
        if (storeFactory == null) {
            return null;
        }
        try {
            var info = storeFactory.inspectPgVectorTable(ragConfigId, config, KnowledgeBaseStorage.chunkKbId(ragConfigId, config));
            return info.exists() && info.layoutMatches() ? info.columnDimension() : null;
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not read the real vector size of the pgvector table of knowledge base %s: %s",
                    LogSanitizer.sanitize(ragConfigId), LogSanitizer.sanitize(String.valueOf(e.getMessage())));
            return null;
        }
    }

    /**
     * Records the configured space as the one this knowledge base's vectors are in,
     * with the size unknown — after they were removed to be made again.
     *
     * @param clearSize
     *            also forget the location's vector size: true when nothing else
     *            keeps vectors there
     */
    public void reset(String ragConfigId, RagConfiguration config, boolean clearSize) {
        Identity identity = identityOf(ragConfigId, config);
        if (identity == null) {
            return;
        }
        VectorSpace configured = configured(config);
        write(identity.modelKey(), configured.withDimension(null).encode());
        if (clearSize) {
            write(identity.sizeKey(), "");
        }
    }

    /** Marks a knowledge base as being re-embedded by this instance. */
    public void beginMaintenance(String ragConfigId) {
        underMaintenance.add(ragConfigId);
    }

    public void endMaintenance(String ragConfigId) {
        underMaintenance.remove(ragConfigId);
    }

    /**
     * Logs a refusal at WARN, once a minute per knowledge base, without a stack
     * trace: a refused knowledge base is refused on every turn, and the turn is not
     * the news.
     */
    public void logRefusal(String ragConfigId, String knowledgeBaseName, Exception refusal) {
        if (recentlyLogged.asMap().putIfAbsent(ragConfigId, Boolean.TRUE) == null) {
            LOGGER.warnf("Knowledge base '%s' (%s) was refused: %s", LogSanitizer.sanitize(knowledgeBaseName),
                    LogSanitizer.sanitize(ragConfigId), LogSanitizer.sanitize(RagFailures.clientMessage(refusal)));
        }
    }

    private void adopt(String ragConfigId, RagConfiguration config, Identity identity, VectorSpace configured) {
        try {
            write(identity.modelKey(), configured.withDimension(null).encode());
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Could not record the vector space of knowledge base %s", LogSanitizer.sanitize(ragConfigId));
            return;
        }
        if (adoptionLogged.add(identity.modelKey())) {
            LOGGER.infof("Knowledge base %s has no recorded vector space (it was created before EDDI tracked it). "
                    + "Adopting the configured one, %s; that cannot be verified against the vectors already in %s.",
                    LogSanitizer.sanitize(ragConfigId), configured.describe(), LogSanitizer.sanitize(config.getStoreType()));
        }
    }

    private String read(String key) {
        String cached = recorded.getIfPresent(key);
        if (cached == null) {
            cached = stateStore.storeFingerprint(key).orElse(null);
            if (cached != null) {
                recorded.put(key, cached);
            }
        }
        return cached;
    }

    private void write(String key, String value) {
        stateStore.recordStoreFingerprint(key, value);
        recorded.put(key, value);
    }

    private void refused(String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter(REFUSED_METRIC, "reason", reason).increment();
        }
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

    /** A digest, because a location can contain a connection string. */
    static String digest(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
