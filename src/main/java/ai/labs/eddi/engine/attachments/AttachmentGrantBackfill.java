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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Copies the attachment grants kept in the legacy shape (on the blob) into
 * {@code attachment_grants}, once, at startup.
 * <p>
 * <b>Why it is safe to run while the node serves traffic.</b> For this release
 * {@link IAttachmentStore#grantAccess} writes both shapes and every read takes
 * the union, so a grant the backfill has not reached yet still authorizes. The
 * backfill therefore runs on a virtual thread and never delays startup.
 * <p>
 * <b>Batched, bounded, resumable.</b> It pages
 * {@link IAttachmentGrantMigration#readLegacyGrants} {@value #BATCH_SIZE} blobs
 * at a time, at most {@value #MAX_BATCHES} pages per start. Inserts are
 * idempotent, so several nodes may run it at once and a run cut short is simply
 * repeated. Where the migration log can keep entries (MongoDB) the cursor is
 * kept between starts; elsewhere the next start pages from the beginning again.
 * <p>
 * <b>Dangling grantees are skipped.</b> A grantee that no longer exists as a
 * conversation or as a group conversation is not copied — copying it would
 * re-create exactly the grants this release prunes. An existence check that
 * fails (rather than answering "absent") ends the pass without the completion
 * marker, so the next start retries it.
 * <p>
 * <b>The completion marker</b> ({@value #MIGRATION_KEY}) only records that
 * every legacy grant was copied. It switches nothing off: the legacy write and
 * read stay for this release (an old node in a rolling upgrade may still write
 * the legacy shape only), and a later release removes them.
 *
 * @since 6.8.0
 */
@ApplicationScoped
public class AttachmentGrantBackfill {

    private static final Logger LOGGER = Logger.getLogger(AttachmentGrantBackfill.class);

    static final String MIGRATION_KEY = "attachment-grants-backfill-complete";
    static final String CURSOR_KEY = "attachment-grants-backfill-cursor";
    static final int BATCH_SIZE = 500;
    static final int MAX_BATCHES = 2_000;

    /** Whether a grantee still exists; throws when it cannot tell. */
    @FunctionalInterface
    public interface GranteeCheck {
        boolean exists(String conversationId) throws Exception;
    }

    /** What one pass did. */
    public record Result(long blobsRead, long grantsCopied, long grantsAlreadyPresent, long granteesSkipped, boolean complete) {
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
            if (migrationLogStore.readMigrationLog(MIGRATION_KEY) != null) {
                return;
            }
            run(this::granteeExists, BATCH_SIZE, MAX_BATCHES);
        } catch (RuntimeException e) {
            LOGGER.warnf("The attachment grant backfill stopped; the next start resumes it. Grants still authorize meanwhile: %s",
                    sanitize(e.getMessage()));
        }
    }

    /**
     * Copies legacy grants, at most {@code maxBatches} pages of {@code batchSize}
     * blobs, and writes the completion marker when the last page was reached.
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
        boolean complete = false;
        Map<String, Boolean> known = new HashMap<>();
        try {
            String cursor = readCursor();
            for (int batch = 0; batch < maxBatches; batch++) {
                IAttachmentGrantMigration.Page page = migration.readLegacyGrants(cursor, batchSize);
                for (IAttachmentGrantMigration.LegacyGrants blob : page.blobs()) {
                    read++;
                    for (String grantee : blob.conversationIds()) {
                        if (grantee == null || grantee.isBlank()) {
                            continue;
                        }
                        Boolean exists = known.get(grantee);
                        if (exists == null) {
                            exists = granteeCheck.exists(grantee);
                            if (known.size() < 10_000) {
                                known.put(grantee, exists);
                            }
                        }
                        if (!exists) {
                            skipped++;
                        } else if (migration.copyGrant(blob.storageRef(), grantee)) {
                            copied++;
                        } else {
                            present++;
                        }
                    }
                }
                cursor = page.nextCursor();
                writeCursor(cursor);
                if (cursor == null) {
                    complete = true;
                    break;
                }
            }
            if (complete) {
                migrationLogStore.createMigrationLog(new MigrationLog(MIGRATION_KEY));
            }
            LOGGER.infof("Attachment grant backfill: read %d blobs with legacy grants, copied %d grants, %d already present, skipped %d"
                    + " grants of conversations that no longer exist%s", read, copied, present, skipped,
                    complete ? "; complete" : "; not finished, the next start continues");
            return new Result(read, copied, present, skipped, complete);
        } catch (Exception e) {
            LOGGER.warnf("The attachment grant backfill stopped after %d blobs (%d grants copied); the next start resumes it: %s", read,
                    copied, sanitize(e.getMessage()));
            return new Result(read, copied, present, skipped, false);
        } finally {
            running.set(false);
        }
    }

    /**
     * A grantee is either a conversation or a group conversation
     * ({@code grantAccess(ref, gc.getId())}). Absent from both means gone; a
     * failure on either is rethrown — "could not tell" is never "gone".
     */
    boolean granteeExists(String conversationId) throws Exception {
        if (conversationMemoryStore.getConversationState(conversationId) != null) {
            return true;
        }
        try {
            return groupConversationStore.read(conversationId) != null;
        } catch (IResourceStore.ResourceNotFoundException e) {
            return false;
        }
    }

    private String readCursor() {
        List<String> entries = migrationLogStore.readMigrationEntries(CURSOR_KEY);
        return entries == null || entries.isEmpty() ? null : entries.getFirst();
    }

    private void writeCursor(String cursor) {
        migrationLogStore.writeMigrationEntries(CURSOR_KEY, cursor == null ? List.of() : List.of(cursor));
    }
}
