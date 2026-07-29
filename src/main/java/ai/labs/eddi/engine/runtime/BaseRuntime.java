/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author ginccc
 */
@ApplicationScoped
public class BaseRuntime implements IRuntime {
    private final String projectVersion;

    @Inject
    ManagedExecutor executorService;
    private final String projectName;
    private final ScheduledExecutorService scheduledExecutorService;

    /**
     * Executor for NESTED submissions — work submitted from a thread that is itself
     * running a callable submitted through {@link #submitCallable}.
     * <p>
     * A conversation turn consumes TWO threads: the coordinator's callable (the
     * "outer" task) submits the pipeline execution (the "inner" task) and then
     * BLOCKS on its {@link Future} for up to the agent timeout. When both come from
     * the same bounded {@link ManagedExecutor}, concurrency collapses at HALF the
     * pool size: every thread becomes a waiter, no inner task can ever be
     * scheduled, and every turn fails at the watchdog. That is a hard cliff, not a
     * slow degradation.
     * <p>
     * Routing the nested (inner) task to a virtual-thread executor removes the
     * cliff: the inner task can always be scheduled, so waiters always make
     * progress. Virtual threads are already the established pattern for
     * conversation-adjacent work in this codebase (SchedulePollerService,
     * GroupConversationService, the Slack handlers), and the pipeline has no
     * dependency on the ManagedExecutor's context propagation — there is not a
     * single {@code @RequestScoped} bean in the engine, and the Slack/group entry
     * points already drive full turns with no request context active.
     * <p>
     * Only the work BODY is marked as nested — completion callbacks run with the
     * marker cleared, so the coordinator's {@code submitNext} still schedules the
     * next turn's outer task on the ManagedExecutor exactly as before.
     */
    private final ExecutorService nestedExecutorService;

    /**
     * Set on a thread for the duration of a submitted callable's BODY (not its
     * callbacks). Read on the submitting thread to decide whether a submission is
     * nested. Always cleared in a {@code finally} so pooled platform threads never
     * carry the marker into unrelated work.
     */
    private static final ThreadLocal<Boolean> EXECUTING_SUBMITTED_CALLABLE = new ThreadLocal<>();

    private boolean isInit = false;

    private final Logger log = Logger.getLogger(BaseRuntime.class);

    @Inject
    public BaseRuntime(@ConfigProperty(name = "systemRuntime.projectName") String projectName,
            @ConfigProperty(name = "systemRuntime.projectVersion") String projectVersion) {

        this.projectName = projectName;
        this.projectVersion = projectVersion;
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "eddi-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.nestedExecutorService = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("eddi-nested-", 0).factory());

        init();
    }

    @PreDestroy
    void shutdown() {
        scheduledExecutorService.shutdownNow();
        // shutdown() (not shutdownNow()) so in-flight pipeline executions are given
        // the chance to finish — the graceful-shutdown drain runs before this.
        nestedExecutorService.shutdown();
    }

    public void init() {
        if (!isInit) {
            if (projectName == null || projectName.isEmpty()) {
                log.error("ProjectName should be defined in systemRuntime.properties as 'systemRuntime.projectName'");
            } else {
                initProjectName(projectName);
            }

            logVersion();
            isInit = true;
        } else {
            log.warn("SystemRuntime has already been initialized!");
        }
    }

    private void initProjectName(String projectName) {
        System.setProperty("systemRuntime.projectName", lowerCaseFirstLetter(projectName));
    }

    @Override
    public void logVersion() {
        log.info(projectName + " v" + getVersion());
    }

    @Override
    public String getVersion() {
        return projectVersion;
    }

    private static String lowerCaseFirstLetter(String value) {
        char[] chars = value.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutorService;
    }

    @Override
    public <T> Future<T> submitCallable(final Callable<T> callable, final Map<Object, Object> threadBindings) {
        return submitCallable(callable, new IgnoredCallableResult<>(), threadBindings);
    }

