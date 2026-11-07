/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.cluster.lease.LeaseUnavailableException;
import ai.labs.eddi.engine.cluster.lease.LocalLeaseManager;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Async document ingestion into knowledge base vector stores. Uses virtual
 * threads.
 * <p>
 * <b>Cluster</b>: everything that removes a document's chunks by name — a
 * replacing ingestion, and a delete — runs under that document's lease (see
 * {@link #underDocumentLease}), which is cluster-wide in NATS mode, so two
 * instances sharing one vector store never interleave them. Ingestion status
 * and warnings live in shared caches (the {@code RAG_STATUS} and
 * {@code RAG_WARNINGS} buckets in NATS mode), so an id can be polled on any
 * instance. Both are kept for one hour.
 */
@ApplicationScoped
public class RagIngestionService {

    private static final Logger LOGGER = Logger.getLogger(RagIngestionService.class);

    private final EmbeddingModelFactory embeddingModelFactory;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final IConversationLeaseManager leases;

    /**
     * What {@link #getStatus} answers for an id it holds no status for — never
     * started, or expired.
     */
    public static final String STATUS_UNKNOWN = "unknown";

    /** How long a status and its warning are kept. */
    static final Duration STATUS_TTL = Duration.ofHours(1);

    /** Shared cache of ingestion statuses (bucket {@code RAG_STATUS}). */
    public static final String STATUS_CACHE = "rag-ingestion-status";
    /** Shared cache of ingestion warnings (bucket {@code RAG_WARNINGS}). */
    public static final String WARNING_CACHE = "rag-ingestion-warnings";

    /**
     * Ingestion statuses. Cluster-wide in NATS mode, so the status the instance
     * running an ingestion writes is what every other instance reads; node-local
     * and bounded in in-memory mode, as it always was.
     */
    private final ICache<String, String> ingestionStatus;

    /** Caveats on completed ingestions, kept exactly as long as their status. */
    private final ICache<String, String> ingestionWarnings;

    /**
     * One lock per document, taken before its lease. Within an instance it orders
     * the operations on one name, so only one of them at a time asks the lease
     * manager for the document's lease — and in in-memory mode, where every lease
     * is granted at once, it is the only exclusion there is. Weak values: an entry
     * lives only while somebody holds or waits for its lock.
     */
    private final Cache<DocumentKey, ReentrantLock> documentLocks = Caffeine.newBuilder().weakValues().build();

    private record DocumentKey(String ragConfigId, String kbId, String documentName) {
    }

    /**
     * How long a replacing ingestion waits for the document's lease — held by
     * another replacement or a delete, on this instance or another — before it
     * fails. Generous, because a replacement holds the lease while it embeds. Not
     * final only so a test can shorten it.
     */
    Duration replaceLeaseWait = Duration.ofMinutes(30);

    /**
     * Grace on top of a lease wait, for the lease manager to report its own timeout
     * before this service stops waiting for it.
     */
    private static final Duration ACQUIRE_GRACE = Duration.ofSeconds(30);

    /** Chunk metadata: the document name the chunk was ingested under. */
    static final String METADATA_SOURCE = "source";
    /** Chunk metadata: the knowledge base the chunk belongs to. */
    static final String METADATA_KB_ID = KnowledgeBaseStorage.METADATA_KB_ID;
    /** Chunk metadata: the ingestion that wrote the chunk. */
    static final String METADATA_INGESTION_ID = "ingestionId";

    @Inject
    public RagIngestionService(EmbeddingModelFactory embeddingModelFactory, EmbeddingStoreFactory embeddingStoreFactory,
            IConversationLeaseManager leases, ICacheFactory cacheFactory) {
        this.embeddingModelFactory = embeddingModelFactory;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.leases = leases;
        this.ingestionStatus = cacheFactory.getCache(STATUS_CACHE, STATUS_TTL);
        this.ingestionWarnings = cacheFactory.getCache(WARNING_CACHE, STATUS_TTL);
    }

    /** Single-instance wiring: unfenced local leases and node-local caches. */
    RagIngestionService(EmbeddingModelFactory embeddingModelFactory, EmbeddingStoreFactory embeddingStoreFactory) {
        this(embeddingModelFactory, embeddingStoreFactory, new LocalLeaseManager(), new CacheFactory());
    }

    /**
     * Ingest a document into a knowledge base, adding to whatever is already stored
     * under its name. Runs on a virtual thread.
     *
     * @see #ingest(String, String, String, RagConfiguration, boolean)
     */
    public String ingest(String ragConfigId, String documentContent, String documentName, RagConfiguration ragConfig) {
        return ingest(ragConfigId, documentContent, documentName, ragConfig, false);
    }

    /**
     * Ingest a document into a knowledge base. Runs on a virtual thread.
     * <p>
     * The knowledge base is identified by its RAG configuration id and nothing
     * else: the store it writes to and the {@code kbId} tag its chunks carry are
     * both derived from that id and the configuration (see
     * {@link KnowledgeBaseStorage}). Up to 6.5.0 this took a caller-chosen
     * {@code kbId}, which is how one knowledge base's endpoint could write into —
     * and with {@code replace} delete from — another's store.
     *
     * @param ragConfigId
     *            the RAG configuration's id
     * @param documentContent
     *            raw text content of the document
     * @param documentName
     *            display name / source of the document
     * @param ragConfig
     *            the RAG configuration defining embedding + store
     * @param replace
     *            whether this document supersedes what was previously ingested
     *            under the same {@code documentName} in this knowledge base. Off,
     *            ingesting a name twice stores both copies and retrieval returns
     *            both
     * @return ingestion ID for status polling
     */
    public String ingest(String ragConfigId, String documentContent, String documentName, RagConfiguration ragConfig, boolean replace) {
        // Resolved before the thread starts, so a configuration that cannot be
        // addressed is refused to the caller rather than reported as "failed" later.
        String kbId = KnowledgeBaseStorage.chunkKbId(ragConfigId, ragConfig);
        String ingestionId = UUID.randomUUID().toString();
        ingestionStatus.put(ingestionId, "pending");

        Thread.startVirtualThread(() -> processIngestion(ragConfigId, kbId, ingestionId, documentContent, documentName, ragConfig, replace));

        return ingestionId;
    }

    private void processIngestion(String ragConfigId, String kbId, String ingestionId, String documentContent, String documentName,
                                  RagConfiguration ragConfig, boolean replace) {
        try {
            ingestionStatus.put(ingestionId, "processing");
            LOGGER.infof("Starting ingestion %s for KB '%s', document '%s' (replace=%b)", ingestionId, sanitize(kbId), sanitize(documentName),
                    replace);

            // 1. Parse document. Every chunk carries the ingestion that wrote it, which
            // is what lets a replacing ingestion tell its own chunks from the ones it
            // supersedes.
            Document document = Document.from(documentContent, Metadata.from(METADATA_SOURCE, documentName)
                    .put(METADATA_KB_ID, kbId)
                    .put(METADATA_INGESTION_ID, ingestionId));

            // 2. Chunk
            DocumentSplitter splitter = DocumentSplitters.recursive(ragConfig.chunkSizeOrDefault(), ragConfig.chunkOverlapOrDefault());

            // 3. Embed + Store
            // DOCUMENT: these vectors are being stored. An asymmetric model embeds a
            // document differently from a query, and gets to know which.
            EmbeddingModel model = embeddingModelFactory.getOrCreate(ragConfig, EmbeddingInputType.DOCUMENT);
            // For writing: an in-memory store built under another embedding
            // configuration is replaced rather than mixed with these vectors.
            EmbeddingStore<TextSegment> store = embeddingStoreFactory.getOrCreateForIngestion(ragConfigId, ragConfig);

            if (replace) {
                // The lease is held from before embedding until the cleanup is done, so
                // a delete arriving meanwhile — on any instance — waits and then removes
                // these chunks too, instead of answering "deleted" and being undone by
                // them a moment later.
                underDocumentLease(ragConfigId, kbId, documentName, replaceLeaseWait, lease -> {
                    storeAndReplace(splitter, model, document, store, kbId, documentName, ingestionId, lease);
                    return null;
                });
            } else {
                storeAndReplace(splitter, model, document, store, kbId, documentName, ingestionId, null);
            }

            ingestionStatus.put(ingestionId, "completed");
            LOGGER.infof("Ingestion %s completed for KB '%s'", ingestionId, sanitize(kbId));

        } catch (LeaseUnavailableException e) {
            ingestionStatus.put(ingestionId, "failed: " + e.getMessage());
            LOGGER.warnf("Ingestion %s for KB '%s' did not run: %s", ingestionId, sanitize(kbId), e.getMessage());
        } catch (Exception e) {
            ingestionStatus.put(ingestionId, "failed: " + e.getMessage());
            LOGGER.errorf(e, "Ingestion %s failed for KB '%s': %s", ingestionId, sanitize(kbId), e.getMessage());
        }
    }

    /**
     * Embeds and stores the document and, given a {@code lease} (a replacement),
     * then removes what was stored under its name before.
     * <p>
     * Embedding and storing are separate steps so the lease can be checked between
     * them. A vector store cannot check a fencing token, so the closest this gets
     * to a fenced write is refusing to start one once the lease is known to be lost
     * — and a lease is reported lost while there is still margin before any other
     * node can take it over (see {@code KvLeaseManager}), which is the time a write
     * started on a held lease has to finish in.
     */
    private void storeAndReplace(DocumentSplitter splitter, EmbeddingModel model, Document document, EmbeddingStore<TextSegment> store,
                                 String kbId, String documentName, String ingestionId, LeaseHandle lease) {
        List<TextSegment> segments = splitter.split(document);
        if (!segments.isEmpty()) {
            List<Embedding> embeddings = model.embedAll(segments).content();
            requireHeld(lease, documentName, "storing the new version");
            store.addAll(embeddings, segments);
        }

        // 4. Replace: remove what the document had before, AFTER the new version is
        // stored — a failure above leaves the previous version retrievable rather
        // than leaving the document with no vectors at all. Chunks written before
        // chunks were tagged with an ingestion id carry no such key, and
        // isNotEqualTo matches them too, so they are superseded as well.
        if (lease != null) {
            try {
                requireHeld(lease, documentName, "removing the previous version");
                store.removeAll(metadataKey(METADATA_SOURCE).isEqualTo(documentName)
                        .and(metadataKey(METADATA_KB_ID).isEqualTo(kbId))
                        .and(metadataKey(METADATA_INGESTION_ID).isNotEqualTo(ingestionId)));
            } catch (UnsupportedOperationException e) {
                String warning = "The new version is stored, but this knowledge base's vector store cannot delete by metadata, "
                        + "so the previous version of '" + documentName + "' is still retrievable alongside it.";
                ingestionWarnings.put(ingestionId, warning);
                LOGGER.warnf("Ingestion %s for KB '%s': %s", ingestionId, sanitize(kbId), sanitize(warning));
            } catch (RuntimeException e) {
                // The new chunks are already stored, so a failed delete (pgvector wraps
                // its SQLException in a RuntimeException), or a lease lost before it,
                // leaves both versions retrievable. Still a failure — the operator should
                // retry, and a retry removes every version but its own — but the status
                // says what state it left, rather than a bare "failed".
                ingestionWarnings.put(ingestionId, "The new version of '" + documentName + "' is stored, but removing the previous "
                        + "version failed, so both may be retrievable until the replacement is retried.");
                throw e;
            }
        }
    }

    /**
     * Refuses to go on once the document's lease is lost: another instance may hold
     * it by now, and this write would undo what it did.
     */
    private static void requireHeld(LeaseHandle lease, String documentName, String step) {
        if (lease != null && lease.isLost()) {
            throw new IllegalStateException("This instance lost the lease on document '" + documentName + "' before " + step
                    + ", so it stopped rather than overwrite what another instance may have done meanwhile. Retry the ingestion.");
        }
    }

    /**
     * The lease key of a document: the {@code rag.} prefix and a hash of the RAG
     * configuration, the knowledge base and the name. A name is caller-chosen text
     * and never lands in a key in clear.
     */
    static String documentLeaseKey(String ragConfigId, String kbId, String documentName) {
        return IConversationLeaseManager.RAG_DOCUMENT + KvKeys.hashed(ragConfigId + "|" + kbId + "|" + documentName);
    }

    /**
     * Runs {@code work} while this instance holds the document's lock and its
     * lease, and releases both afterwards, whether {@code work} returns or throws.
     * Everything that removes a document's chunks by name runs under it: a
     * replacing ingestion (from before it embeds until its cleanup is done) and a
     * delete. In NATS mode the lease is cluster-wide, renewed by the lease
     * manager's heartbeat for as long as {@code work} runs, and reported through
     * {@link LeaseHandle#isLost()} once it could not be renewed; in in-memory mode
     * the local lock is the whole exclusion.
     *
     * @param maxWait
     *            how long to wait for the lock and the lease together
     * @param work
     *            runs with the held lease; check {@link LeaseHandle#isLost()}
     *            before each write to the vector store
     * @return what {@code work} returned
     * @throws LeaseUnavailableException
     *             when the document was still held by somebody else when
     *             {@code maxWait} passed, or its lease could not be had at all
     *             (NATS unreachable under the {@code reject} degraded policy, the
     *             node shutting down or drained); {@code work} has not run
     */
    public <T> T underDocumentLease(String ragConfigId, String kbId, String documentName, Duration maxWait, Function<LeaseHandle, T> work) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        ReentrantLock lock = documentLocks.get(new DocumentKey(ragConfigId, kbId, documentName), key -> new ReentrantLock());
        try {
            if (!lock.tryLock(maxWait.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new LeaseUnavailableException(LeaseUnavailableException.Reason.TIMEOUT, null,
                        "Document '" + documentName + "' is being replaced or deleted on this instance");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LeaseUnavailableException(LeaseUnavailableException.Reason.SHUTTING_DOWN, null,
                    "Interrupted while waiting for document '" + documentName + "'");
        }
        try {
            Duration remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            LeaseHandle lease = acquire(documentLeaseKey(ragConfigId, kbId, documentName), remaining, documentName);
            try {
                return work.apply(lease);
            } finally {
                leases.release(lease);
            }
        } finally {
            lock.unlock();
        }
    }

    private LeaseHandle acquire(String key, Duration maxWait, String documentName) {
        CompletableFuture<LeaseHandle> pending = leases.acquireKey(key, maxWait).toCompletableFuture();
        try {
            return pending.get(maxWait.plus(ACQUIRE_GRACE).toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof LeaseUnavailableException unavailable) {
                throw new LeaseUnavailableException(unavailable.reason(), unavailable.holderNode(), describe(unavailable, documentName));
            }
            throw new IllegalStateException("Could not acquire the lease on document '" + documentName + "': " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            releaseWhenGranted(pending);
            throw new LeaseUnavailableException(LeaseUnavailableException.Reason.TIMEOUT, null,
                    "Document '" + documentName + "' is being replaced or deleted on another instance");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseWhenGranted(pending);
            throw new LeaseUnavailableException(LeaseUnavailableException.Reason.SHUTTING_DOWN, null,
                    "Interrupted while waiting for document '" + documentName + "'");
        }
    }

    /**
     * A lease granted after its caller stopped waiting must not stay held — and
     * renewed by the heartbeat — with nobody to release it.
     */
    private void releaseWhenGranted(CompletableFuture<LeaseHandle> pending) {
        pending.whenComplete((handle, failure) -> {
            if (handle != null) {
                leases.release(handle);
            }
        });
    }

    private static String describe(LeaseUnavailableException e, String documentName) {
        return switch (e.reason()) {
            case TIMEOUT -> "Document '" + documentName + "' is being replaced or deleted on another instance"
                    + (e.holderNode() != null ? " (" + sanitize(e.holderNode()) + ")" : "");
            case DEGRADED -> "The cluster lease on document '" + documentName + "' cannot be taken while NATS is unreachable";
            case SHUTTING_DOWN -> "This instance is shutting down";
            case DRAINING -> "This instance is drained and takes no new work";
        };
    }

    /**
     * A caveat on an ingestion that completed, or {@code null} when there is none —
     * currently only a replacement the vector store could not carry out.
     */
    public String getWarning(String ingestionId) {
        return ingestionWarnings.get(ingestionId);
    }

    /**
     * Get the status of an ingestion operation, on whichever instance it runs.
     *
     * @param ingestionId
     *            the ingestion ID returned by {@link #ingest}
     * @return status string ("pending", "processing", "completed", or "failed:
     *         ...")
     */
    public String getStatus(String ingestionId) {
        String status = ingestionStatus.get(ingestionId);
        return status != null ? status : STATUS_UNKNOWN;
    }
}
