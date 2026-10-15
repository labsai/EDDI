/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.cluster.lease.LeaseHandle;

/**
 * A coordinator task that wants the cluster lease it runs under.
 * <p>
 * The cluster coordinator binds the lease right before it hands the task to the
 * runtime; the task copies the lease's fencing token onto the live conversation
 * memory (so every write of the turn carries it) and cancels the pipeline if
 * the lease is lost. A ThreadLocal would not do: the pipeline runs on a nested
 * future on another thread.
 * <p>
 * The in-memory coordinator never calls {@link #bindLease}, and the task runs
 * exactly as before.
 */
public interface ILeaseAwareTask extends IDiscardableTask {
    void bindLease(LeaseHandle lease);
}
