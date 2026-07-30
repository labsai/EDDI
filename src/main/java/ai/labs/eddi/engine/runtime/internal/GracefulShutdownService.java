/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import io.quarkus.runtime.ShutdownEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;

/**
 * Graceful shutdown for conversation processing (B3).
 *
 * <p>
 * Before this existed there was not a single {@code ShutdownEvent} observer in
 * the engine: a rolling deploy simply killed the JVM, dropping every queued and
 * in-flight turn with no drain and no readiness flip. Callers saw truncated
 * responses and conversations were left in {@code IN_PROGRESS} for the crash
 * recovery observer to clean up on the next boot.
 * </p>
 *
 * <p>
 * On {@link ShutdownEvent} this observer, in order:
 * </p>
 * <ol>
 * <li><b>flips readiness</b> — {@link ShutdownReadinessHealthCheck} is a
 * MicroProfile {@code @Readiness} check, so {@code /q/health/ready} reports
 * DOWN and the load balancer / Kubernetes endpoint controller stops routing new
 * traffic to this pod. It joins the existing readiness set (alongside the agent
 * readiness check) rather than inventing a parallel mechanism;</li>
 * <li><b>stops accepting new turns</b> — {@link #isShuttingDown()} is consulted
 * by {@code ConversationService} on the start/say/sayStreaming entry points,
 * which reject with {@code RejectedExecutionException} once it flips;</li>
 * <li><b>drains the coordinator queues</b> with a BOUNDED wait, so turns
 * already queued or executing get the chance to finish and persist.</li>
 * </ol>
 *
 * <p>
 * The drain observes {@link IConversationCoordinator#getQueueDepths()}, which
 * counts both the queued turns and the head that is currently executing, so an
 * empty depth map means "nothing left in flight on this node". The wait is hard
 * bounded: a pipeline that hangs must never prevent the process from exiting
 * (the orchestrator's SIGKILL grace period is finite anyway).
 * </p>
 *
 * @author ginccc
 */
@ApplicationScoped
public class GracefulShutdownService {

    private static final Logger LOGGER = Logger.getLogger(GracefulShutdownService.class);

    private final IConversationCoordinator conversationCoordinator;

    /**
     * How long to keep draining before giving up. Should be comfortably below the
     * orchestrator's termination grace period.
     */
    private final long drainTimeoutMillis;

    /**
     * Quiet period between flipping readiness and starting the drain, giving the
     * load balancer time to observe the DOWN state and stop sending new requests
     * that would otherwise arrive during the drain and be rejected.
     */
    private final long readinessGraceMillis;

    /** Poll interval for the drain loop. */
    private final long pollIntervalMillis;

    /**
     * Volatile, not atomic: single writer (the shutdown observer), many readers.
     */
    private volatile boolean shuttingDown;

    @Inject
    public GracefulShutdownService(IConversationCoordinator conversationCoordinator,
            @ConfigProperty(name = "eddi.shutdown.drain-timeout-seconds", defaultValue = "30") int drainTimeoutSeconds,
            @ConfigProperty(name = "eddi.shutdown.readiness-grace-seconds", defaultValue = "3") int readinessGraceSeconds,
            @ConfigProperty(name = "eddi.shutdown.drain-poll-millis", defaultValue = "100") long pollIntervalMillis) {
        this(conversationCoordinator, drainTimeoutSeconds * 1000L, readinessGraceSeconds * 1000L, pollIntervalMillis);
    }

    /** Millisecond-precision constructor — used by the tests. */
    GracefulShutdownService(IConversationCoordinator conversationCoordinator, long drainTimeoutMillis,
            long readinessGraceMillis, long pollIntervalMillis) {
        this.conversationCoordinator = conversationCoordinator;
        this.drainTimeoutMillis = Math.max(0, drainTimeoutMillis);
        this.readinessGraceMillis = Math.max(0, readinessGraceMillis);
        this.pollIntervalMillis = Math.max(1, pollIntervalMillis);
    }

