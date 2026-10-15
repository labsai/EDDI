/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.lease;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * In-memory mode: every lease is granted at once, unfenced and never lost. The
 * per-node FIFO of the coordinator is the only ordering a single node needs, so
 * this is a strict no-op — the returned stage is already complete, which lets
 * the coordinator run the task synchronously in the submitting call exactly as
 * it always did.
 */
@ApplicationScoped
@Typed(LocalLeaseManager.class)
public class LocalLeaseManager implements IConversationLeaseManager {

    /** The single, stateless unfenced handle shape. */
    public static LeaseHandle unfenced(String key) {
        return new LeaseHandle() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public String conversationId() {
                return key.startsWith(CONVERSATION) ? key.substring(CONVERSATION.length()) : key;
            }

            @Override
            public Long fence() {
                return null;
            }

            @Override
            public boolean isLost() {
                return false;
            }

            @Override
            public void onLost(Runnable callback) {
                // never lost
            }
        };
    }

    @Override
    public CompletionStage<LeaseHandle> acquireKey(String key, Duration maxWait) {
        return CompletableFuture.completedFuture(unfenced(key));
    }

    @Override
    public Optional<LeaseHandle> tryAcquireKey(String key) {
        return Optional.of(unfenced(key));
    }

    @Override
    public void release(LeaseHandle handle) {
        // nothing held
    }

    @Override
    public Optional<LeaseInfo> peekKey(String key) {
        return Optional.empty();
    }

    @Override
    public int heldCount() {
        return 0;
    }
}
