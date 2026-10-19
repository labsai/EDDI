/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.DocumentState;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.utils.LogSanitizer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.Filter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Read-only views into what a knowledge base holds: which documents a source
 * has ingested, the chunks one of them was split into, and what a query would
 * retrieve.
 *
 * <p>
 * The vector stores EDDI supports have no "list everything" operation, so
 * chunks are reached the way retrieval reaches them — a similarity search,
 * narrowed by the metadata every chunk is tagged with at ingestion. Nothing
 * here writes, and nothing here bypasses the per-knowledge-base isolation
 * filter retrieval applies.
 */
@ApplicationScoped
public class KnowledgeBaseInspector {

    private static final Logger LOGGER = Logger.getLogger(KnowledgeBaseInspector.class);

    /**
     * The most document states one listing reads. The same ceiling the file service
     * uses: the state store has no paging, and a source past this size is one an
     * operator searches rather than scrolls.
     */
    static final int MAX_DOCUMENTS = 10_000;

    /** Ceiling on chunks returned for one document, and on search results. */
    public static final int MAX_CHUNKS = 200;

    public static final int MAX_SEARCH_RESULTS = 50;

    /** Metadata the splitter puts on each segment: its position in the document. */
    static final String METADATA_SEGMENT_INDEX = "index";

    private final IIngestionStateStore stateStore;
    private final IIngestedFileStore fileStore;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final EmbeddingModelFactory embeddingModelFactory;

    @Inject
    public KnowledgeBaseInspector(IIngestionStateStore stateStore, IIngestedFileStore fileStore,
            EmbeddingStoreFactory embeddingStoreFactory, EmbeddingModelFactory embeddingModelFactory) {
        this.stateStore = stateStore;
        this.fileStore = fileStore;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.embeddingModelFactory = embeddingModelFactory;
    }

    /** One document a source has ingested, as its ingestion state records it. */
    public record DocumentSummary(
            String documentId,
            /** What to show: the file name for an uploaded file, else the URL. */
            String label,
            /** The URL for a crawled page; null for an uploaded file. */
            String url,
            Instant firstIngestedAt,
            Instant lastIngestedAt,
            int missedRuns,
            boolean tombstoned) {
    }

    /**
     * A page of a source's documents.
     *
     * @param total
     *            documents matching the filter, up to {@link #MAX_DOCUMENTS}
     * @param truncated
     *            the source holds more documents than were read, so {@code total}
     *            is a floor
     */
    public record DocumentPage(int total, boolean truncated, List<DocumentSummary> documents) {
    }