    /**
     * @return {@code true} once a {@link ShutdownEvent} has been observed. New
     *         conversation turns must be refused from this point on.
     */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    void onShutdown(@Observes ShutdownEvent shutdownEvent) {
        drain();
    }

    /**
     * Flips readiness + the accept gate, then waits (bounded) for the coordinator
     * to drain. Package-private so the tests can drive it without a CDI container.
     *
     * @return {@code true} if everything drained within the timeout
     */
    boolean drain() {
        // Steps 1 and 2 are the SAME flag flip: readiness reports DOWN and the
        // conversation entry points start rejecting. Do it first and unconditionally
        // — even if the drain below fails, no new work is admitted.
        shuttingDown = true;
        LOGGER.info("Shutdown signalled — readiness is now DOWN and new conversation turns are rejected");

        // The poll loop below honours this return value; the grace sleep must too, or
        // an interrupt gets acknowledged (the flag is restored) and then ignored — we
        // would keep waiting after being told to stop, and the first poll would throw
        // immediately anyway, landing in the "timed out" branch and misreporting why.
        if (!sleepQuietly(readinessGraceMillis)) {
            int remaining = countInFlight();
            if (remaining == 0) {
                LOGGER.info("Readiness grace interrupted, but nothing was in flight — shutdown drain completed");
                return true;
            }
            LOGGER.warnf("Shutdown drain interrupted during the readiness grace window with %d conversation task(s) in flight — "
                    + "not waiting further; crash recovery reconciles their state on the next boot", remaining);
            return false;
        }

        long deadline = System.nanoTime() + drainTimeoutMillis * 1_000_000L;
        int inFlight = countInFlight();
        if (inFlight == 0) {
            LOGGER.info("Nothing in flight — shutdown drain completed immediately");
            return true;
        }

        LOGGER.infof("Draining %d in-flight conversation task(s), waiting up to %d ms", inFlight, drainTimeoutMillis);
        boolean interrupted = false;
        while (inFlight > 0 && System.nanoTime() < deadline) {
            if (!sleepQuietly(pollIntervalMillis)) {
                interrupted = true;
                // Re-check once before deciding: the last turn may have finished DURING
                // this sleep, with the interrupt arriving immediately after. Reporting
                // "still in flight" for a drain that actually completed is the same
                // misreporting the interrupt/timeout split above exists to prevent.
                inFlight = countInFlight();
                break;
            }
            inFlight = countInFlight();
        }

        if (inFlight > 0) {
            // Interrupt and timeout are different failures and must not read alike:
            // "timed out after 30000 ms" for a drain cut short after 50 ms sends
            // whoever reads that log hunting the wrong problem.
            if (interrupted) {
                LOGGER.warnf("Shutdown drain interrupted with %d conversation task(s) still in flight — "
                        + "they will be abandoned; crash recovery reconciles their state on the next boot", inFlight);
            } else {
                LOGGER.warnf("Shutdown drain timed out after %d ms with %d conversation task(s) still in flight — "
                        + "they will be abandoned; crash recovery reconciles their state on the next boot",
                        drainTimeoutMillis, inFlight);
            }
            return false;
        }

        LOGGER.info("Shutdown drain completed — all conversation tasks finished");
        return true;
    }

    private int countInFlight() {
        try {
            Map<String, Integer> depths = conversationCoordinator.getQueueDepths();
            if (depths == null || depths.isEmpty()) {
                return 0;
            }
            int total = 0;
            for (Integer depth : depths.values()) {
                if (depth != null) {
                    total += depth;
                }
            }
            return total;
        } catch (RuntimeException e) {
            // A coordinator that cannot report its depth must not block the exit.
            LOGGER.warnf("Could not read coordinator queue depths during shutdown drain: %s", e.getMessage());
            return 0;
        }
    }

    /**
     * @return {@code false} if the wait was interrupted (the caller should stop
     *         waiting — the interrupt flag is restored)
     */
    private static boolean sleepQuietly(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
