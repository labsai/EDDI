/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionParameterGuard;
import ai.labs.eddi.datastore.mongo.MongoDriverInfoFactory;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.secrets.SecretResolver;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.mongodb.MongoDbEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoDriverInformation;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Creates and caches {@link EmbeddingStore} instances based on
 * {@link RagConfiguration}.
 * <p>
 * Each knowledge base gets its own store instance (collection-per-KB
 * isolation). Supported store types: {@code in-memory}, {@code pgvector},
 * {@code mongodb-atlas}, {@code elasticsearch}, {@code qdrant}, {@code chroma}.
 * <p>
 * <h2>Clients and data are kept apart</h2>
 * <p>
 * An external store (pgvector, MongoDB Atlas, Elasticsearch, Qdrant, Chroma) is
 * only a <em>client</em>: its data lives in the database. Those clients sit in
 * a bounded cache (max {@value #MAX_STORES} entries, 30-minute idle TTL) that
 * is also flushed whenever a vault secret or a global variable changes, because
 * their parameters carry credentials. Evicting one costs a reconnect, nothing
 * more.
 * <p>
 * An {@code in-memory} store <em>is</em> its data, so it is never in that
 * cache. It lives in a registry keyed by knowledge base id (the knowledge
 * base's name, which is what every caller passes) with no TTL, no size eviction
 * and no reaction to secret or variable changes — it holds no credential. Its
 * {@code storeParameters} are not part of the key, because an in-memory store
 * has none that mean anything. An entry is dropped only when:
 * <ul>
 * <li>the knowledge base is deleted and no readable version is left
 * ({@link #dropInMemoryStore});</li>
 * <li>an update renames the knowledge base, moves it off {@code in-memory}, or
 * changes its {@linkplain #embeddingSignature embedding configuration} — the
 * old store is unreachable or its vectors are meaningless to the new
 * model;</li>
 * <li>a write arrives under a different embedding configuration than the one
 * the store was built with ({@link #getOrCreateForIngestion}) — the same rule,
 * for writes that did not come through the REST update (an import, a store
 * written directly). Retrieval never replaces a store.</li>
 * </ul>
 * A new version of a knowledge base with the same name and the same embedding
 * configuration keeps its data: versions are not part of the key, because the
 * vectors depend on nothing else a version can change (chunking settings only
 * shape what the next run writes).
 * <p>
 * The process still loses every in-memory store on restart. Each store carries
 * an epoch that ingestion records with its per-source state
 * ({@link #ingestionFingerprint}), so the next run recognises a store that is
 * not the one its content hashes describe and re-embeds everything instead of
 * reporting it "unchanged".
 * <p>
 * The total number of segments across all in-memory stores is capped by
 * {@value #MAX_IN_MEMORY_SEGMENTS_PROPERTY}. Reaching it fails the write with
 * an {@link InMemoryStoreFullException}; nothing is evicted to make room.
 */
@ApplicationScoped
public class EmbeddingStoreFactory {

    private static final Logger LOGGER = Logger.getLogger(EmbeddingStoreFactory.class);
    private static final MongoDriverInformation DRIVER_INFO = MongoDriverInfoFactory.build();

    private static final int MAX_PG_IDENTIFIER_LENGTH = 63;
    private static final Pattern UNSAFE_IDENTIFIER_CHARS = Pattern.compile("[^a-z0-9_]");

    /** Ceiling on cached external-store clients across every knowledge base. */
    static final int MAX_STORES = 50;

    /** The store type whose store is its data. */
    static final String IN_MEMORY = "in-memory";

    /**
     * Deployment-wide cap on segments held by all in-memory stores together. Zero
     * or negative disables it.
     */
    static final String MAX_IN_MEMORY_SEGMENTS_PROPERTY = "eddi.rag.in-memory.max-segments";

    /**
     * Fifty thousand segments of a 1536-dimension model is roughly 300 MB of
     * vectors before their text — room for development and demos, and small enough
     * that in-memory knowledge bases cannot quietly take a production heap down.
     */
    static final int DEFAULT_MAX_IN_MEMORY_SEGMENTS = 50_000;

    /**
     * What ingestion records about a store starts with its epoch. The part is named
     * rather than the epoch standing alone so that further components — an
     * embedding-model fingerprint is planned — can be appended to the same string,
     * and any change to any of them reads as a different store.
     */
    static final String FINGERPRINT_EPOCH_PREFIX = "store-epoch=";

    /**
     * Returned by {@link #previewIngestionFingerprint} when a write would start a
     * fresh store. Never recorded, so it never equals a stored fingerprint.
     */
    static final String FINGERPRINT_NO_STORE = FINGERPRINT_EPOCH_PREFIX + "<none>";

    /**
     * Ceiling on distinct MongoDB deployments a single EDDI instance keeps clients
     * for.
     * <p>
     * Deliberately LARGER than {@link #MAX_STORES}. A size eviction here closes the
     * client, and a live {@code MongoDbEmbeddingStore} built from it keeps using it
     * — every distinct connection string reachable from the store cache therefore
     * needs a client slot, or the crude fallback breaks the very stores it is
     * bounding. With one client per store at worst, the bound cannot bite until
     * more distinct deployments exist than the store cache can hold stores.
     * <p>
     * This was 20 against a 50-entry store cache, i.e. a bound that fires while 30
     * cached stores still hold clients.
     */
    static final int MAX_MONGO_CLIENTS = MAX_STORES + 14;

    /** External-store clients only — see the class comment. */
    private final Cache<String, EmbeddingStore<TextSegment>> cache;

    /**
     * In-memory stores, keyed by knowledge base id. Never evicted — the class
     * comment lists the only ways an entry leaves.
     */
    private final Map<String, InMemoryKnowledgeBase> inMemoryStores = new ConcurrentHashMap<>();

    /** Held while checking the cap and writing, across every in-memory store. */
    private final Object inMemoryWriteLock = new Object();

    private final int maxInMemorySegments;

    /**
     * An in-memory store and the embedding configuration its vectors are made with.
     */
    private record InMemoryKnowledgeBase(CappedInMemoryEmbeddingStore store, String embeddingSignature) {
    }

    /**
     * MongoClients shared by every Atlas-backed store pointing at the same
     * deployment.
     * <p>
     * Bounded, keyed on a SHA-256 digest of the connection string rather than the
     * string itself, and closing the client (and therefore its connection pool)
     * when an entry leaves. The previous plain {@code ConcurrentHashMap} did none
     * of the three: it grew one never-closed client per distinct RAG config for the
     * lifetime of the process, and every one of those map keys was a live
     * credential-bearing URI sitting in the heap.
     * <p>
     * Deliberately size-bounded only — no idle TTL. The key is consulted on the
     * store <em>build</em> path, so an actively used store would not refresh it,
     * and an expiring entry would close a client that a live
     * {@code MongoDbEmbeddingStore} still holds.
     * <p>
     * Size eviction has exactly the same hazard — it also closes a client a live
     * store may still hold; the bound is not "safe" where a TTL is not. What makes
     * it tolerable is that it is unreachable in normal operation:
     * {@link #MAX_MONGO_CLIENTS} exceeds {@link #MAX_STORES}, so it cannot fire
     * while every client is reachable from a cached store. Reaching it at all is
     * logged, because the symptom otherwise is an inexplicable "state should be:
     * open" from a store that used to work.
     */
    private final Cache<String, MongoClient> mongoClientCache = Caffeine.newBuilder()
            .maximumSize(MAX_MONGO_CLIENTS)
            .removalListener((String key, MongoClient client, RemovalCause cause) -> {
                if (cause == RemovalCause.SIZE) {
                    LOGGER.warnf("Closing a pooled MongoClient because more than %d distinct MongoDB deployments are in use by "
                            + "vector stores. Any embedding store still holding this client will start failing; reduce the number of "
                            + "distinct connection strings across knowledge bases.", MAX_MONGO_CLIENTS);
                }
                closeQuietly(client);
            })
            .build();

    private final GlobalVariableResolver globalVariableResolver;
    private final SecretResolver secretResolver;

    @Inject
    public EmbeddingStoreFactory(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver,
            @ConfigProperty(name = MAX_IN_MEMORY_SEGMENTS_PROPERTY, defaultValue = "50000") int maxInMemorySegments) {
        this(globalVariableResolver, secretResolver, maxInMemorySegments, Ticker.systemTicker());
    }

    /** With the default cap — for tests. */
    EmbeddingStoreFactory(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver) {
        this(globalVariableResolver, secretResolver, DEFAULT_MAX_IN_MEMORY_SEGMENTS, Ticker.systemTicker());
    }

    /** With a controllable clock for the client cache's idle TTL — for tests. */
    EmbeddingStoreFactory(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver, int maxInMemorySegments,
            Ticker ticker) {
        this.globalVariableResolver = globalVariableResolver;
        this.secretResolver = secretResolver;
        this.maxInMemorySegments = maxInMemorySegments;
        this.cache = Caffeine.newBuilder().maximumSize(MAX_STORES)
                .expireAfterAccess(Duration.ofMinutes(30))
                .ticker(ticker)
                .build();
    }

    /**
     * Evict cached stores when a vault secret or a global variable changes.
     * <p>
     * Store parameters carry credentials — {@code password}, {@code apiKey},
     * {@code connectionString} — and the cache had no invalidation hook at all:
     * {@code expireAfterAccess} resets on every read, so a store serving traffic
     * never reached its TTL, and {@link #clearCache()} had no production caller.
     * Rotating a vector store's credential therefore kept the pre-rotation one
     * alive for the lifetime of the process.
     */
    @PostConstruct
    void registerInvalidation() {
        secretResolver.registerInvalidationListener(reference -> invalidateStores());
        globalVariableResolver.registerInvalidationListener(this::invalidateStores);
        LOGGER.info("EmbeddingStoreFactory registered for secret and global variable invalidation events");
    }

    /**
     * Drop cached store clients so the next lookup rebuilds them from current
     * credentials.
     * <p>
     * In-memory stores are deliberately untouched: they hold no credential, and
     * dropping one deletes every document ingested into it. They used to share this
     * cache, so rotating any secret in the deployment silently emptied every
     * in-memory knowledge base.
     * <p>
     * Deliberately does <em>not</em> close pooled MongoClients: an invalidation
     * fires for any secret in the deployment, including ones no vector store uses,
     * and closing a client would break a RAG query that is in flight on a store
     * another thread already holds. A rotated connection string simply hashes to a
     * new key and gets a new client; the superseded one is closed when the bounded
     * client cache evicts it.
     */
    private void invalidateStores() {
        cache.invalidateAll();
    }

    /**
     * Returns the embedding store to <em>read</em> from — retrieval, and removals —
     * for the given configuration and knowledge base id.
     * <p>
     * For an in-memory knowledge base this never replaces an existing store, even
     * when the embedding configuration differs from {@code config}: only a write
     * decides that stored vectors are obsolete (see
     * {@link #getOrCreateForIngestion}).
     */
    public EmbeddingStore<TextSegment> getOrCreate(RagConfiguration config, String kbId) {
        if (isInMemory(config)) {
            return inMemoryStores.computeIfAbsent(kbId, id -> newInMemoryStore(config, id)).store();
        }
        // Include storeParameters in cache key (different connection params = different
        // store)
        String paramsKey = config.getStoreParameters() != null ? new TreeMap<>(config.getStoreParameters()).toString() : "";
        String cacheKey = config.getStoreType() + ":" + kbId + ":" + paramsKey;
        return cache.get(cacheKey, k -> build(config, kbId));
    }

    /**
     * Returns the embedding store to <em>write</em> to. The same as
     * {@link #getOrCreate} for an external store. For an in-memory knowledge base
     * it replaces a store built under a different embedding configuration: adding
     * another model's vectors to it would make every search over it meaningless.
     */
    public EmbeddingStore<TextSegment> getOrCreateForIngestion(RagConfiguration config, String kbId) {
        if (isInMemory(config)) {
            return inMemoryStoreForWrite(config, kbId).store();
        }
        return getOrCreate(config, kbId);
    }

    /**
     * What ingestion records about the store a write to this knowledge base lands
     * in now, so that a later run can tell whether that store is still the one its
     * state describes.
     * <p>
     * For an in-memory knowledge base this is the store's epoch. Like
     * {@link #getOrCreateForIngestion} it creates the store, or replaces one built
     * under another embedding configuration, so the epoch returned is the one the
     * run's writes go to. For an external store it is {@code null}: its data
     * outlives the process, and its client has no identity worth comparing.
     *
     * @return the fingerprint, or {@code null} when there is nothing to compare
     */
    public String ingestionFingerprint(RagConfiguration config, String kbId) {
        if (!isInMemory(config)) {
            return null;
        }
        return FINGERPRINT_EPOCH_PREFIX + inMemoryStoreForWrite(config, kbId).store().epoch();
    }

    /**
     * {@link #ingestionFingerprint} without its side effects, for a preview: it
     * creates and replaces nothing. When a write would start a fresh store it
     * returns {@link #FINGERPRINT_NO_STORE}, which no recorded fingerprint equals.
     */
    public String previewIngestionFingerprint(RagConfiguration config, String kbId) {
        if (!isInMemory(config)) {
            return null;
        }
        InMemoryKnowledgeBase existing = inMemoryStores.get(kbId);
        if (existing == null || !existing.embeddingSignature().equals(embeddingSignature(config))) {
            return FINGERPRINT_NO_STORE;
        }
        return FINGERPRINT_EPOCH_PREFIX + existing.store().epoch();
    }

    /**
     * Forgets an in-memory knowledge base's store, and with it every document in it
     * — for a knowledge base that was deleted, or that its old name or store type
     * no longer reaches. Does nothing for a configuration that is not in-memory.
     *
     * @return whether a store was dropped
     */
    public boolean dropInMemoryStore(RagConfiguration config) {
        if (config == null || !isInMemory(config) || config.getName() == null) {
            return false;
        }
        InMemoryKnowledgeBase dropped = inMemoryStores.remove(config.getName());
        if (dropped != null) {
            LOGGER.infof("Dropped the in-memory vector store of knowledge base '%s' (%d segments)", sanitize(config.getName()),
                    dropped.store().size());
        }
        return dropped != null;
    }

    /**
     * Drops the in-memory store of {@code previous} when an update leaves it
     * behind: the knowledge base was renamed (the store is keyed by name), moved to
     * another store type, or changed its embedding configuration (the vectors were
     * made by another model). An update that changes none of these — a new version
     * with other chunking, sources or retrieval settings — keeps the data.
     *
     * @return whether a store was dropped
     */
    public boolean dropSupersededInMemoryStore(RagConfiguration previous, RagConfiguration updated) {
        if (previous == null || updated == null || !isInMemory(previous)) {
            return false;
        }
        boolean sameStore = Objects.equals(previous.getName(), updated.getName())
                && embeddingSignature(previous).equals(embeddingSignature(updated));
        return !sameStore && dropInMemoryStore(previous);
    }

    /** Segments held by every in-memory store together. */
    long inMemorySegmentCount() {
        long total = 0;
        for (InMemoryKnowledgeBase knowledgeBase : inMemoryStores.values()) {
            total += knowledgeBase.store().size();
        }
        return total;
    }

    /**
     * The part of a knowledge base's configuration that decides whether vectors
     * already stored are comparable with new ones: store type, embedding provider,
     * and the parameters that pick the model ({@code model}, Azure's
     * {@code deploymentName}). Credentials, endpoints and task types are left out —
     * rotating a key does not change a vector.
     * <p>
     * Compared on the configured text: a {@code model} given as a global-variable
     * reference is compared as the reference, so a change to the variable's value
     * is not seen here.
     */
    public static String embeddingSignature(RagConfiguration config) {
        Map<String, String> params = config.getEmbeddingParameters() != null ? config.getEmbeddingParameters() : Map.of();
        String provider = config.getEmbeddingProvider() == null ? "" : config.getEmbeddingProvider().trim();
        return String.join("|", String.valueOf(config.getStoreType()), provider,
                Objects.toString(params.get("model"), ""), Objects.toString(params.get("deploymentName"), ""));
    }

    private static boolean isInMemory(RagConfiguration config) {
        return IN_MEMORY.equals(config.getStoreType());
    }

    private InMemoryKnowledgeBase inMemoryStoreForWrite(RagConfiguration config, String kbId) {
        String signature = embeddingSignature(config);
        return inMemoryStores.compute(kbId, (id, existing) -> {
            if (existing == null) {
                return newInMemoryStore(config, id);
            }
            if (existing.embeddingSignature().equals(signature)) {
                return existing;
            }
            LOGGER.warnf("Knowledge base '%s' is being written with a different embedding configuration; its in-memory "
                    + "vectors (%d segments) were made by another model and are discarded. Every source re-embeds on "
                    + "its next run.", sanitize(id), existing.store().size());
            return newInMemoryStore(config, id);
        });
    }

    private InMemoryKnowledgeBase newInMemoryStore(RagConfiguration config, String kbId) {
        LOGGER.infof("Building embedding store: type=%s, kbId=%s", IN_MEMORY, sanitize(kbId));
        CappedInMemoryEmbeddingStore.CapacityGuard guard = new CappedInMemoryEmbeddingStore.CapacityGuard() {
            @Override
            public Object lock() {
                return inMemoryWriteLock;
            }

            @Override
            public void reserve(String knowledgeBaseId, int incoming) {
                reserveInMemorySegments(knowledgeBaseId, incoming);
            }
        };
        return new InMemoryKnowledgeBase(new CappedInMemoryEmbeddingStore(kbId, guard), embeddingSignature(config));
    }

    private void reserveInMemorySegments(String knowledgeBaseId, int incoming) {
        if (maxInMemorySegments <= 0 || incoming <= 0) {
            return;
        }
        long held = inMemorySegmentCount();
        if (held + incoming > maxInMemorySegments) {
            throw new InMemoryStoreFullException(String.format(
                    "Cannot store %d more segments in the in-memory vector store of knowledge base '%s': in-memory "
                            + "knowledge bases already hold %d of the %d segments this deployment allows (%s). Nothing "
                            + "was evicted to make room. Move large knowledge bases to a persistent store such as "
                            + "pgvector, delete in-memory knowledge bases that are no longer needed, or raise %s.",
                    incoming, sanitize(knowledgeBaseId), held, maxInMemorySegments, MAX_IN_MEMORY_SEGMENTS_PROPERTY,
                    MAX_IN_MEMORY_SEGMENTS_PROPERTY));
        }
    }

    /**
     * Builds an external store's client. Package-private so a test can stand in for
     * it: an external client may open sockets as it is built, which a unit test of
     * the cache around it does not need.
     */
    EmbeddingStore<TextSegment> build(RagConfiguration config, String kbId) {
        String storeType = config.getStoreType();
        LOGGER.infof("Building embedding store: type=%s, kbId=%s", sanitize(storeType), sanitize(kbId));

        return switch (storeType) {
            case "pgvector" -> buildPgVector(config, kbId);
            case "mongodb-atlas" -> buildMongoDbAtlas(config, kbId);
            case "elasticsearch" -> buildElasticsearch(config, kbId);
            case "qdrant" -> buildQdrant(config, kbId);
            case "chroma" -> buildChroma(config, kbId);
            default -> throw new IllegalArgumentException(
                    "Unsupported store type: " + storeType + ". Supported: in-memory, pgvector, mongodb-atlas, elasticsearch, qdrant, chroma");
        };
    }

    // ──────────────────────────────────────────────────
    // pgvector
    // ──────────────────────────────────────────────────

    /**
     * Builds a pgvector-backed {@link EmbeddingStore} from configuration
     * parameters.
     * <p>
     * Supported storeParameters:
     * <ul>
     * <li>{@code host} — PostgreSQL host (default: "localhost")</li>
     * <li>{@code port} — PostgreSQL port (default: 5432)</li>
     * <li>{@code database} — database name (default: "eddi")</li>
     * <li>{@code user} — database user (default: "eddi")</li>
     * <li>{@code password} — database password, supports {@code ${vault:...}}
     * (required)</li>
     * <li>{@code table} — table name (default: auto-generated from kbId)</li>
     * <li>{@code dimension} — embedding vector dimension (default: 1536 for OpenAI
     * text-embedding-3-small)</li>
     * </ul>
     */
    private EmbeddingStore<TextSegment> buildPgVector(RagConfiguration config, String kbId) {
        Map<String, String> params = resolveParams(config);

        String host = params.getOrDefault("host", "localhost");
        guardMetadataHost(host);
        int port = parseIntParam(params, "port", 5432);
        String database = params.getOrDefault("database", "eddi");
        String user = params.getOrDefault("user", "eddi");
        String password = requireParam(params, "password", "pgvector");
        int dimension = parseIntParam(params, "dimension", 1536);

        // Table name: use explicit param, or derive a safe name from kbId
        String table = params.getOrDefault("table", sanitizeTableName(kbId));

        LOGGER.infof("Building pgvector store: host=%s, port=%d, database=%s, table=%s, dimension=%d", sanitize(host), port, sanitize(database),
                sanitize(table), dimension);

        return PgVectorEmbeddingStore.builder().host(host).port(port).database(database).user(user).password(password).table(table)
                .dimension(dimension).createTable(true).build();
    }

    // ──────────────────────────────────────────────────
    // MongoDB Atlas
    // ──────────────────────────────────────────────────

    /**
     * Builds a MongoDB Atlas Vector Search-backed embedding store.
     * <p>
     * Supported storeParameters:
     * <ul>
     * <li>{@code connectionString} — MongoDB connection string (required, supports
     * {@code ${vault:...}})</li>
     * <li>{@code databaseName} — database name (default: "eddi")</li>
     * <li>{@code collectionName} — collection name (default: auto-generated from
     * kbId)</li>
     * <li>{@code indexName} — Atlas Search index name (default:
     * "vector_index")</li>
     * </ul>
     */
    private EmbeddingStore<TextSegment> buildMongoDbAtlas(RagConfiguration config, String kbId) {
        Map<String, String> params = resolveParams(config);

        String connectionString = requireParam(params, "connectionString", "mongodb-atlas");
        String databaseName = params.getOrDefault("databaseName", "eddi");
        String collectionName = params.getOrDefault("collectionName", "eddi_kb_" + kbId);
        String indexName = params.getOrDefault("indexName", "vector_index");

        LOGGER.infof("Building MongoDB Atlas store: database=%s, collection=%s, index=%s", sanitize(databaseName), sanitize(collectionName),
                sanitize(indexName));

        // Keyed on a digest, never on the connection string itself: the string is a
        // credential and a cache key outlives the call that produced it.
        MongoClient mongoClient = mongoClientCache.get(connectionKey(connectionString), key -> {
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(connectionString))
                    .build();
            return MongoClients.create(settings, DRIVER_INFO);
        });

        return MongoDbEmbeddingStore.builder().fromClient(mongoClient).databaseName(databaseName).collectionName(collectionName).indexName(indexName)
                .build();
    }

    // ──────────────────────────────────────────────────
    // Elasticsearch
    // ──────────────────────────────────────────────────

    /**
     * Builds an Elasticsearch-backed embedding store.
     * <p>
     * Supported storeParameters:
     * <ul>
     * <li>{@code serverUrl} — Elasticsearch URL (default:
     * "http://localhost:9200")</li>
     * <li>{@code apiKey} — API key (optional, supports {@code ${vault:...}})</li>
     * <li>{@code indexName} — index name (default: auto-generated from kbId)</li>
     * </ul>
     */
    @SuppressWarnings("removal") // serverUrl/apiKey/userName/password deprecated in favor of
                                 // restClient(RestClient), but usable without direct ES REST client dependency
    private EmbeddingStore<TextSegment> buildElasticsearch(RagConfiguration config, String kbId) {
        Map<String, String> params = resolveParams(config);

        String serverUrl = params.getOrDefault("serverUrl", "http://localhost:9200");
        UrlValidationUtils.rejectCloudMetadataTarget(serverUrl);
        String indexName = params.getOrDefault("indexName", "eddi_kb_" + UNSAFE_IDENTIFIER_CHARS.matcher(kbId.toLowerCase()).replaceAll("_"));

        var builder = ElasticsearchEmbeddingStore.builder().serverUrl(serverUrl).indexName(indexName);

        if (params.containsKey("apiKey")) {
            builder.apiKey(params.get("apiKey"));
        }
        if (params.containsKey("userName") && params.containsKey("password")) {
            builder.userName(params.get("userName"));
            builder.password(params.get("password"));
        }

        LOGGER.infof("Building Elasticsearch store: serverUrl=%s, index=%s", endpointForLog(serverUrl), sanitize(indexName));

        return builder.build();
    }

    // ──────────────────────────────────────────────────
    // Qdrant
    // ──────────────────────────────────────────────────

    /**
     * Builds a Qdrant-backed embedding store.
     * <p>
     * Supported storeParameters:
     * <ul>
     * <li>{@code host} — Qdrant host (default: "localhost")</li>
     * <li>{@code port} — Qdrant gRPC port (default: 6334)</li>
     * <li>{@code collectionName} — collection name (default: auto-generated from
     * kbId)</li>
     * <li>{@code apiKey} — Qdrant API key (optional, supports
     * {@code ${vault:...}})</li>
     * <li>{@code useTls} — use TLS (default: "false")</li>
     * </ul>
     */
    private EmbeddingStore<TextSegment> buildQdrant(RagConfiguration config, String kbId) {
        Map<String, String> params = resolveParams(config);

        String host = params.getOrDefault("host", "localhost");
        guardMetadataHost(host);
        int port = parseIntParam(params, "port", 6334);
        String collectionName = params.getOrDefault("collectionName", sanitizeCollection(kbId));
        boolean useTls = Boolean.parseBoolean(params.getOrDefault("useTls", "false"));

        LOGGER.infof("Building Qdrant store: host=%s, port=%d, collection=%s, tls=%b", sanitize(host), port, sanitize(collectionName), useTls);

        var builder = QdrantEmbeddingStore.builder().host(host).port(port).collectionName(collectionName).useTls(useTls);

        if (params.containsKey("apiKey")) {
            builder.apiKey(params.get("apiKey"));
        }

        return builder.build();
    }

    // ──────────────────────────────────────────────────
    // Chroma
    // ──────────────────────────────────────────────────

    /**
     * Builds a Chroma-backed embedding store.
     * <p>
     * Supported storeParameters:
     * <ul>
     * <li>{@code baseUrl} — Chroma server URL (default:
     * "http://localhost:8000")</li>
     * <li>{@code tenantName} — tenant name (default: "default_tenant")</li>
     * <li>{@code databaseName} — database name (default: "default_database")</li>
     * <li>{@code collectionName} — collection name (default: auto-generated from
     * kbId)</li>
     * </ul>
     */
    private EmbeddingStore<TextSegment> buildChroma(RagConfiguration config, String kbId) {
        Map<String, String> params = resolveParams(config);

        String baseUrl = params.getOrDefault("baseUrl", "http://localhost:8000");
        UrlValidationUtils.rejectCloudMetadataTarget(baseUrl);
        String tenantName = params.getOrDefault("tenantName", "default_tenant");
        String databaseName = params.getOrDefault("databaseName", "default_database");
        String collectionName = params.getOrDefault("collectionName", sanitizeCollection(kbId));

        ChromaApiVersion version = parseChromaApiVersion(params.getOrDefault("apiVersion", "V2"));

        LOGGER.infof("Building Chroma store: baseUrl=%s, tenant=%s, database=%s, collection=%s, apiVersion=%s", endpointForLog(baseUrl),
                sanitize(tenantName), sanitize(databaseName), sanitize(collectionName), version.toString());

        return ChromaEmbeddingStore.builder()
                .baseUrl(baseUrl)
                .tenantName(tenantName)
                .databaseName(databaseName)
                .collectionName(collectionName)
                .apiVersion(version)
                .build();
    }

    // ──────────────────────────────────────────────────
    // Utility methods
    // ──────────────────────────────────────────────────

    /**
     * Resolves global variable and vault references in store parameters.
     */
    private Map<String, String> resolveParams(RagConfiguration config) {
        Map<String, String> rawParams = config.getStoreParameters() != null ? config.getStoreParameters() : Map.of();
        Map<String, String> resolved = globalVariableResolver.resolveAll(rawParams);
        ConnectionParameterGuard.rejectConnectionReferences(resolved);
        return SecretResolver.requireResolved(secretResolver.resolveSecrets(resolved), "vector store '" + config.getStoreType() + "'");
    }

    private ChromaApiVersion parseChromaApiVersion(String apiVersionStr) {
        try {
            ChromaApiVersion chromaApiVersion = (apiVersionStr == null || apiVersionStr.isBlank())
                    ? ChromaApiVersion.V2
                    : ChromaApiVersion.valueOf(apiVersionStr);
            return chromaApiVersion;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    String.format("Invalid '%s' ChromaApiVersion. Valid ChromaApiVersion '%s'",
                            apiVersionStr,
                            Arrays.toString(ChromaApiVersion.values())),
                    e);
        }
    }

    /**
     * Requires a parameter to be present and non-blank. Fails fast with a clear
     * error message.
     */
    private String requireParam(Map<String, String> params, String key, String storeType) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(storeType + " requires '" + key + "' in storeParameters (use ${vault:...} for secrets)");
        }
        return value;
    }

    /**
     * Parses an integer parameter with a default, providing a clear error on
     * invalid values.
     */
    private int parseIntParam(Map<String, String> params, String key, int defaultValue) {
        String raw = params.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer value for '" + key + "': " + raw, e);
        }
    }

    /**
     * A store URL as it may be logged: scheme, host and port. A URL in
     * {@code storeParameters} can carry credentials — {@code user:password@}, a
     * token in the query, or one in the path — and
     * {@link ai.labs.eddi.utils.LogSanitizer#sanitize} only neutralises control
     * characters, so it left both in the log line.
     */
    static String endpointForLog(String url) {
        if (url == null) {
            return "null";
        }
        try {
            URI parsed = URI.create(url.trim());
            if (parsed.getHost() == null) {
                return "<unparseable URL>";
            }
            String port = parsed.getPort() >= 0 ? ":" + parsed.getPort() : "";
            return sanitize(parsed.getScheme() + "://" + parsed.getHost() + port);
        } catch (IllegalArgumentException e) {
            return "<unparseable URL>";
        }
    }

    static String sanitizeCollection(String kbId) {
        String name = "eddi_kb_" + UNSAFE_IDENTIFIER_CHARS.matcher(kbId.toLowerCase()).replaceAll("_");
        // A scan from the end, not the "_+$" regex it replaces: an unanchored-start
        // "_+$" retries at every underscore, quadratic on a name that is a long run of
        // them (CodeQL java/polynomial-redos), and the name is operator input.
        int end = name.length();
        while (end > 0 && name.charAt(end - 1) == '_') {
            end--;
        }
        return name.substring(0, end);
    }

    /**
     * Refuses a vector-store host that is, or resolves to, the cloud
     * instance-metadata service. The value is a bare host (no scheme), so it is
     * wrapped in a URL for the check; other private/internal hosts stay allowed —
     * this is a metadata guard, not an SSRF lockout of legitimate internal stores.
     */
    private static void guardMetadataHost(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        String bare = host.trim();
        // Bracket a bare IPv6 literal so URI parsing accepts it.
        String authority = bare.indexOf(':') >= 0 && !bare.startsWith("[") ? "[" + bare + "]" : bare;
        UrlValidationUtils.rejectCloudMetadataTarget("http://" + authority);
    }

    /**
     * Converts a knowledge base ID into a safe PostgreSQL table name. Replaces
     * non-alphanumeric characters with underscores, lowercases, prefixes with
     * {@code eddi_kb_}, and truncates to the PostgreSQL identifier limit (63
     * chars).
     */
    static String sanitizeTableName(String kbId) {
        String sanitized = UNSAFE_IDENTIFIER_CHARS.matcher(kbId.toLowerCase()).replaceAll("_");
        String result = "eddi_kb_" + sanitized;
        if (result.length() > MAX_PG_IDENTIFIER_LENGTH) {
            result = result.substring(0, MAX_PG_IDENTIFIER_LENGTH);
        }
        return result;
    }

    /**
     * Stable, non-reversible cache key for a connection string. SHA-256 rather than
     * {@code hashCode()} so that two distinct clusters cannot collide onto one
     * shared client.
     */
    static String connectionKey(String connectionString) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(connectionString.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JRE.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void closeQuietly(MongoClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            LOGGER.warnf("Failed to close MongoClient: %s", e.getMessage());
        }
    }

    /**
     * Clears the external-store client cache and closes every pooled MongoClient.
     * In-memory stores are kept: they are data, not clients — see
     * {@link #dropInMemoryStore}.
     */
    public void clearCache() {
        cache.invalidateAll();
        // invalidateAll fires the removal listener, which closes each client.
        mongoClientCache.invalidateAll();
        mongoClientCache.cleanUp();
    }
}
