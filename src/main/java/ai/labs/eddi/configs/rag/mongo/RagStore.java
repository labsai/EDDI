/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.mongo;

import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Set;

/**
 * MongoDB-backed store for RAG (Knowledge Base) configurations.
 */
@ApplicationScoped
public class RagStore extends AbstractResourceStore<RagConfiguration> implements IRagStore {

    private static final Logger LOGGER = Logger.getLogger(RagStore.class);

    /**
     * Chunk strategies that were documented and accepted for years but never had a
     * reader — ingestion always built a recursive splitter, so these values already
     * <em>meant</em> {@code "recursive"}.
     */
    private static final Set<String> LEGACY_CHUNK_STRATEGY_ALIASES = Set.of("paragraph", "sentence");

    private static final String DEFAULT_CHUNK_STRATEGY = "recursive";

    @Inject
    public RagStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "rags", documentBuilder, RagConfiguration.class);
    }

    /**
     * Finding I3: {@code chunkStrategy} has no reader — ingestion always builds a
     * {@code DocumentSplitters.recursive} splitter — so any other value was
     * accepted and then silently ignored. The two <em>advertised</em> aliases are
     * normalised here to the behaviour they always had, which is a data fix that is
     * safe on every write path, including the ones that merely replay a document
     * that already exists.
     * <p>
     * <strong>Deliberately does not reject.</strong> This hook runs on every
     * {@code create} and {@code update}, and three of those callers are replaying a
     * stored document rather than accepting author input:
     * <ul>
     * <li>{@code RestRagStore.duplicateRag} — documented and tested to tolerate an
     * unsupported stored strategy, because a store that serves a document through
     * {@code readRag} must not refuse to copy it;</li>
     * <li>{@code RestImportService.createNewRags} — writes through
     * {@code createResourceDirect}, i.e. straight to this store, and only catches
     * {@code ResourceStoreException}, so an {@code IllegalArgumentException} here
     * escapes and rolls back the <em>entire</em> agent import;</li>
     * <li>{@code UpgradeExecutor} — likewise a replay of existing documents.</li>
     * </ul>
     * Rejecting here silently defeated all three: the REST layer's duplicate
     * leniency was contradicted one layer down, and its test only passed because it
     * mocks {@code IRagStore} and so never reached this method.
     * <p>
     * The author-facing rejection lives at the write boundary instead —
     * {@code RestRagStore.prepareForWrite}, which is the only layer that can tell
     * "an author typed this" from "this document already exists" — and it answers
     * with an actionable 400. Nothing is lost by being lenient here: the value is
     * inert (documents are chunked recursively regardless) and retrieval degrades
     * gracefully via {@link RagConfiguration#findUnsupportedSettings()}. An
     * unsupported value is also left verbatim rather than rewritten, so a duplicate
     * is a faithful copy of its original.
     */
    @Override
    protected void validate(RagConfiguration content) {
        if (content == null) {
            return;
        }
        normalizeLegacyChunkStrategy(content);
    }

    private void normalizeLegacyChunkStrategy(RagConfiguration content) {
        String strategy = content.getChunkStrategy();
        if (strategy == null || !LEGACY_CHUNK_STRATEGY_ALIASES.contains(strategy.trim().toLowerCase(Locale.ROOT))) {
            return;
        }
        LOGGER.warnf("Knowledge base '%s' declares chunkStrategy '%s', which the ingestion pipeline never implemented — "
                + "storing it as '%s', the behaviour it always had.", LogSanitizer.sanitize(content.getName()),
                LogSanitizer.sanitize(strategy), DEFAULT_CHUNK_STRATEGY);
        content.setChunkStrategy(DEFAULT_CHUNK_STRATEGY);
    }
}
