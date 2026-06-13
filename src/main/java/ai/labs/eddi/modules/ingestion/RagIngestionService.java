/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.ingestion.model.RagIngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.utils.LogSanitizer;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the RAG ingestion pipeline: fetch → convert → dedup → chunk →
 * embed → store.
 * <p>
 * This service is source-type-agnostic. It dispatches to the appropriate
 * {@link ContentFetcher} based on the source configuration's type field, then
 * uses the appropriate {@link ContentConverter} based on document content type.
 * <p>
 * Supported source types:
 * <ul>
 * <li>{@code "web"} — {@link WebContentFetcher} for web crawling</li>
 * </ul>
 * <p>
 * This service coordinates:
 * <ol>
 * <li>{@link ContentFetcher} — fetch content from the source</li>
 * <li>{@link ContentConverter} — convert to Markdown</li>
 * <li>{@link IContentHashStore} — SHA-256 deduplication + stale marking</li>
 * <li>{@link EmbeddingModelFactory} — cached embedding model creation</li>
 * <li>{@link EmbeddingStoreFactory} — cached vector store access</li>
 * </ol>
 * <p>
 * Runs on virtual threads for async processing. Tracks metrics via Micrometer.
 *
 * @since 6.0.3
 */
@ApplicationScoped
public class RagIngestionService {

    private static final Logger LOGGER = Logger.getLogger(RagIngestionService.class);

    private final Instance<ContentFetcher> fetcherInstances;
    private final Instance<ContentConverter> converters;
    private final IContentHashStore contentHashTracker;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final IResourceClientLibrary resourceClientLibrary;
    private final MeterRegistry meterRegistry;

    // Map of fetchers built from CDI instances (keyed by fetcher type)
    private Map<String, ContentFetcher> fetchers;

    // Metrics
    private Counter documentsFetchedCounter;
    private Counter chunksStoredCounter;
    private Counter chunksSkippedCounter;
    private Counter documentsStaleCounter;
    private Counter errorsCounter;
    private Timer fetchDurationTimer;
    private Timer totalDurationTimer;

    @Inject
    public RagIngestionService(
            Instance<ContentFetcher> fetcherInstances,
            Instance<ContentConverter> converters,
            IContentHashStore contentHashTracker,
            EmbeddingModelFactory embeddingModelFactory,
            EmbeddingStoreFactory embeddingStoreFactory,
            IResourceClientLibrary resourceClientLibrary,
            MeterRegistry meterRegistry) {
        this.fetcherInstances = fetcherInstances;
        this.converters = converters;
        this.contentHashTracker = contentHashTracker;
        this.embeddingModelFactory = embeddingModelFactory;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.resourceClientLibrary = resourceClientLibrary;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        // Build fetcher map from CDI instances
        fetchers = new HashMap<>();
        for (ContentFetcher fetcher : fetcherInstances) {
            String type = fetcher.getType();
            if (fetchers.put(type, fetcher) != null) {
                LOGGER.warnf("Duplicate ContentFetcher for type '%s' - last one wins", type);
            }
            LOGGER.debugf("Registered ContentFetcher for type: %s", type);
        }

        // Initialize metrics
        documentsFetchedCounter = meterRegistry.counter("eddi.ingestion.fetch.documents");
        chunksStoredCounter = meterRegistry.counter("eddi.ingestion.chunks.stored");
        chunksSkippedCounter = meterRegistry.counter("eddi.ingestion.chunks.skipped");
        documentsStaleCounter = meterRegistry.counter("eddi.ingestion.documents.stale");
        errorsCounter = meterRegistry.counter("eddi.ingestion.errors");
        fetchDurationTimer = meterRegistry.timer("eddi.ingestion.fetch.duration");
        totalDurationTimer = meterRegistry.timer("eddi.ingestion.total.duration");
    }

