/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.connector.SourceMetadataKeys;
import ai.labs.eddi.modules.ingestion.drive.DriveRetrievalScope;
import ai.labs.eddi.utils.LogSanitizer;
import dev.langchain4j.data.document.Metadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies the current folder scope of a knowledge base's Google Drive sources
 * at retrieval time.
 *
 * <p>
 * Taking a folder out of a source's scope, or excluding one, removes its
 * vectors at the next <em>complete</em> listing. A listing that stops early —
 * throttled, over its time budget — removes nothing, and a source that never
 * completes one would keep answering from the folder for good. So retrieval
 * itself drops every chunk whose recorded folder chain lies outside the
 * source's current scope, whatever the vector store holds.
 *
 * <p>
 * A post-filter, so it works on every store: the search asks for more matches
 * than it returns ({@link Scopes#fetchSize}) and keeps the first that pass.
 * Chunks of other sources, and chunks without a chain, are never touched.
 */
@ApplicationScoped
public class FolderScopeRetrievalFilter {

    private static final Logger LOGGER = Logger.getLogger(FolderScopeRetrievalFilter.class);

    /** How many more matches than wanted a scoped search asks for, at most. */
    static final int MAX_EXTRA_RESULTS = 100;

    private final IIngestionStateStore stateStore;

    @Inject
    public FolderScopeRetrievalFilter(IIngestionStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /** The scopes that restrict anything, keyed by the source's state key. */
    public Scopes forKnowledgeBase(String ragConfigId, RagConfiguration knowledgeBase) {
        Map<String, DriveRetrievalScope> scopes = new LinkedHashMap<>();
        if (knowledgeBase == null || knowledgeBase.getSources() == null) {
            return new Scopes(scopes);
        }
        for (IngestionSource source : knowledgeBase.getSources()) {
            if (source == null || !IngestionSource.TYPE_GOOGLE_DRIVE.equals(source.getType())
                    || source.getGoogleDrive() == null) {
                continue;
            }
            String sourceKey = IngestionPipeline.stateKey(ragConfigId, source);
            String recorded = null;
            try {
                recorded = stateStore.resolvedScope(sourceKey).orElse(null);
            } catch (RuntimeException e) {
                // Without the resolved paths only the scope's ids apply; better than
                // failing the turn.
                LOGGER.warnf(e, "Could not read the resolved folder scope of source '%s'",
                        LogSanitizer.sanitize(source.getName()));
            }
            DriveRetrievalScope scope = DriveRetrievalScope.of(source.getGoogleDrive(), recorded);
            if (scope.restricts()) {
                scopes.put(sourceKey, scope);
            }
        }
        return new Scopes(scopes);
    }

    /** The folder scopes of one knowledge base. */
    public record Scopes(Map<String, DriveRetrievalScope> bySourceKey) {

        public Scopes {
            bySourceKey = Map.copyOf(bySourceKey);
        }

        /** Whether any source restricts anything. */
        public boolean active() {
            return !bySourceKey.isEmpty();
        }

        /**
         * How many matches to ask the store for, to return {@code wanted} after
         * filtering.
         */
        public int fetchSize(int wanted) {
            if (!active()) {
                return wanted;
            }
            return wanted + Math.min(Math.max(wanted, 1) * 3, MAX_EXTRA_RESULTS);
        }

        /** Whether a chunk with this metadata may be returned. */
        public boolean admits(Metadata metadata) {
            if (metadata == null || bySourceKey.isEmpty()) {
                return true;
            }
            DriveRetrievalScope scope = bySourceKey.get(metadata.getString(SourceMetadataKeys.SOURCE_KEY));
            return scope == null || scope.admits(metadata.getString(SourceMetadataKeys.FOLDER_IDS));
        }
    }
}
