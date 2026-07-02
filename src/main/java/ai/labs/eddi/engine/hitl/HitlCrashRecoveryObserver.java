/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Startup observer that cleans up stale HITL conversations left in a paused
 * state from a prior crash. Without this, a server restart while conversations
 * are in AWAITING_HUMAN / AWAITING_APPROVAL leaves them stuck forever.
 *
 * <p>
 * Behavior:
 * <ul>
 * <li>On startup, finds all conversations in AWAITING_HUMAN /
 * AWAITING_APPROVAL.</li>
 * <li>If a conversation has been paused longer than the configured stale
 * threshold (default: 24 hours), transitions it to ERROR with a log entry.</li>
 * <li>Conversations within the threshold are left alone (they may have a
 * legitimate timeout schedule pending).</li>
 * </ul>
 *
 * <p>
 * Disable via {@code eddi.hitl.crash-recovery.enabled=false}.
 *
 * @author ginccc
 * @since 6.0.2
 */
@ApplicationScoped
public class HitlCrashRecoveryObserver {

    private static final Logger LOGGER = Logger.getLogger("ai.labs.eddi.HITL_RECOVERY");

    private final IConversationMemoryStore conversationMemoryStore;
    private final IGroupConversationStore groupConversationStore;
    private final boolean enabled;
    private final Duration staleThreshold;

    @Inject
    public HitlCrashRecoveryObserver(
            IConversationMemoryStore conversationMemoryStore,
            IGroupConversationStore groupConversationStore,
            @ConfigProperty(name = "eddi.hitl.crash-recovery.enabled",
                            defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.hitl.crash-recovery.stale-threshold",
                            defaultValue = "PT24H") String staleThreshold) {
        this.conversationMemoryStore = conversationMemoryStore;
        this.groupConversationStore = groupConversationStore;
        this.enabled = enabled;
        this.staleThreshold = Duration.parse(staleThreshold);
    }

    void onStartup(@Observes StartupEvent event) {
        if (!enabled) {
            LOGGER.info("HITL crash recovery disabled via config.");
            return;
        }

        LOGGER.infof("HITL crash recovery running (stale threshold: %s)...", staleThreshold);
        int recoveredRegular = recoverRegularConversations();
        int recoveredGroup = recoverGroupConversations();

        if (recoveredRegular > 0 || recoveredGroup > 0) {
            LOGGER.warnf("HITL crash recovery: transitioned %d regular + %d group stale conversations to ERROR.",
                    recoveredRegular, recoveredGroup);
        } else {
            LOGGER.info("HITL crash recovery: no stale conversations found.");
        }
    }

    private int recoverRegularConversations() {
        try {
            List<String> staleIds = conversationMemoryStore
                    .findConversationIdsByState(ConversationState.AWAITING_HUMAN);
            int count = 0;
            Instant cutoff = Instant.now().minus(staleThreshold);

            for (String conversationId : staleIds) {
                try {
                    var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
                    if (snapshot == null)
                        continue;

                    // Check if paused before the cutoff (stale)
                    Instant pausedAt = snapshot.getHitlPausedAt();
                    if (pausedAt != null && pausedAt.isBefore(cutoff)) {
                        conversationMemoryStore.setConversationState(conversationId, ConversationState.ERROR);
                        LOGGER.warnf("Recovered stale AWAITING_HUMAN conversation: %s (paused at %s)",
                                conversationId, pausedAt);
                        count++;
                    }
                } catch (Exception e) {
                    LOGGER.warnf("Failed to recover conversation %s: %s", conversationId, e.getMessage());
                }
            }
            return count;
        } catch (Exception e) {
            LOGGER.warnf("Error during regular conversation recovery: %s", e.getMessage());
            return 0;
        }
    }

    private int recoverGroupConversations() {
        try {
            List<GroupConversation> staleGcs = groupConversationStore.findByState(GroupConversationState.AWAITING_APPROVAL);
            int count = 0;
            Instant cutoff = Instant.now().minus(staleThreshold);

            for (GroupConversation gc : staleGcs) {
                Instant pausedAt = gc.getPausedAt();
                if (pausedAt != null && pausedAt.isBefore(cutoff)) {
                    gc.setState(GroupConversationState.FAILED);
                    gc.setLastModified(Instant.now());
                    try {
                        groupConversationStore.update(gc);
                        LOGGER.warnf("Recovered stale AWAITING_APPROVAL group conversation: %s (paused at %s)",
                                gc.getId(), pausedAt);
                        count++;
                    } catch (Exception e) {
                        LOGGER.warnf("Failed to recover group conversation %s: %s", gc.getId(), e.getMessage());
                    }
                }
            }
            return count;
        } catch (Exception e) {
            LOGGER.warnf("Error during group conversation recovery: %s", e.getMessage());
            return 0;
        }
    }
}
