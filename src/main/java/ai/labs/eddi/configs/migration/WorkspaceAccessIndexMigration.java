/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.engine.security.spaces.DescriptorAccess;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * One-shot startup migration: gives every pre-existing configuration descriptor
 * an access index, so workspace filtering can be switched on without hiding
 * everything that already exists.
 *
 * <h3>Why an explicit backfill is required, not optional</h3> Listings filter
 * on {@code accessIndex}, and neither backend can express "this field is
 * absent" — a regex predicate simply does not match a missing field on MongoDB,
 * and {@code jsonb ->> 'accessIndex'} is null on PostgreSQL. So a descriptor
 * written before this feature would match no access predicate at all and vanish
 * from every listing the moment enforcement is enabled. Stamping the
 * {@link Subjects#LEGACY} token is what keeps that from happening.
 * <p>
 * Descriptors that already carry an owner or a space are re-indexed rather than
 * skipped: the index is derived state, and rebuilding it is idempotent.
 *
 * <h3>It does not invent owners</h3> A descriptor with no {@code ownerId} keeps
 * none. Attribution cannot be reconstructed after the fact, and guessing (the
 * first admin? the deployment owner?) would produce confident, wrong answers
 * that are worse than an honest "unowned". Whether unowned resources stay
 * visible is the operator's call, via
 * {@code eddi.workspaces.legacy-visibility}.
 *
 * @since 6.3.0
 */
@ApplicationScoped
public class WorkspaceAccessIndexMigration {

    private static final Logger LOGGER = Logger.getLogger(WorkspaceAccessIndexMigration.class);
    private static final String MIGRATION_KEY = "workspace-access-index-migration-complete";

    /**
     * Descriptor types to backfill. Every configuration type that has a REST store,
     * because every one of them is listed through the same access predicate.
     */
    private static final List<String> DESCRIPTOR_TYPES = List.of(
            "ai.labs.agent", "ai.labs.workflow", "ai.labs.behavior", "ai.labs.httpcalls", "ai.labs.mcpcalls",
            "ai.labs.langchain", "ai.labs.output", "ai.labs.property", "ai.labs.parser", "ai.labs.regulardictionary",
            "ai.labs.rag", "ai.labs.snippet", "ai.labs.channel", "ai.labs.connection", "ai.labs.group");

    /**
     * Page size for the sweep. Bounded so a large deployment does not load it all.
     */
    private static final int BATCH_SIZE = 200;

    /**
     * Pages scanned per type before giving up. At {@value #BATCH_SIZE} per page
     * this is 200 000 descriptors of one type — far beyond any real deployment, and
     * a backstop against a store whose paging never advances.
     */
    private static final int MAX_PAGES = 1000;

    private final IDocumentDescriptorStore descriptorStore;
    private final IMigrationLogStore migrationLogStore;

    @Inject
    public WorkspaceAccessIndexMigration(IDocumentDescriptorStore descriptorStore, IMigrationLogStore migrationLogStore) {
        this.descriptorStore = descriptorStore;
        this.migrationLogStore = migrationLogStore;
    }

    /** Runs the backfill unless it has already completed. */
    public void runIfNeeded() {
        if (migrationLogStore.readMigrationLog(MIGRATION_KEY) != null) {
            LOGGER.debug("Workspace access-index migration already applied — skipping");
            return;
        }

        int stamped = 0;
        int scanned = 0;
        for (String type : DESCRIPTOR_TYPES) {
            for (int page = 0; page < MAX_PAGES; page++) {
                List<DocumentDescriptor> batch;
                try {
                    // Unrestricted deliberately: a migration runs below the access model, and
                    // filtering the backfill by an access index that does not exist yet would
                    // return nothing.
                    batch = descriptorStore.readDescriptors(type, "", page, BATCH_SIZE, true);
                } catch (Exception e) {
                    LOGGER.warnf("Could not read %s descriptors on page %d during access-index backfill: %s", type, page, e.getMessage());
                    break;
                }
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                scanned += batch.size();
                for (DocumentDescriptor descriptor : batch) {
                    if (stampIfNeeded(descriptor)) {
                        stamped++;
                    }
                }
                if (batch.size() < BATCH_SIZE) {
                    break;
                }
            }
        }

        LOGGER.infov("Workspace access-index backfill complete: {0} descriptor(s) scanned, {1} stamped.", scanned, stamped);
        migrationLogStore.createMigrationLog(new MigrationLog(MIGRATION_KEY));
    }

    /**
     * @return whether the descriptor was written
     */
    private boolean stampIfNeeded(DocumentDescriptor descriptor) {
        if (descriptor == null || descriptor.getResource() == null) {
            return false;
        }
        String rebuilt = DescriptorAccess.buildIndex(descriptor);
        if (rebuilt.equals(descriptor.getAccessIndex())) {
            return false;
        }
        descriptor.setAccessIndex(rebuilt);

        var resourceId = RestUtilities.extractResourceId(descriptor.getResource());
        if (resourceId == null || resourceId.getId() == null || resourceId.getVersion() == null) {
            return false;
        }
        try {
            // setDescriptor writes in place. A backfill must not create a version: doing so
            // would make every pre-existing resource look as though it had just been
            // edited.
            descriptorStore.setDescriptor(resourceId.getId(), resourceId.getVersion(), descriptor);
            return true;
        } catch (Exception e) {
            LOGGER.warnf("Could not stamp access index on descriptor %s v%s: %s", resourceId.getId(), resourceId.getVersion(), e.getMessage());
            return false;
        }
    }
}
