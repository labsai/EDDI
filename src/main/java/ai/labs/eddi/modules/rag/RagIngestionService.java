/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
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

/**
 * Async document ingestion into knowledge base vector stores. Uses virtual
 * threads (NATS JetStream support planned for future).
 */
@ApplicationScoped
public class RagIngestionService {

    private static final Logger LOGGER = Logger.getLogger(RagIngestionService.class);

    private final EmbeddingModelFactory embeddingModelFactory;
    private final EmbeddingStoreFactory embeddingStoreFactory;

    /**
     * What {@link #getStatus} answers for an id it holds no status for — never
     * started on this instance, or expired.
     */
    public static final String STATUS_UNKNOWN = "unknown";

    /**
     * Bounded status tracking with 1-hour expiry to prevent memory leaks.
     */
    private final Cache<String, String> ingestionStatus = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(10_000).build();

    /** Caveats on completed ingestions, kept exactly as long as their status. */
    private final Cache<String, String> ingestionWarnings = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(10_000).build();

    /** Chunk metadata: the document name the chunk was ingested under. */
    static final String METADATA_SOURCE = "source";
    /** Chunk metadata: the knowledge base the chunk belongs to. */
    static final String METADATA_KB_ID = "kbId";
    /** Chunk metadata: the ingestion that wrote the chunk. */
    static final String METADATA_INGESTION_ID = "ingestionId";

    @Inject
    public RagIngestionService(EmbeddingModelFactory embeddingModelFactory, EmbeddingStoreFactory embeddingStoreFactory) {
        this.embeddingModelFactory = embeddingModelFactory;
        this.embeddingStoreFactory = embeddingStoreFactory;
    }

    /**
     * Ingest a document into a knowledge base, adding to whatever is already stored
     * under its name. Runs on a virtual thread.
     *
     * @see #ingest(String, String, String, RagConfiguration, boolean)
     */
    public String ingest(String kbId, String documentContent, String documentName, RagConfiguration ragConfig) {
        return ingest(kbId, documentContent, documentName, ragConfig, false);
    }

    /**
     * Ingest a document into a knowledge base. Runs on a virtual thread.
     *
     * @param kbId
     *            knowledge base ID
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
    public String ingest(String kbId, String documentContent, String documentName, RagConfiguration ragConfig, boolean replace) {
        String ingestionId = UUID.randomUUID().toString();
        ingestionStatus.put(ingestionId, "pending");

        Thread.startVirtualThread(() -> processIngestion(kbId, ingestionId, documentContent, documentName, ragConfig, replace));

        return ingestionId;
    }

    private void processIngestion(String kbId, String ingestionId, String documentContent, String documentName, RagConfiguration ragConfig,
                                  boolean replace) {
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
            DocumentSplitter splitter = DocumentSplitters.recursive(ragConfig.getChunkSize(), ragConfig.getChunkOverlap());

            // 3. Embed + Store
            // DOCUMENT: these vectors are being stored. An asymmetric model embeds a
            // document differently from a query, and gets to know which.
            EmbeddingModel model = embeddingModelFactory.getOrCreate(ragConfig, EmbeddingInputType.DOCUMENT);
            EmbeddingStore<TextSegment> store = embeddingStoreFactory.getOrCreate(ragConfig, kbId);

            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder().documentSplitter(splitter).embeddingModel(model).embeddingStore(store)
                    .build();

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
                    ingestionWarnings.put(ingestionId, warning);
                    LOGGER.warnf("Ingestion %s for KB '%s': %s", ingestionId, sanitize(kbId), sanitize(warning));
                }
            }

            ingestionStatus.put(ingestionId, "completed");
            LOGGER.infof("Ingestion %s completed for KB '%s'", ingestionId, sanitize(kbId));

        } catch (Exception e) {
            ingestionStatus.put(ingestionId, "failed: " + e.getMessage());
            LOGGER.errorf(e, "Ingestion %s failed for KB '%s': %s", ingestionId, sanitize(kbId), e.getMessage());
        }
    }

    /**
     * A caveat on an ingestion that completed, or {@code null} when there is none —
     * currently only a replacement the vector store could not carry out.
     */
    public String getWarning(String ingestionId) {
        return ingestionWarnings.getIfPresent(ingestionId);
    }

    /**
     * Get the status of an ingestion operation.
     *
     * @param ingestionId
     *            the ingestion ID returned by {@link #ingest}
     * @return status string ("pending", "processing", "completed", or "failed:
     *         ...")
     */
    public String getStatus(String ingestionId) {
        String status = ingestionStatus.getIfPresent(ingestionId);
        return status != null ? status : "unknown";
    }
}
