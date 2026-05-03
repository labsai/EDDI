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
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the web RAG ingestion pipeline: crawl → convert → dedup → chunk
 * → embed → store.
 * <p>
 * This service coordinates:
 * <ol>
 * <li>{@link WebCrawler} — TOC-driven BFS crawl of documentation sites</li>
 * <li>{@link HtmlToMarkdownConverter} — Clean HTML → Markdown conversion</li>
 * <li>{@link ContentHashTracker} — SHA-256 deduplication + stale marking</li>
 * <li>{@link EmbeddingModelFactory} — Cached embedding model creation</li>
 * <li>{@link EmbeddingStoreFactory} — Cached vector store access</li>
 * </ol>
 * <p>
 * Runs on virtual threads for async processing. Tracks metrics via Micrometer.
 *
 * @since 6.0.3
 */
@ApplicationScoped
public class RagWebIngestionService {

    private static final Logger LOGGER = Logger.getLogger(RagWebIngestionService.class);

    private final WebCrawler webCrawler;
    private final HtmlToMarkdownConverter markdownConverter;
    private final ContentHashTracker contentHashTracker;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final IResourceClientLibrary resourceClientLibrary;
    private final MeterRegistry meterRegistry;

    // Metrics
    private Counter crawlPagesCounter;
    private Counter chunksStoredCounter;
    private Counter chunksSkippedCounter;
    private Counter pagesStaleCounter;
    private Counter errorsCounter;
    private Timer crawlDurationTimer;
    private Timer totalDurationTimer;

    @Inject
    public RagWebIngestionService(
            WebCrawler webCrawler,
            HtmlToMarkdownConverter markdownConverter,
            ContentHashTracker contentHashTracker,
            EmbeddingModelFactory embeddingModelFactory,
            EmbeddingStoreFactory embeddingStoreFactory,
            IResourceClientLibrary resourceClientLibrary,
            MeterRegistry meterRegistry) {
        this.webCrawler = webCrawler;
        this.markdownConverter = markdownConverter;
        this.contentHashTracker = contentHashTracker;
        this.embeddingModelFactory = embeddingModelFactory;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.resourceClientLibrary = resourceClientLibrary;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        crawlPagesCounter = meterRegistry.counter("eddi.ingestion.crawl.pages");
        chunksStoredCounter = meterRegistry.counter("eddi.ingestion.chunks.stored");
        chunksSkippedCounter = meterRegistry.counter("eddi.ingestion.chunks.skipped");
        pagesStaleCounter = meterRegistry.counter("eddi.ingestion.pages.stale");
        errorsCounter = meterRegistry.counter("eddi.ingestion.errors");
        crawlDurationTimer = meterRegistry.timer("eddi.ingestion.crawl.duration");
        totalDurationTimer = meterRegistry.timer("eddi.ingestion.total.duration");
    }

