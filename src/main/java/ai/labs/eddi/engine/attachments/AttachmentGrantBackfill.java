/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Copies the attachment grants kept in the legacy shape (on the blob) into
 * {@code attachment_grants}, on every start.
 * <p>
 * <b>Why it is safe to run while the node serves traffic.</b> For this release
 * {@link IAttachmentStore#grantAccess} writes both shapes and every read takes
 * the union, so a grant the backfill has not reached yet still authorizes. The
 * backfill therefore runs on a virtual thread and never delays startup.
 * <p>
 * <b>Every start, not once.</b> During a rolling upgrade a node of the previous
 * release still writes grants in the legacy shape only — behind the cursor, or
 * after a pass completed. A pass that ran once would never copy them. So each
 * start runs a catch-up pass; it is idempotent, and after the first one it
 * finds every grant already present.
 * <p>
 * <b>Batched, bounded, resumable.</b> It pages
 * {@link IAttachmentGrantMigration#readLegacyGrants} {@value #BATCH_SIZE} blobs
 * at a time, at most {@value #MAX_BATCHES} pages per start, and keeps its
 * cursor in the store ({@link IAttachmentGrantMigration#readBackfillCursor}) on
 * both databases, so a pass larger than one start's bound continues on the
 * next. Inserts are idempotent, so several nodes may run it at once.
 * <p>
 * <b>Grantees that no longer exist are skipped</b> — copying one would
 * re-create exactly the grants this release prunes — and a copied grant whose
 * grantee disappeared meanwhile is taken back. A copy is also refused for a
 * blob deleted meanwhile. A lookup that fails (rather than answering "absent")
 * skips that grant, is counted, and keeps the pass from being marked complete.
 * <p>
 * <b>Before copying</b> it waits for the grant indexes (the unique one stops
 * concurrent nodes from writing duplicates) and removes grant rows whose blob
 * is gone — rows a node of the previous release leaves behind when it deletes a
 * blob.
 * <p>
 * <b>The completion marker</b> ({@value #MIGRATION_KEY}) records that a whole
 * pass copied every legacy grant without a failed lookup. It switches nothing
 * off and is no proof for later: an old node can write a legacy-only grant
 * after it. <b>The release that drops the legacy read must therefore keep this
 * catch-up pass and run it once more, under a new marker key, after the last
 * node of the previous release is gone, before it stops reading the legacy
 * shape.</b>
 *
 * @since 6.8.0
 */
@ApplicationScoped
public class AttachmentGrantBackfill {

    private static final Logger LOGGER = Logger.getLogger(AttachmentGrantBackfill.class);

    static final String MIGRATION_KEY = "attachment-grants-backfill-complete";
    static final int BATCH_SIZE = 500;
    static final int MAX_BATCHES = 2_000;

    /** Whether a grantee still exists; throws when it cannot tell. */
    @FunctionalInterface
    public interface GranteeCheck {
        boolean exists(String conversationId) throws Exception;
    }

    /** What one pass did. */
    public record Result(long blobsRead, long grantsCopied, long grantsAlreadyPresent, long granteesSkipped, long lookupsFailed,
            long danglingRowsRemoved, boolean complete) {
    }

    private final IAttachmentGrantMigration migration;
    private final IMigrationLogStore migrationLogStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IGroupConversationStore groupConversationStore;
    private final AtomicBoolean running = new AtomicBoolean();

    @Inject
    public AttachmentGrantBackfill(IAttachmentGrantMigration migration, IMigrationLogStore migrationLogStore,
            IConversationMemoryStore conversationMemoryStore, IGroupConversationStore groupConversationStore) {
        this.migration = migration;
        this.migrationLogStore = migrationLogStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.groupConversationStore = groupConversationStore;
    }

    void onStart(@Observes StartupEvent event) {
        Thread.ofVirtual().name("attachment-grant-backfill").start(this::runOnce);
    }

    /** One pass with the production bounds; never throws. */
    void runOnce() {
        try {
            run(this::granteeExists, BATCH_SIZE, MAX_BATCHES);
        } catch (RuntimeException e) {
            LOGGER.warnf("The attachment grant backfill stopped; the next start resumes it. Grants still authorize meanwhile: %s",
                    sanitize(e.getMessage()));
        }
    }

    /**
     * One pass: ensures the grant indexes, removes grant rows whose blob is gone,
     * then copies legacy grants, at most {@code maxBatches} pages of
     * {@code batchSize} blobs, and writes the completion marker when the last page
     * was reached and no lookup failed.
     *
     * @return what was done, or {@code null} when a pass is already running here
     */
    public Result run(GranteeCheck granteeCheck, int batchSize, int maxBatches) {
        if (!running.compareAndSet(false, true)) {
            return null;
        }
        long read = 0;
        long copied = 0;
        long present = 0;
        long skipped = 0;
        long failed = 0;
        long dangling = 0;
        boolean complete = false;
        try {
            try {
                migration.ensureGrantIndexes();
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not ensure the attachment grant indexes; copying anyway: %s", sanitize(e.getMessage()));
            }
            try {
                dangling = migration.pruneGrantsWithoutBlob();
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not remove attachment grant rows whose blob is gone; the next start retries: %s", sanitize(e.getMessage()));
            }
            String cursor = migration.readBackfillCursor();
            for (int batch = 0; batch < maxBatches; batch++) {
                IAttachmentGrantMigration.Page page = migration.readLegacyGrants(cursor, batchSize);
                // Existence is cached for one page only, so a pass of many pages does
                // not act on an answer from long ago.
                Map<String, Boolean> known = new HashMap<>();
                // Grants already copied need no lookup: after the first pass that is
                // nearly all of them, and every start runs a pass.
                Map<String, Set<String>> existing = page.blobs().isEmpty()
                        ? Map.of()
                        : migration.existingGrants(page.blobs().stream().map(IAttachmentGrantMigration.LegacyGrants::storageRef).toList());
                for (IAttachmentGrantMigration.LegacyGrants blob : page.blobs()) {
                    read++;
                    Set<String> alreadyCopied = existing.getOrDefault(blob.storageRef(), Set.of());
                    for (String grantee : blob.conversationIds()) {
                        if (grantee == null || grantee.isBlank()) {
                            continue;
                        }
                        if (alreadyCopied.contains(grantee)) {
                            present++;
                            continue;
                        }
                        Boolean exists = known.get(grantee);
                        if (exists == null) {
                            try {
                                exists = granteeCheck.exists(grantee);
                            } catch (Exception e) {
                                failed++;
                                LOGGER.debugf("Could not tell whether conversation '%s' exists; its grant on %s is copied on a later start: %s",
                                        sanitize(grantee), blob.storageRef(), sanitize(e.getMessage()));
                                continue;
                            }
                            known.put(grantee, exists);
                        }
                        if (!exists) {
                            skipped++;
                        } else if (migration.copyGrant(blob.storageRef(), grantee)) {
                            copied++;
                            takeBackIfGone(granteeCheck, blob.storageRef(), grantee);
                        } else {
                            present++;
                        }
                    }
                }
                cursor = page.nextCursor();
                migration.writeBackfillCursor(cursor);
                if (cursor == null) {
                    complete = failed == 0;
                    break;
                }
            }
            // Once: every start runs a pass, and the MongoDB log has no unique key.
            if (complete && migrationLogStore.readMigrationLog(MIGRATION_KEY) == null) {
                migrationLogStore.createMigrationLog(new MigrationLog(MIGRATION_KEY));
            }
            LOGGER.infof("Attachment grant backfill: read %d blobs with legacy grants, copied %d grants, %d already present, skipped %d"
                    + " grants of conversations that no longer exist, %d lookups failed, removed %d grant rows whose blob is gone%s",
                    read, copied, present, skipped, failed, dangling,
                    complete ? "; complete" : "; not complete, the next start continues");
            return new Result(read, copied, present, skipped, failed, dangling, complete);
        } catch (RuntimeException e) {
            LOGGER.warnf("The attachment grant backfill stopped after %d blobs (%d grants copied); the next start resumes it: %s", read,
                    copied, sanitize(e.getMessage()));
            return new Result(read, copied, present, skipped, failed, dangling, false);
        } finally {
            running.set(false);
        }
    }

    /**
     * A grantee erased between its check and the copy keeps no row: an erasure
     * prunes the grants a conversation holds, and a row written after that prune
     * would carry the erased id for good. Checked again after the write; a failed
     * re-check keeps the row (it authorizes nothing for a conversation that is
     * gone).
     */
    private void takeBackIfGone(GranteeCheck granteeCheck, String storageRef, String grantee) {
        try {
            if (!granteeCheck.exists(grantee)) {
                migration.removeGrant(storageRef, grantee);
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not re-check conversation '%s' after copying its grant: %s", sanitize(grantee), sanitize(e.getMessage()));
        }
    }

    /**
     * A grantee is either a conversation or a group conversation
     * ({@code grantAccess(ref, gc.getId())}). Absent from both means gone; a
     * failure on either is rethrown — "could not tell" is never "gone".
     */
    boolean granteeExists(String conversationId) throws Exception {
        // A stored conversation without a state field answers a null state; its
        // revision (unversioned included) is non-null whenever the document exists.
        if (conversationMemoryStore.getConversationState(conversationId) != null
                || conversationMemoryStore.getRevision(conversationId) != null) {
            return true;
        }
        try {
            return groupConversationStore.read(conversationId) != null;
        } catch (IResourceStore.ResourceNotFoundException e) {
            return false;
        }
    }
}
