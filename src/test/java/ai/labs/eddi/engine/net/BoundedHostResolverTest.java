/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.net;

import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BoundedHostResolver")
class BoundedHostResolverTest {

    private ExecutorService pool;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        pool.shutdownNow();
    }

    private static InetAddress[] address() throws UnknownHostException {
        return new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})};
    }

    @Test
    @DisplayName("a fast lookup returns its addresses")
    void fastLookupProceeds() throws Exception {
        var expected = address();
        var resolver = new BoundedHostResolver(host -> expected, 1_000, pool);
        assertArrayEquals(expected, resolver.resolveAll("example.test"));
    }

    @Test
    @DisplayName("a lookup that outlives the deadline is refused as unresolvable, and frees the caller")
    void slowLookupTimesOutAndFailsClosed() {
        UrlValidationUtils.HostResolver blackHole = host -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return address();
        };
        var resolver = new BoundedHostResolver(blackHole, 100, pool);
        long start = System.nanoTime();
        var e = assertThrows(UnknownHostException.class, () -> resolver.resolveAll("slow.test"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue(elapsedMs < 5_000, "caller must be freed at the deadline, took " + elapsedMs);
    }

    @Test
    @DisplayName("a failed lookup propagates as UnknownHostException")
    void failureRefuses() {
        var resolver = new BoundedHostResolver(host -> {
            throw new UnknownHostException(host);
        }, 1_000, pool);
        assertThrows(UnknownHostException.class, () -> resolver.resolveAll("nope.test"));
    }

    @Test
    @DisplayName("a saturated pool refuses at once instead of queuing the caller")
    void saturatedPoolFailsClosed() {
        var full = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>());
        try {
            UrlValidationUtils.HostResolver blackHole = host -> {
                // like the JDK resolver, ignores interrupts: the thread stays busy
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // keep waiting
                    }
                }
                return address();
            };
            var resolver = new BoundedHostResolver(blackHole, 50, full);
            assertThrows(UnknownHostException.class, () -> resolver.resolveAll("a.test"));
            var e = assertThrows(UnknownHostException.class, () -> resolver.resolveAll("b.test"));
            assertTrue(e.getMessage().contains("too many"), e.getMessage());
        } finally {
            release.countDown();
            full.shutdownNow();
        }
    }
}
