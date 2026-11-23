/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Carries the org binding across a hand-off to another thread (plan §6.3).
 * <p>
 * A {@link ScopedValue} is not inherited by work that runs later on another
 * thread, and {@code ThreadContextProvider} cannot carry it, so every executor
 * submission, {@code CompletableFuture.*Async}, Mutiny {@code emitOn} /
 * {@code runSubscriptionOn}, coordinator task and streaming callback wraps its
 * work here. A wrapper captures the binding when it is <em>created</em> (for
 * {@link #wrap(Executor)}: when a task is <em>submitted</em>) and restores that
 * binding when it runs, including "unbound".
 * <p>
 * A thread that is already bound to an org (an inline executor, a future
 * completed inside a request) is never moved out of it:
 * <ul>
 * <li>work captured for the same org runs directly, under the thread's
 * epoch;</li>
 * <li>work captured for a <em>different</em> org, or for the platform plane,
 * throws {@link OrgBindingException};</li>
 * <li>work captured unbound runs under the thread's org, so it cannot shed the
 * binding and then bind another org.</li>
 * </ul>
 * On a thread bound to the platform plane:
 * <ul>
 * <li>platform work runs directly, under the thread's epoch;</li>
 * <li>org work binds its org (platform to org is allowed);</li>
 * <li>work captured unbound runs <em>unbound</em>. Inheriting the platform
 * plane would give work that was never meant to cross orgs that privilege, so
 * it sheds it and fails closed instead.</li>
 * </ul>
 * A refused hand-off is logged at ERROR before it throws, because an inline
 * {@code CompletableFuture} stage may swallow the exception.
 * <p>
 * The supplier variant is named {@link #wrapSupplier} because a lambda fits
 * {@code Callable} and {@code Supplier} alike, and the two overloads would be
 * ambiguous.
 */
public final class OrgPropagation {

    private static final Logger LOGGER = Logger.getLogger(OrgPropagation.class);

    private OrgPropagation() {
    }

    public static Runnable wrap(Runnable work) {
        Objects.requireNonNull(work, "work");
        OrgContext.Binding captured = OrgContext.bindingOrNull();
        return () -> {
            try {
                restore(captured, () -> {
                    work.run();
                    return null;
                });
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                // unreachable: a Runnable throws no checked exception
                throw new IllegalStateException(e);
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> work) {
        Objects.requireNonNull(work, "work");
        OrgContext.Binding captured = OrgContext.bindingOrNull();
        return () -> restore(captured, work);
    }

    public static <T> Supplier<T> wrapSupplier(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        OrgContext.Binding captured = OrgContext.bindingOrNull();
        return () -> {
            try {
                return restore(captured, work::get);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                // unreachable: a Supplier throws no checked exception
                throw new IllegalStateException(e);
            }
        };
    }

    /**
     * The current binding, to restore around work that is chosen only later. Use it
     * where the work to run is not known at capture time, such as a queued task
     * whose submission happens on another thread.
     */
    public static Captured capture() {
        return new Captured(OrgContext.bindingOrNull());
    }

    /**
     * A binding captured by {@link #capture()}; restoring it follows the same rules
     * as a wrapped task.
     */
    public static final class Captured {
        private final OrgContext.Binding binding;

        private Captured(OrgContext.Binding binding) {
            this.binding = binding;
        }

        public <T> T call(Callable<T> work) throws Exception {
            Objects.requireNonNull(work, "work");
            return restore(binding, work);
        }

        public void run(Runnable work) {
            Objects.requireNonNull(work, "work");
            try {
                restore(binding, () -> {
                    work.run();
                    return null;
                });
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                // unreachable: a Runnable throws no checked exception
                throw new IllegalStateException(e);
            }
        }
    }

    /** An executor that wraps every task with the binding current at submission. */
    public static Executor wrap(Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return command -> executor.execute(wrap(command));
    }

    private static <T> T restore(OrgContext.Binding captured, Callable<T> work) throws Exception {
        OrgContext.Binding current = OrgContext.bindingOrNull();
        if (current == captured) {
            return work.call();
        }
        if (current instanceof OrgContext.Org bound) {
            if (captured instanceof OrgContext.Org wanted) {
                if (!bound.org().equals(wanted.org())) {
                    throw refused("work captured for one org ran on a thread bound to another");
                }
                // same org: a no-op that keeps the thread's epoch, as OrgContext.callAs does
                return work.call();
            }
            if (captured instanceof OrgContext.Platform) {
                // leaving an org for the platform plane goes through OrgScope.platform only
                throw refused("platform work ran inline on a thread bound to an org");
            }
            // captured unbound: keep the thread's org rather than dropping it, so the work
            // cannot shed the binding and then bind another org
            return work.call();
        }
        if (current instanceof OrgContext.Platform && captured instanceof OrgContext.Platform) {
            // platform inside platform: a no-op, like a nested OrgScope.platform
            return work.call();
        }
        // unbound thread: restore exactly; platform thread: org work binds its org,
        // unbound work sheds the platform plane (null binding) and fails closed
        return OrgContext.callUnder(captured, work);
    }

    private static OrgBindingException refused(String reason) {
        OrgBindingException e = new OrgBindingException(reason);
        LOGGER.error(e.getMessage(), e);
        return e;
    }
}
