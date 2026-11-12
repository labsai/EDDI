/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * How many agent imports, import previews and live syncs this node runs at
 * once.
 * <p>
 * Each one holds real memory for as long as it runs: an import reads every
 * archive entry whole (up to {@code eddi.backup.import.max-entry-bytes} each),
 * a sync keeps every document it read from the source (up to
 * {@code eddi.backup.sync.max-total-response-bytes}), and the first sync of an
 * agent buffers the whole source archive. Those caps bound one operation; with
 * no bound on how many ran side by side, a handful of concurrent requests from
 * any editor could still take the heap. Past the limit a request is refused at
 * once with {@code 429} and a message naming the property, rather than queued
 * behind the others on a request worker.
 * <p>
 * A call made from inside an admitted one (an endpoint delegating to another on
 * the same bean, a batch sync running its members) runs on the slot it already
 * holds: the count is per outermost request, so nesting can neither deadlock
 * nor be refused.
 */
@ApplicationScoped
public class BackupConcurrencyGate {

    static final String MAX_CONCURRENT_PROPERTY = "eddi.backup.max-concurrent-operations";
    static final int DEFAULT_MAX_CONCURRENT = 4;

    /** Seconds a refused caller is told to wait before trying again. */
    static final String RETRY_AFTER_SECONDS = "10";

    /** Work run inside the gate. */
    @FunctionalInterface
    public interface Operation<T> {
        T run() throws Exception;
    }

    private final int maxConcurrent;
    private final Semaphore slots;
    /** How deep the current thread is inside an admitted operation. */
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);

    @Inject
    public BackupConcurrencyGate(@ConfigProperty(name = MAX_CONCURRENT_PROPERTY, defaultValue = "4") int maxConcurrent) {
        this.maxConcurrent = maxConcurrent > 0 ? maxConcurrent : DEFAULT_MAX_CONCURRENT;
        this.slots = new Semaphore(this.maxConcurrent);
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    /** Slots free right now; for tests and diagnostics. */
    int available() {
        return slots.availablePermits();
    }

    /**
     * Runs {@code operation} in a free slot, or inside the slot this thread already
     * holds.
     *
     * @throws WebApplicationException
     *             {@code 429} when every slot is taken
     */
    public <T> T run(Operation<T> operation) throws Exception {
        int[] level = depth.get();
        if (level[0] > 0) {
            level[0]++;
            try {
                return operation.run();
            } finally {
                level[0]--;
            }
        }
        if (!slots.tryAcquire()) {
            depth.remove();
            throw busy();
        }
        level[0] = 1;
        try {
            return operation.run();
        } finally {
            depth.remove();
            slots.release();
        }
    }

    private WebApplicationException busy() {
        String message = "This instance is already running " + maxConcurrent + " agent imports, previews or syncs ("
                + MAX_CONCURRENT_PROPERTY + "). Retry when one has finished.";
        return new WebApplicationException(message, Response.status(Response.Status.TOO_MANY_REQUESTS)
                .header("Retry-After", RETRY_AFTER_SECONDS)
                .entity(Map.of("error", message))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
