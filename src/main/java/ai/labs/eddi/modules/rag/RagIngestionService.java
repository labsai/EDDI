/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Async document ingestion into knowledge base vector stores. Uses virtual
 * threads. Ingestion status is kept for one hour in the
 * {@code rag-ingestion-status} cache, which a cluster shares between its nodes
 * (see {@code CacheFactory.SHARED_BUCKETS}), so the instance that answers a
 * poll need not be the one that runs the ingestion.
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
     * is left with none. Weak values: an entry lives only while a replacement holds
     * or waits for its lock. Per instance: replacements of one name arriving at two
     * instances at once are not serialized.
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

    /**
     * Redacts a failed ingestion's message before it is stored as its status.
     * Field-injected; null in tests built with {@code new}, which then redact
     * credential shapes only.
     */
    @Inject
    KnowledgeBaseFailureRedactor failureRedactor;

    @Inject
    public RagIngestionService(EmbeddingModelFactory embeddingModelFactory, EmbeddingStoreFactory embeddingStoreFactory,
            ICacheFactory cacheFactory) {
        this.embeddingModelFactory = embeddingModelFactory;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.ingestionState = cacheFactory.getCache(INGESTION_CACHE_NAME, STATUS_TTL);
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
            // For writing: an in-memory store built under another embedding
            // configuration is replaced rather than mixed with these vectors.
            EmbeddingStore<TextSegment> store = embeddingStoreFactory.getOrCreateForIngestion(ragConfigId, ragConfig);

            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder().documentSplitter(splitter).embeddingModel(model).embeddingStore(store)
                    .build();

            if (replace) {
                ReentrantLock replaceLock = replaceLocks.get(new ReplaceKey(ragConfigId + "|" + kbId, documentName), key -> new ReentrantLock());
                replaceLock.lock();
                try {
                    storeAndReplace(ingestor, document, store, ragConfigId, kbId, documentName, ingestionId, true);
                } finally {
                    replaceLock.unlock();
                }
            } else {
                storeAndReplace(ingestor, document, store, ragConfigId, kbId, documentName, ingestionId, false);
            }

            ingestionState.put(statusKey(ragConfigId, ingestionId), "completed");
            LOGGER.infof("Ingestion %s completed for KB '%s'", ingestionId, sanitize(kbId));

        } catch (Exception e) {
            // The status is returned to anyone with VIEW on the knowledge base — on any
            // node, the cache being shared — and the message is the provider's:
            // redacted before it is kept.
            var redacted = KnowledgeBaseFailureRedactor.redactFailure(failureRedactor, ragConfig, e);
            ingestionState.put(statusKey(ragConfigId, ingestionId), "failed: " + redacted.text());
            if (redacted.altered()) {
                // The exception and its causes carry what was just removed: the log gets
                // the redacted cause chain (or only its classes), never the exception.
                LOGGER.errorf("Ingestion %s failed for KB '%s': %s", ingestionId, sanitize(kbId), redacted.logText());
            } else {
                LOGGER.errorf(e, "Ingestion %s failed for KB '%s': %s", ingestionId, sanitize(kbId), e.getMessage());
            }
        }
    }

    private void storeAndReplace(EmbeddingStoreIngestor ingestor, Document document, EmbeddingStore<TextSegment> store, String ragConfigId,
                                 String kbId, String documentName, String ingestionId, boolean replace) {
        ingestor.ingest(document);

        // 4. Replace: remove what the document had before, AFTER the new version is
        // stored — a failure above leaves the previous version retrievable rather
        // than leaving the document with no vectors at all. Chunks written before
        // chunks were tagged with an ingestion id carry no such key, and
        // isNotEqualTo matches them too, so they are superseded as well.
        if (replace) {
            try {
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
                // its SQLException in a RuntimeException) leaves both versions
                // retrievable. Still a failure — the operator should retry, and a retry
                // removes every version but its own — but the status says what state it
                // left, rather than a bare "failed".
                ingestionState.put(warningKey(ragConfigId, ingestionId),
                        "The new version of '" + documentName + "' is stored, but removing the previous "
                                + "version failed, so both may be retrievable until the replacement is retried.");
                throw e;
            }
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
     * @return status string ("pending", "processing", "completed", or "failed:
     *         ...")
     */
    public String getStatus(String ragConfigId, String ingestionId) {
        String status = ingestionState.get(statusKey(ragConfigId, ingestionId));
        return status != null ? status : STATUS_UNKNOWN;
    }
}
