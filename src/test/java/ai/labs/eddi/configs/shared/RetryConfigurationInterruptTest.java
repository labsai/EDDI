/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cancelled attempt on the deadline-bounded path is an interrupt, not a
 * provider failure: it must surface as a {@code LifecycleInterruptedException}
 * with the thread's interrupt flag restored.
 */
@DisplayName("RetryConfiguration — interrupt on the deadline-bounded path")
class RetryConfigurationInterruptTest {

    @Test
    @DisplayName("an interrupted wait surfaces as LifecycleInterruptedException and keeps the interrupt flag")
    void interruptIsNotClassifiedAsAFailure() throws Exception {
        var started = new CountDownLatch(1);
        var thrown = new AtomicReference<Throwable>();
        var flag = new AtomicBoolean();
        var deadline = TurnDeadline.of(Clock.systemUTC(), System.currentTimeMillis(), 60_000, 1_500L);

        Thread worker = new Thread(() -> {
            try {
                RetryConfiguration.executeWithRetry(() -> {
                    started.countDown();
                    new CountDownLatch(1).await(); // blocks until the attempt is cancelled
                    return "never";
                }, new RetryConfiguration(), "test", new long[1], deadline);
            } catch (Throwable t) {
                thrown.set(t);
            }
            flag.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        worker.interrupt();
        worker.join(5_000);

        assertInstanceOf(LifecycleException.LifecycleInterruptedException.class, thrown.get(), String.valueOf(thrown.get()));
        assertTrue(flag.get(), "the interrupt flag must be restored for cooperative cancellation upstream");
    }
}