    /**
     * A source's documents, newest ingestion first, optionally narrowed to those
     * whose id or label contains {@code contains} (case-insensitive).
     */
    public DocumentPage listDocuments(String ragConfigId, IngestionSource source, String contains, int offset,
                                      int limit) {
        String sourceKey = IngestionPipeline.stateKey(ragConfigId, source);
        List<DocumentState> states = stateStore.listDocuments(sourceKey, MAX_DOCUMENTS + 1);
        boolean truncated = states.size() > MAX_DOCUMENTS;

        Map<String, String> fileNames = source.isUpload() ? fileNames(sourceKey) : Map.of();
        String needle = contains == null || contains.isBlank() ? null : contains.toLowerCase(Locale.ROOT).trim();

        List<DocumentSummary> matching = states.stream()
                .limit(MAX_DOCUMENTS)
                // The marker row the orphan sweep keeps is bookkeeping, not a document.
                .filter(state -> !IngestionPipeline.SWEEP_MARKER.equals(state.documentId()))
                .map(state -> summarize(state, source, fileNames))
                .filter(summary -> needle == null
                        || summary.documentId().toLowerCase(Locale.ROOT).contains(needle)
                        || summary.label().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(DocumentSummary::lastIngestedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(DocumentSummary::label))
                .toList();

        int from = Math.min(Math.max(0, offset), matching.size());
        int to = Math.min(matching.size(), from + Math.max(1, limit));
        return new DocumentPage(matching.size(), truncated, matching.subList(from, to));
    }

    /** One chunk of text as the vector store holds it. */
    public record Chunk(Integer index, String text, Double score, Map<String, Object> metadata) {
    }

    /**
     * The chunks one document of one source was split into, in document order.
     *
     * <p>
     * Found by a similarity search filtered to the document, because that is the
     * only read every supported store offers. It costs one embedding call — of the
     * document's own id, which is as good a key as any when the filter does the
     * selecting.
     *
     * @throws UnsupportedOperationException
     *             when the store cannot filter by metadata, so the document's
     *             chunks cannot be told from anyone else's
     */
    public List<Chunk> documentChunks(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source,
                                      String documentId, int limit) {
        String sourceKey = IngestionPipeline.stateKey(ragConfigId, source);
        Filter filter = metadataKey(IngestionPipeline.METADATA_DOCUMENT_ID).isEqualTo(documentId)
                .and(metadataKey(IngestionPipeline.METADATA_SOURCE_KEY).isEqualTo(sourceKey));
        int max = clamp(limit, 1, MAX_CHUNKS);
        List<EmbeddingMatch<TextSegment>> matches = search(ragConfigId, knowledgeBase, documentId, filter, max, 0.0);
        return matches.stream()
                .map(match -> toChunk(match, false))
                .sorted(Comparator.comparing(Chunk::index, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * What retrieval would return for {@code query} — the same embedding model in
     * query mode, the same isolation filter — so an operator can see why an agent
     * answered as it did without starting a conversation.
     *
     * @param source
     *            narrows the search to one source's chunks; null searches the whole
     *            knowledge base
     */
    public List<Chunk> search(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source,
                              String query, int maxResults, double minScore) {
        Filter filter = source == null
                ? null
                : metadataKey(IngestionPipeline.METADATA_SOURCE_KEY)
                        .isEqualTo(IngestionPipeline.stateKey(ragConfigId, source));
        return search(ragConfigId, knowledgeBase, query, filter, clamp(maxResults, 1, MAX_SEARCH_RESULTS),
                Math.max(0.0, Math.min(1.0, minScore)))
                .stream()
                .map(match -> toChunk(match, true))
                .toList();
    }

    private List<EmbeddingMatch<TextSegment>> search(String ragConfigId, RagConfiguration knowledgeBase, String text,
                                                     Filter narrowing, int maxResults, double minScore) {
        Embedding queryVector = embeddingModelFactory.getOrCreate(knowledgeBase, EmbeddingInputType.QUERY)
                .embed(text).content();
        // Retrieval's own isolation, always: a knowledge base in the per-id layout
        // only ever sees the chunks tagged with its own id, even in a shared table.
        Filter isolation = EmbeddingStoreFactory.retrievalFilter(ragConfigId, knowledgeBase);
        Filter filter = isolation == null ? narrowing : narrowing == null ? isolation : isolation.and(narrowing);

        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryVector)
                .maxResults(maxResults)
                .minScore(minScore)
                .filter(filter)
                .build();
        try {
            return embeddingStoreFactory.getOrCreate(ragConfigId, knowledgeBase).search(request).matches();
        } catch (UnsupportedFeatureException e) {
            LOGGER.infof("The vector store of knowledge base %s cannot filter by metadata: %s",
                    LogSanitizer.sanitize(ragConfigId), e.getMessage());
            throw new UnsupportedOperationException(
                    "This knowledge base's vector store cannot filter by metadata, so its chunks cannot be listed", e);
        }
    }

    private static Chunk toChunk(EmbeddingMatch<TextSegment> match, boolean withScore) {
        TextSegment segment = match.embedded();
        Map<String, Object> metadata = segment == null ? Map.of() : new LinkedHashMap<>(segment.metadata().toMap());
        Object index = metadata.get(METADATA_SEGMENT_INDEX);
        Integer position = null;
        if (index != null) {
            try {
                position = Integer.valueOf(index.toString());
            } catch (NumberFormatException e) {
                // Not a position, just a key that happens to share the name.
            }
        }
        return new Chunk(position, segment == null ? "" : segment.text(), withScore ? match.score() : null, metadata);
    }

    private DocumentSummary summarize(DocumentState state, IngestionSource source, Map<String, String> fileNames) {
        String id = state.documentId();
        if (source.isUpload()) {
            // A file whose row outlived the file (deleted, then not yet reconciled)
            // has no name left to show; its id still identifies it.
            return new DocumentSummary(id, fileNames.getOrDefault(id, id), null, state.firstIngestedAt(),
                    state.lastIngestedAt(), state.missedRuns(), state.tombstoned());
        }
        return new DocumentSummary(id, id, id, state.firstIngestedAt(), state.lastIngestedAt(), state.missedRuns(),
                state.tombstoned());
    }

    private Map<String, String> fileNames(String sourceKey) {
        Map<String, String> names = new HashMap<>();
        try {
            fileStore.list(sourceKey).forEach(file -> names.put(file.fileId(), file.fileName()));
        } catch (RuntimeException e) {
            // Names are a convenience; the ids still list every document.
            LOGGER.warnf(e, "Could not read the file names of source %s", LogSanitizer.sanitize(sourceKey));
        }
        return names;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
