package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
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
     * Bounded status tracking with 1-hour expiry to prevent memory leaks.
     */
    private final Cache<String, String> ingestionStatus = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(10_000).build();

    @Inject
    public RagIngestionService(EmbeddingModelFactory embeddingModelFactory, EmbeddingStoreFactory embeddingStoreFactory) {
        this.embeddingModelFactory = embeddingModelFactory;
        this.embeddingStoreFactory = embeddingStoreFactory;
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
     * @return ingestion ID for status polling
     */
    public String ingest(String kbId, String documentContent, String documentName, RagConfiguration ragConfig) {
        String ingestionId = UUID.randomUUID().toString();
        ingestionStatus.put(ingestionId, "pending");

        Thread.startVirtualThread(() -> processIngestion(kbId, ingestionId, documentContent, documentName, ragConfig));

        return ingestionId;
    }

    private void processIngestion(String kbId, String ingestionId, String documentContent, String documentName, RagConfiguration ragConfig) {
        try {
            ingestionStatus.put(ingestionId, "processing");
            LOGGER.infof("Starting ingestion %s for KB '%s', document '%s'", ingestionId, sanitize(kbId), sanitize(documentName));

            // 1. Parse document
            Document document = Document.from(documentContent, Metadata.from("source", documentName).put("kbId", kbId));

            // 2. Chunk
            DocumentSplitter splitter = DocumentSplitters.recursive(ragConfig.getChunkSize(), ragConfig.getChunkOverlap());

            // 3. Embed + Store
            EmbeddingModel model = embeddingModelFactory.getOrCreate(ragConfig);
            EmbeddingStore<TextSegment> store = embeddingStoreFactory.getOrCreate(ragConfig, kbId);

            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder().documentSplitter(splitter).embeddingModel(model).embeddingStore(store)
                    .build();

            ingestor.ingest(document);

            ingestionStatus.put(ingestionId, "completed");
            LOGGER.infof("Ingestion %s completed for KB '%s'", ingestionId, sanitize(kbId));

        } catch (Exception e) {
            ingestionStatus.put(ingestionId, "failed: " + e.getMessage());
            LOGGER.errorf(e, "Ingestion %s failed for KB '%s': %s", ingestionId, sanitize(kbId), e.getMessage());
        }
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
