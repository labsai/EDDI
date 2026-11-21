/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.cluster.lease.LeaseUnavailableException;
import ai.labs.eddi.engine.cluster.lease.LocalLeaseManager;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
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
 * threads. Ingestion status is kept for one hour in the
 * {@code rag-ingestion-status} cache, which a cluster shares between its nodes
 * (see {@code CacheFactory.SHARED_BUCKETS}), so the instance that answers a
 * poll need not be the one that runs the ingestion.
 * <p>
 * <b>Cluster</b>: everything that removes a document's chunks by name — a
 * replacing ingestion, and a delete — runs under that document's lease (see
 * {@link #underDocumentLease}), which is cluster-wide in NATS mode, so two
 * instances sharing one vector store never interleave them.
 */
@ApplicationScoped
public class RagIngestionService {

    private static final Logger LOGGER = Logger.getLogger(RagIngestionService.class);

    private final EmbeddingModelFactory embeddingModelFactory;
    private final EmbeddingStoreFactory embeddingStoreFactory;

    /**
     * What {@link #getStatus} answers for an id it holds no status for — never
     * issued, or expired.
     */
    public static final String STATUS_UNKNOWN = "unknown";

    /**
     * The cache holding each ingestion's status and, for one that left a caveat,
     * its warning — the two under different keys, see {@link #statusKey}.
     */
    public static final String INGESTION_CACHE_NAME = "rag-ingestion-status";

    /** How long a status, and its warning, can still be polled. */
    static final Duration STATUS_TTL = Duration.ofHours(1);

    /**
     * Status and warning of every recent ingestion, under {@link #statusKey} and
     * {@link #warningKey}. In a cluster, shared by every node.
     */
    private final ICache<String, String> ingestionState;

    /**
     * One lock per document being replaced, so two replacements of one name cannot
     * delete each other. Each stores its chunks and then removes every other
     * ingestion's under that name — run concurrently, both can store before either
     * deletes, each deletion then matches the other's new chunks, and the document
     * is left with none. {@link #delete} takes the same lock. Weak values: an entry
     * lives only while a replacement or delete holds or waits for its lock. This
     * lock is per instance: it is the whole exclusion in in-memory mode, and what
     * keeps one instance from asking the lease manager twice for one key. Between
     * instances the document's cluster lease does that (see
     * {@link #underDocumentLease}).
     */
    private final Cache<ReplaceKey, ReentrantLock> replaceLocks = Caffeine.newBuilder().weakValues().build();

    private record ReplaceKey(String kbId, String documentName) {
    }

    /** Chunk metadata: the document name the chunk was ingested under. */
    static final String METADATA_SOURCE = "source";
    /** Chunk metadata: the knowledge base the chunk belongs to. */
    static final String METADATA_KB_ID = KnowledgeBaseStorage.METADATA_KB_ID;
    /** Chunk metadata: the ingestion that wrote the chunk. */
    static final String METADATA_INGESTION_ID = "ingestionId";

    private final IConversationLeaseManager leases;

    /**
     * How long a replacement waits for the document's lock and lease before it
     * gives up and reports {@code failed}. Generous: the holder is another
     * replacement that is embedding. Not final only so a test can shorten it.
     */
    Duration replaceLeaseWait = Duration.ofMinutes(30);

    /**
     * Slack on top of a lease wait for the answer to come back: the lease manager
     * enforces the wait itself, this only keeps a lost answer from blocking
     * forever.
     */
    private static final Duration ACQUIRE_GRACE = Duration.ofSeconds(30);

    /** How far past its wait a grant may arrive and still be taken (scheduling). */
    private static final Duration LATE_GRANT_TOLERANCE = Duration.ofSeconds(1);

    @Inject
    public RagIngestionService(EmbeddingModelFactory embeddingModelFactory, EmbeddingStoreFactory embeddingStoreFactory,
            ICacheFactory cacheFactory, IConversationLeaseManager leases) {
        this.embeddingModelFactory = embeddingModelFactory;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.ingestionState = cacheFactory.getCache(INGESTION_CACHE_NAME, STATUS_TTL);
        this.leases = leases;
    }

    /** Single-instance wiring: the lease is the local lock and nothing more. */
    public RagIngestionService(EmbeddingModelFactory embeddingModelFactory, EmbeddingStoreFactory embeddingStoreFactory,
            ICacheFactory cacheFactory) {
        this(embeddingModelFactory, embeddingStoreFactory, cacheFactory, new LocalLeaseManager());
    }

    /**
     * An ingestion's status and its warning live in one cache — one shared bucket
     * in a cluster — so each kind gets a prefix of its own: neither can overwrite
     * the other, and an id polled as {@code warning:<id>} finds no status.
     * <p>
     * The key also names the knowledge base (its RAG configuration id) the
     * ingestion ran for. The ingestion id alone is global, and the status endpoint
     * checks access on the configuration in its <i>path</i>: without the binding,
     * an editor of knowledge base B who holds an ingestion id of knowledge base A
     * could poll it through B and read A's document name or failure message — on
     * any node, now that the cache is shared. With it, an id polled through any
     * other knowledge base finds nothing and is answered as unknown.
     */
    private static String statusKey(String ragConfigId, String ingestionId) {
        return "status:" + ragConfigId + ":" + ingestionId;
    }

    private static String warningKey(String ragConfigId, String ingestionId) {
        return "warning:" + ragConfigId + ":" + ingestionId;
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
        ingestionState.put(statusKey(ragConfigId, ingestionId), "pending");

        Thread.startVirtualThread(() -> processIngestion(ragConfigId, kbId, ingestionId, documentContent, documentName, ragConfig, replace));

        return ingestionId;
    }

    private void processIngestion(String ragConfigId, String kbId, String ingestionId, String documentContent, String documentName,
                                  RagConfiguration ragConfig, boolean replace) {
        try {
            ingestionState.put(statusKey(ragConfigId, ingestionId), "processing");
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
            if (replace) {
                // The lease is held from before embedding until the cleanup is done, so
                // a delete arriving meanwhile — on any instance — waits and then removes
                // these chunks too, instead of answering "deleted" and being undone by
                // them a moment later.
                underDocumentLease(kbId, documentName, replaceLeaseWait, lease -> {
                    // Looked up only once the lease is held: for an in-memory store built
                    // under another embedding configuration this replaces it, which a
                    // replacement that is then refused the lease must not have done.
                    EmbeddingStore<TextSegment> store = embeddingStoreFactory.getOrCreateForIngestion(ragConfigId, ragConfig);
                    storeAndReplace(splitter, model, document, store, ragConfigId, kbId, documentName, ingestionId, lease);
                    return null;
                });
            } else {
                // For writing: an in-memory store built under another embedding
                // configuration is replaced rather than mixed with these vectors.
                EmbeddingStore<TextSegment> store = embeddingStoreFactory.getOrCreateForIngestion(ragConfigId, ragConfig);
                storeAndReplace(splitter, model, document, store, ragConfigId, kbId, documentName, ingestionId, null);
            }

            ingestionState.put(statusKey(ragConfigId, ingestionId), "completed");
            LOGGER.infof("Ingestion %s completed for KB '%s'", ingestionId, sanitize(kbId));

        } catch (LeaseUnavailableException e) {
            ingestionState.put(statusKey(ragConfigId, ingestionId), "failed: " + e.getMessage());
            LOGGER.warnf("Ingestion %s for KB '%s' did not run: %s", ingestionId, sanitize(kbId), sanitize(e.getMessage()));
        } catch (Exception e) {
            ingestionState.put(statusKey(ragConfigId, ingestionId), "failed: " + e.getMessage());
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
                                 String ragConfigId, String kbId, String documentName, String ingestionId, LeaseHandle lease) {
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
                ingestionState.put(warningKey(ragConfigId, ingestionId), warning);
                LOGGER.warnf("Ingestion %s for KB '%s': %s", ingestionId, sanitize(kbId), sanitize(warning));
            } catch (RuntimeException e) {
                // The new chunks are already stored, so a failed delete (pgvector wraps
                // its SQLException in a RuntimeException), or a lease lost before it,
                // leaves both versions retrievable. Still a failure — the operator should
                // retry, and a retry removes every version but its own — but the status
                // says what state it left, rather than a bare "failed".
                ingestionState.put(warningKey(ragConfigId, ingestionId),
                        "The new version of '" + documentName + "' is stored, but removing the previous "
                                + "version failed, so both may be retrievable until the replacement is retried.");
                throw e;
            }
        }
    }

    /**
     * The lock that serializes everything that removes a document's chunks by name:
     * a replacing ingestion and {@link #delete}. Shared, so the two are ordered: a
     * delete never lands between a replacement's store and its cleanup, where it
     * would take the new chunks along with the old and leave a replacement that
     * reports "completed" over a document with no text, and a delete answered while
     * a replacement is still embedding cannot be undone by that replacement's
     * chunks arriving a moment later — the delete waits, and then removes them too.
     */
    ReentrantLock documentLock(String kbId, String documentName) {
        return replaceLocks.get(new ReplaceKey(kbId, documentName), key -> new ReentrantLock());
    }

    /**
     * Refuses to go on once the document's lease is lost: another instance may hold
     * it by now, and this write would undo what it did.
     */
    private static void requireHeld(LeaseHandle lease, String documentName, String step) {
        if (lease != null && lease.isLost()) {
            throw new IllegalStateException("This instance lost the lease on document '" + sanitize(documentName) + "' before " + step
                    + ", so it stopped rather than overwrite what another instance may have done meanwhile. Retry the ingestion.");
        }
    }

    /**
     * The lease key of a document: the {@code rag.} prefix and a hash of the chunk
     * {@code kbId} tag and the name — exactly the scope a cleanup deletes by. Not
     * the RAG configuration id: two configurations whose names collide share their
     * table and their tag, and so must share the lease. A name is caller-chosen
     * text and never lands in a key in clear.
     */
    static String documentLeaseKey(String kbId, String documentName) {
        return IConversationLeaseManager.RAG_DOCUMENT + KvKeys.hashed(kbId + "|" + documentName);
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
    public <T> T underDocumentLease(String kbId, String documentName, Duration maxWait, Function<LeaseHandle, T> work) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        ReentrantLock lock = documentLock(kbId, documentName);
        try {
            if (!lock.tryLock(maxWait.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new LeaseUnavailableException(LeaseUnavailableException.Reason.TIMEOUT, null,
                        "Document '" + sanitize(documentName) + "' is being replaced or deleted on this instance");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LeaseUnavailableException(LeaseUnavailableException.Reason.SHUTTING_DOWN, null,
                    "Interrupted while waiting for document '" + sanitize(documentName) + "'");
        }
        try {
            Duration remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            LeaseHandle lease = acquire(documentLeaseKey(kbId, documentName), remaining, deadline, documentName);
            try {
                return work.apply(lease);
            } finally {
                leases.release(lease);
            }
        } finally {
            lock.unlock();
        }
    }

    private LeaseHandle acquire(String key, Duration maxWait, long deadlineNanos, String documentName) {
        CompletableFuture<LeaseHandle> pending = leases.acquireKey(key, maxWait).toCompletableFuture();
        try {
            LeaseHandle granted = pending.get(maxWait.plus(ACQUIRE_GRACE).toMillis(), TimeUnit.MILLISECONDS);
            // The grace only keeps a lost answer from blocking forever; it is not extra
            // time for the caller. A grant that came in well past the wait is refused,
            // so a delete keeps its documented bound.
            if (System.nanoTime() - deadlineNanos > LATE_GRANT_TOLERANCE.toNanos()) {
                leases.release(granted);
                throw new LeaseUnavailableException(LeaseUnavailableException.Reason.TIMEOUT, null,
                        "Document '" + sanitize(documentName) + "' is being replaced or deleted on another instance");
            }
            return granted;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof LeaseUnavailableException unavailable) {
                throw new LeaseUnavailableException(unavailable.reason(), unavailable.holderNode(), describe(unavailable, documentName));
            }
            throw new IllegalStateException("Could not acquire the lease on document '" + sanitize(documentName) + "': " + e.getCause(),
                    e.getCause());
        } catch (TimeoutException e) {
            releaseWhenGranted(pending);
            throw new LeaseUnavailableException(LeaseUnavailableException.Reason.TIMEOUT, null,
                    "Document '" + sanitize(documentName) + "' is being replaced or deleted on another instance");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseWhenGranted(pending);
            throw new LeaseUnavailableException(LeaseUnavailableException.Reason.SHUTTING_DOWN, null,
                    "Interrupted while waiting for document '" + sanitize(documentName) + "'");
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
            case TIMEOUT -> "Document '" + sanitize(documentName) + "' is being replaced or deleted on another instance"
                    + (e.holderNode() != null ? " (" + sanitize(e.holderNode()) + ")" : "");
            case DEGRADED -> "The cluster lease on document '" + sanitize(documentName) + "' cannot be taken while NATS is unreachable";
            case SHUTTING_DOWN -> "This instance is shutting down";
            case DRAINING -> "This instance is drained and takes no new work";
        };
    }

    /** What {@link #delete} did. */
    public enum DeleteOutcome {
        /** Every chunk ingested under the name was removed (zero is not an error). */
        DELETED,
        /**
         * The vector store cannot delete by metadata, so nothing was removed and the
         * text is still retrievable.
         */
        UNSUPPORTED,
        /**
         * The document stayed held — by a replacement or delete of this name, here or
         * on another instance — for longer than {@link #deleteLockTimeout}, or its
         * cluster lease could not be had at all (NATS unreachable under the
         * {@code reject} degraded policy, the node shutting down or drained), or was
         * lost before the removal; nothing was removed and the call can be retried.
         */
        BUSY
    }

    /**
     * How long {@link #delete} waits for a replacement of the same name to finish.
     * A replacement holds the lock while it embeds, which for a large document can
     * take a while; a request thread should not wait indefinitely. Not final only
     * so a test can shorten it.
     */
    Duration deleteLockTimeout = Duration.ofSeconds(30);

    /** Shortens or lengthens the wait of {@link #delete}; for tests and tuning. */
    public void setDeleteLockTimeout(Duration deleteLockTimeout) {
        this.deleteLockTimeout = deleteLockTimeout;
    }

    /**
     * Removes what {@link #ingest} stored under {@code documentName} in this
     * knowledge base — the same chunks {@code replace=true} supersedes: those
     * tagged with the name as {@code source} and with this knowledge base's
     * {@code kbId}, whichever ingestion wrote them. Synchronous; runs under the
     * per-document lock and cluster lease a replacement takes (see
     * {@link #underDocumentLease}).
     * <p>
     * Chunks written by an ingestion source are never matched: they carry the
     * source's name as {@code sourceName} and are owned by a {@code sourceKey}, and
     * no {@code source} key — the filter below cannot select them. They are removed
     * through the source.
     *
     * @param ragConfigId
     *            the RAG configuration's id
     * @param documentName
     *            the name the document was ingested under; required — and never the
     *            {@code unnamed} default, which every unnamed document shares
     * @param ragConfig
     *            the RAG configuration, which decides the store and the chunk tag
     * @return what was done
     * @throws IllegalArgumentException
     *             when the name is missing, or the configuration cannot be
     *             addressed
     * @throws RuntimeException
     *             when the store fails to delete; the client should retry
     */
    public DeleteOutcome delete(String ragConfigId, String documentName, RagConfiguration ragConfig) {
        if (documentName == null || documentName.isBlank()) {
            throw new IllegalArgumentException("A document name is required to delete a document");
        }
        String kbId = KnowledgeBaseStorage.chunkKbId(ragConfigId, ragConfig);

        try {
            return underDocumentLease(kbId, documentName, deleteLockTimeout, lease -> {
                try {
                    // Not getOrCreateForIngestion: for an in-memory knowledge base that would
                    // replace a store built under another embedding configuration, and a
                    // delete has no say in which vectors are current.
                    EmbeddingStore<TextSegment> store = embeddingStoreFactory.getOrCreate(ragConfigId, ragConfig);
                    // After the lookup, which can open a connection: a lease lost meanwhile
                    // means another node may have replaced the document by now.
                    if (lease.isLost()) {
                        LOGGER.warnf("Delete of document '%s' from KB '%s' not run: the lease was lost before the removal", sanitize(documentName),
                                sanitize(kbId));
                        return DeleteOutcome.BUSY;
                    }
                    store.removeAll(metadataKey(METADATA_SOURCE).isEqualTo(documentName)
                            .and(metadataKey(METADATA_KB_ID).isEqualTo(kbId)));
                    LOGGER.infof("Deleted document '%s' from KB '%s'", sanitize(documentName), sanitize(kbId));
                    return DeleteOutcome.DELETED;
                } catch (UnsupportedOperationException e) {
                    LOGGER.warnf("KB '%s' cannot delete by metadata, so document '%s' was not deleted: %s", sanitize(kbId),
                            sanitize(documentName), e.getMessage());
                    return DeleteOutcome.UNSUPPORTED;
                }
            });
        } catch (LeaseUnavailableException e) {
            LOGGER.infof("Delete of document '%s' from KB '%s' not run (%s): %s", sanitize(documentName), sanitize(kbId), e.reason(),
                    sanitize(e.getMessage()));
            return DeleteOutcome.BUSY;
        }
    }

    /**
     * A caveat on an ingestion that completed, or {@code null} when there is none —
     * currently only a replacement the vector store could not carry out. Also
     * {@code null} when the ingestion belongs to another knowledge base.
     *
     * @param ragConfigId
     *            the RAG configuration the ingestion was started for
     */
    public String getWarning(String ragConfigId, String ingestionId) {
        return ingestionState.get(warningKey(ragConfigId, ingestionId));
    }

    /**
     * Get the status of an ingestion operation.
     *
     * @param ragConfigId
     *            the RAG configuration the ingestion was started for; an ingestion
     *            of any other knowledge base is {@link #STATUS_UNKNOWN}
     * @param ingestionId
     *            the ingestion ID returned by {@link #ingest}
     * @return status string ("pending", "processing", "completed", "failed: ...",
     *         or {@link #STATUS_UNKNOWN} when no status is held for the id: never
     *         started, expired, or started for another knowledge base)
     */
    public String getStatus(String ragConfigId, String ingestionId) {
        String status = ingestionState.get(statusKey(ragConfigId, ingestionId));
        return status != null ? status : STATUS_UNKNOWN;
    }
}
