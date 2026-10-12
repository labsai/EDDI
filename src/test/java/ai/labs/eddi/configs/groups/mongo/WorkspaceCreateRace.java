/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.model.GroupWorkspace;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Drives the create race the container tests share: {@code threads} callers
 * released together into {@code readOrCreate} for a group nobody has a
 * workspace for yet, each then recording one write on whatever workspace it got
 * back (re-reading and retrying on a lost CAS, as every real caller does).
 * <p>
 * With one atomic create every caller gets the same document and every write
 * lands in it. With the old insert-then-converge scheme, a caller could get
 * back a document that was not the one later reads elect — its write then
 * landed and reported success, and vanished.
 */
final class WorkspaceCreateRace {

    record Outcome(Set<String> idsReturned, int writesCounted) {
    }

    private WorkspaceCreateRace() {
    }

    static Outcome run(GroupWorkspaceStore store, String groupId, int threads) throws Exception {
        var barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    GroupWorkspace mine = store.readOrCreate(groupId);
                    String returnedId = mine.getId();
                    GroupWorkspace current = mine;
                    for (int attempt = 0; attempt < 200; attempt++) {
                        current.getMetrics().setDiscussions(current.getMetrics().getDiscussions() + 1);
                        if (store.casRevision(current)) {
                            return returnedId;
                        }
                        current = store.find(groupId);
                        if (current == null) {
                            current = store.readOrCreate(groupId);
                        }
                    }
                    throw new IllegalStateException("write never landed");
                }));
            }
            Set<String> ids = new HashSet<>();
            for (Future<String> future : futures) {
                ids.add(future.get(60, TimeUnit.SECONDS));
            }
            GroupWorkspace finalWorkspace = store.find(groupId);
            return new Outcome(ids, finalWorkspace.getMetrics().getDiscussions());
        } finally {
            pool.shutdownNow();
        }
    }
}