    /**
     * Ingests a web source: crawl, convert to Markdown, deduplicate, chunk, embed,
     * store.
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
        LOGGER.infof("Starting web ingestion for source '%s': %s", LogSanitizer.sanitize(sourceId), LogSanitizer.sanitize(sourceConfig.getName()));

        try {
            // 1. Resolve the associated RagConfiguration
            RagConfiguration ragConfig = resolveRagConfiguration(sourceConfig.getRagConfigUri());
            String kbId = sourceConfig.getName(); // Use source name as knowledge base ID

            // 2. Crawl the website
            long crawlStart = System.currentTimeMillis();
            WebCrawler.CrawlResult crawlResult = crawlDurationTimer.recordCallable(() -> webCrawler.crawl(sourceConfig));
            long crawlDuration = System.currentTimeMillis() - crawlStart;

            crawlPagesCounter.increment(crawlResult.pages().size());
            errorsCounter.increment(crawlResult.errors().size());

            LOGGER.infof("Crawl completed for '%s': %d pages, %d errors in %d ms",
                    LogSanitizer.sanitize(sourceId), crawlResult.pages().size(), crawlResult.errors().size(), crawlDuration);

            // 3. Convert to Markdown and deduplicate
            List<ContentHashTracker.PageToProcess> pagesToProcess = new ArrayList<>();
            List<String> unchangedUrls = new ArrayList<>();

            for (WebCrawler.CrawledPage page : crawlResult.pages()) {
                try {
                    // Convert HTML to Markdown
                    String markdown = markdownConverter.convert(
                            page.html(),
                            page.url(),
                            sourceConfig.getIngestionSettings().getMaxContentLength());

                    // Check if we should ingest (dedup)
                    boolean shouldIngest = !sourceConfig.getIngestionSettings().isContentHashDedup()
                            || contentHashTracker.shouldIngest(sourceId, page.url(), markdown);

                    if (shouldIngest) {
                        String hash = contentHashTracker.computeHash(markdown);
                        pagesToProcess.add(new ContentHashTracker.PageToProcess(
                                page.url(), page.title(), markdown, hash));
                    } else {
                        unchangedUrls.add(page.url());
                        chunksSkippedCounter.increment();
                        LOGGER.debugf("Skipping unchanged page: %s", LogSanitizer.sanitize(page.url()));
                    }
                } catch (Exception e) {
                    LOGGER.warnf(e, "Failed to convert page to Markdown: %s", LogSanitizer.sanitize(page.url()));
                    errorsCounter.increment();
                }
            }

            LOGGER.infof("Pages to ingest for '%s': %d new/updated, %d unchanged",
                    LogSanitizer.sanitize(sourceId), pagesToProcess.size(), unchangedUrls.size());

            // 4. Mark stale pages
            List<String> crawledUrls = crawlResult.pages().stream()
                    .map(WebCrawler.CrawledPage::url)
                    .toList();
            int staleCount = contentHashTracker.markStalePages(sourceId, crawledUrls);
            pagesStaleCounter.increment(staleCount);

            // 5. Embed and store
            int chunksStored = 0;
            if (!pagesToProcess.isEmpty()) {
                chunksStored = embedAndStore(pagesToProcess, ragConfig, kbId);
                chunksStoredCounter.increment(chunksStored);
            }

            long totalDuration = System.currentTimeMillis() - totalStart;
            totalDurationTimer.record(java.time.Duration.ofMillis(totalDuration));

            IngestionResult result = new IngestionResult(
                    sourceId,
                    crawlResult.pages().size(),
                    pagesToProcess.size(),
                    unchangedUrls.size(),
                    staleCount,
                    chunksStored,
                    crawlResult.errors().size(),
                    totalDuration,
                    null);

            LOGGER.infof("Ingestion completed for '%s': %d pages crawled, %d stored, %d unchanged, %d stale, %d chunks in %d ms",
                    LogSanitizer.sanitize(sourceId), result.pagesCrawled(), result.pagesNew(),
                    result.pagesUnchanged(), result.pagesStale(), result.chunksStored(), result.durationMs());

            return result;

        } catch (Exception e) {
            long totalDuration = System.currentTimeMillis() - totalStart;
            errorsCounter.increment();
            LOGGER.errorf(e, "Ingestion failed for source '%s'", LogSanitizer.sanitize(sourceId));
            return new IngestionResult(sourceId, 0, 0, 0, 0, 0, 1, totalDuration, e.getMessage());
        }
    }

    private RagConfiguration resolveRagConfiguration(String ragConfigUri) throws Exception {
        LOGGER.debugf("Resolving RAG configuration from: %s", LogSanitizer.sanitize(ragConfigUri));
        return resourceClientLibrary.getResource(URI.create(ragConfigUri), RagConfiguration.class);
    }

    private int embedAndStore(List<ContentHashTracker.PageToProcess> pages, RagConfiguration ragConfig, String kbId) {
        // Get or create embedding model and store
        EmbeddingModel embeddingModel = embeddingModelFactory.getOrCreate(ragConfig);
        EmbeddingStore<TextSegment> embeddingStore = embeddingStoreFactory.getOrCreate(ragConfig, kbId);

        int totalChunks = 0;

        for (ContentHashTracker.PageToProcess page : pages) {
            try {
                // Create document with metadata
                Metadata metadata = Metadata.from("sourceUrl", page.url())
                        .put("title", page.title())
                        .put("contentHash", page.contentHash())
                        .put("ingestedAt", java.time.Instant.now().toString());

                Document document = Document.from(page.markdown(), metadata);

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
                int estimatedChunks = Math.max(1, page.markdown().length() / ragConfig.getChunkSize());
                totalChunks += estimatedChunks;

                LOGGER.debugf("Ingested page: %s (%d estimated chunks)", LogSanitizer.sanitize(page.url()), estimatedChunks);

            } catch (Exception e) {
                LOGGER.errorf(e, "Failed to ingest page: %s", LogSanitizer.sanitize(page.url()));
                errorsCounter.increment();
            }
        }

        return totalChunks;
    }

    /**
     * Result of a web ingestion operation.
     */
    public record IngestionResult(
            String sourceId,
            int pagesCrawled,
            int pagesNew,
            int pagesUnchanged,
            int pagesStale,
            int chunksStored,
            int errors,
            long durationMs,
            String error) {
        public boolean isSuccess() {
            return error == null;
        }
    }
}