    /**
     * Ingests content from a source: fetch, convert to Markdown, deduplicate,
     * chunk, embed, store.
     * <p>
     * This method blocks until ingestion is complete. Call from a virtual thread or
     * async executor for non-blocking operation.
     *
     * @param sourceId
     *            the ingestion source ID
     * @param sourceConfig
     *            the ingestion source configuration
     * @return the ingestion result
     */
    public IngestionResult ingest(String sourceId, RagIngestionSource sourceConfig) {
        long totalStart = System.currentTimeMillis();
        LOGGER.infof("Starting ingestion for source '%s' (type=%s): %s",
                LogSanitizer.sanitize(sourceId),
                LogSanitizer.sanitize(sourceConfig.type()),
                LogSanitizer.sanitize(sourceConfig.name()));

        try {
            // 1. Resolve the associated RagConfiguration
            RagConfiguration ragConfig = resolveRagConfiguration(sourceConfig.ragConfigUri());
            String kbId = sourceConfig.name(); // Use source name as knowledge base ID

            // 2. Get the appropriate fetcher for this source type
            ContentFetcher fetcher = fetchers.get(sourceConfig.type());
            if (fetcher == null) {
                throw new IllegalArgumentException(
                        "No ContentFetcher found for source type: " + sourceConfig.type());
            }

            // 3. Fetch content from the source
            long fetchStart = System.currentTimeMillis();
            FetchResult fetchResult = fetchDurationTimer.recordCallable(
                    () -> fetcher.fetch(sourceId, sourceConfig.sourceConfig()));
            long fetchDuration = System.currentTimeMillis() - fetchStart;

            documentsFetchedCounter.increment(fetchResult.documents().size());
            errorsCounter.increment(fetchResult.errors().size());

            LOGGER.infof("Fetch completed for '%s': %d documents, %d errors in %d ms",
                    LogSanitizer.sanitize(sourceId), fetchResult.documents().size(),
                    fetchResult.errors().size(), fetchDuration);

            // 4. Convert to Markdown and deduplicate
            List<IContentHashStore.DocumentToProcess> docsToProcess = new ArrayList<>();
            List<String> unchangedIds = new ArrayList<>();

            for (FetchedDocument doc : fetchResult.documents()) {
                try {
                    // Find appropriate converter for content type
                    ContentConverter converter = findConverter(doc.contentType());
                    if (converter == null) {
                        LOGGER.warnf("No converter found for content type '%s' on document: %s",
                                doc.contentType(), LogSanitizer.sanitize(doc.id()));
                        errorsCounter.increment();
                        continue;
                    }

                    // Convert to Markdown
                    String markdown = converter.convert(
                            doc.content(),
                            doc.id(),
                            sourceConfig.ingestionSettings().maxContentLength());

                    // Check if we should ingest (dedup)
                    boolean shouldIngest;
                    try {
                        shouldIngest = !sourceConfig.ingestionSettings().contentHashDedup()
                                || contentHashTracker.shouldIngest(sourceId, doc.id(), markdown);
                    } catch (Exception dedupEx) {
                        LOGGER.warnf(dedupEx, "Dedup check failed for document '%s', will ingest fresh: %s",
                                LogSanitizer.sanitize(doc.id()), dedupEx.getMessage());
                        shouldIngest = true;
                    }

                    if (shouldIngest) {
                        String hash = contentHashTracker.computeHash(markdown);
                        docsToProcess.add(new IContentHashStore.DocumentToProcess(
                                doc.id(), doc.title(), markdown, hash));
                    } else {
                        unchangedIds.add(doc.id());
                        chunksSkippedCounter.increment();
                        LOGGER.debugf("Skipping unchanged document: %s", LogSanitizer.sanitize(doc.id()));
                    }
                } catch (Exception e) {
                    LOGGER.warnf(e, "Failed to process document '%s': %s", LogSanitizer.sanitize(doc.id()), e.getMessage());
                    errorsCounter.increment();
                }
            }

            LOGGER.infof("Documents to ingest for '%s': %d new/updated, %d unchanged",
                    LogSanitizer.sanitize(sourceId), docsToProcess.size(), unchangedIds.size());

            // 5. Mark stale documents
            List<String> fetchedIds = fetchResult.documents().stream()
                    .map(FetchedDocument::id)
                    .toList();
            int staleCount = contentHashTracker.markStaleDocuments(sourceId, fetchedIds);
            documentsStaleCounter.increment(staleCount);

            // 6. Embed and store
            int chunksStored = 0;
            if (!docsToProcess.isEmpty()) {
                chunksStored = embedAndStore(docsToProcess, ragConfig, kbId);
                chunksStoredCounter.increment(chunksStored);
            }

            long totalDuration = System.currentTimeMillis() - totalStart;
            totalDurationTimer.record(java.time.Duration.ofMillis(totalDuration));

            IngestionResult result = new IngestionResult(
                    sourceId,
                    fetchResult.documents().size(),
                    docsToProcess.size(),
                    unchangedIds.size(),
                    staleCount,
                    chunksStored,
                    fetchResult.errors().size(),
                    totalDuration,
                    null);

            LOGGER.infof("Ingestion completed for '%s': %d documents fetched, %d stored, %d unchanged, %d stale, %d chunks in %d ms",
                    LogSanitizer.sanitize(sourceId), result.documentsProcessed(), result.documentsNew(),
                    result.documentsUnchanged(), result.documentsStale(), result.chunksStored(), result.durationMs());

            return result;

        } catch (Exception e) {
            long totalDuration = System.currentTimeMillis() - totalStart;
            errorsCounter.increment();
            LOGGER.errorf(e, "Ingestion failed for source '%s'", LogSanitizer.sanitize(sourceId));
            return new IngestionResult(sourceId, 0, 0, 0, 0, 0, 1, totalDuration, Optional.of(e.getMessage()));
        }
    }