    @Override
    public <T> Future<T> submitCallable(final Callable<T> callable, final IFinishedExecution<T> callback, final Map<Object, Object> threadBindings) {

        IFinishedExecution<T> resolvedCallback = callback;
        if (resolvedCallback == null) {
            resolvedCallback = new IgnoredCallableResult<>();
        }
        final IFinishedExecution<T> completion = resolvedCallback;

        /*
         * Per-submission abandonment token. The interrupt flag CANNOT be used as a
         * completion guard: any intermediate catch block (or a bare
         * Thread.interrupted() call) clears it, and the JDK does not guarantee a
         * running body is interrupted at all. The token lives on the returned Future
         * and can never be cleared by the work itself, so a turn the watchdog has
         * already abandoned can never report success and persist its stale snapshot
         * over a newer turn.
         */
        final AtomicBoolean abandoned = new AtomicBoolean(false);

        /*
         * One-shot callback gate: at most ONE of onComplete/onFailure may ever fire for
         * a submission. Without it, an unchecked throw on the COMPLETION path
         * re-entered onFailure — and callers that read onFailure as "the work did not
         * run" (the coordinator's retry) then re-executed an already-executed turn,
         * duplicating LLM calls, tool side effects and cost.
         */
        final AtomicBoolean callbackFired = new AtomicBoolean(false);

        final ExecutorService target = Boolean.TRUE.equals(EXECUTING_SUBMITTED_CALLABLE.get())
                ? nestedExecutorService
                : getExecutorService();

        Future<T> submitted = target.submit(() -> {
            try {
                final T result;
                try {
                    if (threadBindings != null) {
                        ThreadContext.setResources(threadBindings);
                    }

                    EXECUTING_SUBMITTED_CALLABLE.set(Boolean.TRUE);
                    try {
                        result = callable.call();
                    } finally {
                        // Cleared BEFORE the callbacks run: a callback that submits
                        // follow-up work (the coordinator scheduling the next turn) is
                        // not nested work and must keep using the ManagedExecutor.
                        EXECUTING_SUBMITTED_CALLABLE.remove();
                    }
                } catch (Throwable t) {
                    log.error(t.getLocalizedMessage(), t);
                    fireFailure(callbackFired, completion, t);
                    return null;
                }

                // Completion dispatch sits OUTSIDE the guarded region above, so a
                // throwing onComplete can never be routed to onFailure.
                if (abandoned.get() || Thread.currentThread().isInterrupted()) {
                    // Execution was abandoned (e.g. the agent-timeout watchdog cancelled
                    // it) but the callable completed anyway (non-interruptible I/O, or a
                    // swallowed interrupt). Route to onFailure to skip stale persistence
                    // that would overwrite newer state, and return null so the stale
                    // result cannot leak through the Future either.
                    log.warnf("Execution completed after cancellation — discarding result to prevent stale persistence (thread=%s)",
                            Thread.currentThread().getName());
                    fireFailure(callbackFired, completion, new InterruptedException(
                            "Execution completed after cancellation — result discarded"));
                    return null;
                }

                if (callbackFired.compareAndSet(false, true)) {
                    try {
                        completion.onComplete(result);
                    } catch (RuntimeException | Error t) {
                        // Deliberately NOT routed to onFailure: the callable already ran,
                        // and every caller reads onFailure as "the work never happened"
                        // (the coordinator dead-letters the turn, ConversationService
                        // writes ERROR for a turn that actually succeeded).
                        //
                        // It must not vanish either: name the callback that failed AND
                        // fail the Future, so the submitter — which knows the conversation
                        // — still sees it. ConversationService.waitForExecutionFinishOrTimeout
                        // turns the resulting ExecutionException into a context-carrying
                        // logConversationError (conversationId + logging context + ERROR
                        // state), which is exactly what the pre-token onFailure route did.
                        log.errorf(t, "Completion callback %s failed after the work had already executed "
                                + "(callable=%s, thread=%s) — not reported through onFailure (callers read that as "
                                + "'never ran' and would re-execute); surfaced through the Future instead",
                                completion.getClass().getName(), callable.getClass().getName(),
                                Thread.currentThread().getName());
                        throw t;
                    }
                }
                return result;
            } finally {
                ThreadContext.remove();
            }
        });

        return new AbandonableFuture<>(submitted, abandoned);
    }

    private <T> void fireFailure(AtomicBoolean callbackFired, IFinishedExecution<T> completion, Throwable cause) {
        if (callbackFired.compareAndSet(false, true)) {
            try {
                completion.onFailure(cause);
            } catch (Throwable t) {
                log.error("Failure callback threw — swallowing to keep the one-shot callback contract", t);
            }
        }
    }

    /**
     * Wraps the executor's Future so {@link #cancel(boolean)} marks the execution
     * abandoned BEFORE delegating. Cancellation of an already-running body is
     * advisory at best (the JDK's {@code mayInterruptIfRunning} is not honoured by
     * every task, and pipelines routinely swallow interrupts), so the token — not
     * the interrupt flag — is what suppresses the stale completion callback.
     */
    private static final class AbandonableFuture<T> implements Future<T> {
        private final Future<T> delegate;
        private final AtomicBoolean abandoned;

        private AbandonableFuture(Future<T> delegate, AtomicBoolean abandoned) {
            this.delegate = delegate;
            this.abandoned = abandoned;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            abandoned.set(true);
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return delegate.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.get(timeout, unit);
        }
    }

    private static class IgnoredCallableResult<T> implements IFinishedExecution<T> {
        @Override
        public void onComplete(T result) {
            // ignored result
        }

        @Override
        public void onFailure(Throwable t) {
            // ignored result
        }
    }
}
