/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Builds the GridFS attachment metadata indexes when the application starts, on
 * the MongoDB datastore only.
 * <p>
 * {@link GridFsAttachmentStore} is created on first use, so building them from
 * its constructor put the index builds - and, with the database unreachable, up
 * to one server-selection timeout per index - on whichever request first
 * touched an attachment. Here they run once at boot, each bounded by
 * {@link GridFsAttachmentStore#INDEX_TIMEOUT_SECONDS}, and a failure is logged
 * rather than stopping the application.
 */
@ApplicationScoped
public class GridFsIndexInitializer {

    private static final Logger LOGGER = Logger.getLogger(GridFsIndexInitializer.class);

    private final Instance<GridFsAttachmentStore> attachmentStore;
    private final String datastoreType;

    @Inject
    public GridFsIndexInitializer(Instance<GridFsAttachmentStore> attachmentStore,
            @ConfigProperty(name = "eddi.datastore.type", defaultValue = "mongodb") String datastoreType) {
        this.attachmentStore = attachmentStore;
        this.datastoreType = datastoreType;
    }

    void onStart(@Observes StartupEvent event) {
        if ("postgres".equals(datastoreType)) {
            return;
        }
        try {
            attachmentStore.get().ensureIndexes();
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not prepare the attachment indexes; attachment lookups fall back to a scan: %s", e.getMessage());
        }
    }
}
