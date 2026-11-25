/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.index;

import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.engine.documents.DocumentSettings;
import ai.labs.eddi.utils.LogSanitizer;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Validates {@link DocumentSettings#INDEX_DEFAULT_RAG} when the deployment
 * starts. A property cannot be refused at save time like a designer's
 * {@code documents.index.rag}, so a deployment whose default names a missing or
 * ineligible RAG configuration fails to start, with the reason — rather than
 * writing users' documents somewhere agents' retrieval may read later. The
 * write path re-checks before every write.
 */
@ApplicationScoped
public class DocumentIndexDefaultCheck {

    private static final Logger LOGGER = Logger.getLogger(DocumentIndexDefaultCheck.class);

    private final DocumentSettings settings;
    private final IRagStore ragStore;

    @Inject
    public DocumentIndexDefaultCheck(DocumentSettings settings, IRagStore ragStore) {
        this.settings = settings;
        this.ragStore = ragStore;
    }

    void onStart(@Observes StartupEvent event) {
        check();
    }

    /**
     * @throws IllegalStateException
     *             when the default is set and names no eligible document index
     */
    void check() {
        Optional<String> reference = settings.indexDefaultRag();
        if (reference.isEmpty()) {
            return;
        }
        String reason = DocumentIndexEligibility.referenceIneligibility(reference.get(), DocumentIndexLookup.of(ragStore));
        if (reason != null) {
            throw new IllegalStateException(DocumentSettings.INDEX_DEFAULT_RAG + " must name a document index: " + reason);
        }
        LOGGER.infof("The default document index is %s", LogSanitizer.sanitize(reference.get()));
    }
}
