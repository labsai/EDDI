/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.mongo;

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
     * accepted and then silently ignored. {@link RagConfiguration#validate()}
     * already knows which strategies exist but only ran at retrieval time, long
     * after the author had been told the save succeeded. Running it here rejects
     * the unimplemented value at save time and names the supported ones.
     * <p>
     * The two <em>advertised</em> aliases are normalised rather than rejected: they
     * are sitting in stored configs and in every ZIP exported while the docs still
     * listed them. Failing them outright made those knowledge bases impossible to
     * update without first hand-editing the field, and aborted the entire agent
     * import they appeared in — over a value whose actual behaviour never changed.
     * Anything else was never valid and still fails loudly.
     */
    @Override
    protected void validate(RagConfiguration content) {
        if (content == null) {
            return;
        }
        normalizeLegacyChunkStrategy(content);
        content.validate();
    }

    private void normalizeLegacyChunkStrategy(RagConfiguration content) {
        String strategy = content.getChunkStrategy();
        if (strategy == null || !LEGACY_CHUNK_STRATEGY_ALIASES.contains(strategy.trim().toLowerCase(Locale.ROOT))) {
            return;
        }
        LOGGER.warnf("Knowledge base '%s' declares chunkStrategy '%s', which the ingestion pipeline never implemented — "
                + "storing it as '%s', the behaviour it always had.", content.getName(), strategy, DEFAULT_CHUNK_STRATEGY);
        content.setChunkStrategy(DEFAULT_CHUNK_STRATEGY);
    }
}
