/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.org.OrgPropagation;

import java.util.concurrent.Callable;

/**
 * A queued conversation task with the org binding of the thread that enqueued
 * it (plan §6.3).
 * <p>
 * The coordinator captures at enqueue, not when the task is finally submitted.
 * Submission happens on whichever thread finished the previous turn or obtained
 * the cluster lease, which is not the requester's. So the coordinator
 * {@linkplain #submitUnder submits} under the captured binding, and the runtime
 * carries the binding it sees at submission on to the worker thread. Restoring
 * follows {@link OrgPropagation}: a task captured for one org is refused on a
 * thread bound to another.
 * <p>
 * The runtime receives the {@linkplain #original original} task. The
 * coordinators read marker interfaces ({@code IDescribedTask},
 * {@code ILeaseAwareTask}, {@code IDiscardableTask}) from the original too,
 * never from the wrapper.
 */
final class OrgCapturedTask implements Callable<Void> {

    private final Callable<Void> original;
    private final OrgPropagation.Captured binding;

    private OrgCapturedTask(Callable<Void> original) {
        this.original = original;
        this.binding = OrgPropagation.capture();
    }

    /**
     * Wraps {@code task} with the current binding; a captured task stays as it is.
     */
    static Callable<Void> capture(Callable<Void> task) {
        return task instanceof OrgCapturedTask ? task : new OrgCapturedTask(task);
    }

    /** The task as its submitter wrote it. */
    static Callable<Void> original(Callable<Void> task) {
        return task instanceof OrgCapturedTask captured ? captured.original : task;
    }

    /**
     * Runs {@code submission}, which hands the task to the runtime, under the
     * binding captured with {@code task}; a task that was never captured is
     * submitted as it is.
     */
    static void submitUnder(Callable<Void> task, Runnable submission) {
        if (task instanceof OrgCapturedTask captured) {
            captured.binding.run(submission);
        } else {
            submission.run();
        }
    }

    /**
     * Runs the original under the captured binding, for a caller that runs the
     * wrapper itself.
     */
    @Override
    public Void call() throws Exception {
        return binding.call(original);
    }

    @Override
    public String toString() {
        return original.toString();
    }
}