    private ContentConverter findConverter(String contentType) {
        if (contentType == null) {
            // Default to first available converter
            return converters.stream().findFirst().orElse(null);
        }

        // Find a converter that supports this content type
        for (ContentConverter converter : converters) {
            if (converter.supports(contentType)) {
                return converter;
            }
        }

        // Fall back to first converter if no specific match
        return converters.stream().findFirst().orElse(null);
    }

    private RagConfiguration resolveRagConfiguration(String ragConfigUri) throws Exception {
        LOGGER.debugf("Resolving RAG configuration from: %s", LogSanitizer.sanitize(ragConfigUri));
        return resourceClientLibrary.getResource(URI.create(ragConfigUri), RagConfiguration.class);
    }

    private int embedAndStore(List<IContentHashStore.DocumentToProcess> docs, RagConfiguration ragConfig, String kbId) {
        // Get or create embedding model and store
        EmbeddingModel embeddingModel = embeddingModelFactory.getOrCreate(ragConfig);
        EmbeddingStore<TextSegment> embeddingStore = embeddingStoreFactory.getOrCreate(ragConfig, kbId);

        int totalChunks = 0;

        for (IContentHashStore.DocumentToProcess doc : docs) {
            try {
                // Create document with metadata
                Metadata metadata = Metadata.from("documentId", doc.id())
                        .put("title", doc.title())
                        .put("contentHash", doc.contentHash())
                        .put("ingestedAt", java.time.Instant.now().toString());

                Document document = Document.from(doc.markdown(), metadata);

                // Create splitter
                var splitter = DocumentSplitters.recursive(
                        ragConfig.getChunkSize(),
                        ragConfig.getChunkOverlap());

                // Create ingestor and ingest
                EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                        .documentSplitter(splitter)
                        .embeddingModel(embeddingModel)
                        .embeddingStore(embeddingStore)
                        .build();

                ingestor.ingest(document);

                // Estimate chunk count (approximate, since langchain4j doesn't expose this
                // directly)
                int estimatedChunks = Math.max(1, doc.markdown().length() / ragConfig.getChunkSize());
                totalChunks += estimatedChunks;

                LOGGER.debugf("Ingested document: %s (%d estimated chunks)", LogSanitizer.sanitize(doc.id()), estimatedChunks);

            } catch (Exception e) {
                LOGGER.errorf(e, "Failed to ingest document: %s", LogSanitizer.sanitize(doc.id()));
                errorsCounter.increment();
            }
        }

        return totalChunks;
    }

    /**
     * Result of an ingestion operation.
     */
    public record IngestionResult(
            String sourceId,
            int documentsProcessed,
            int documentsNew,
            int documentsUnchanged,
            int documentsStale,
            int chunksStored,
            int errors,
            long durationMs,
            Optional<String> error) {
        public boolean isSuccess() {
            return error == null || error.isEmpty();
        }
    }
}
