/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Fails startup when {@code eddi.attachments.blob.backend} cannot run on the
 * configured database, instead of on the first large upload. Resolving the
 * {@link BlobBackendSelector} is the check: its producer throws, naming the
 * reason.
 */
@ApplicationScoped
public class BlobBackendStartupCheck {

    private static final Logger LOGGER = Logger.getLogger(BlobBackendStartupCheck.class);

    private final Instance<BlobBackendSelector> selector;

    @Inject
    public BlobBackendStartupCheck(Instance<BlobBackendSelector> selector) {
        this.selector = selector;
    }

    void onStart(@Observes StartupEvent event) {
        check();
    }

    /**
     * @throws IllegalStateException
     *             if the configured backend cannot run here
     */
    void check() {
        // Unwraps a creation failure to the producer's own message.
        try {
            LOGGER.debugf("Attachment blob backend for large files: %s", selector.get().writeBackendName());
        } catch (RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && !(cause instanceof IllegalStateException)) {
                cause = cause.getCause();
            }
            if (cause instanceof IllegalStateException refusal) {
                throw refusal;
            }
            throw e;
        }
    }
}
