/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.net;

import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A {@link UrlValidationUtils.HostResolver} that gives up after a deadline.
 * <p>
 * {@code InetAddress.getAllByName} has no timeout: a slow or black-holed name
 * server holds the calling thread for the OS resolver's default, tens of
 * seconds per server. The lookups that validate an httpcall target run on a
 * deploying thread and on a conversation turn, so they are bounded here.
 * <p>
 * <b>Fails closed.</b> A timeout, an interrupt and a saturated lookup pool all
 * surface as {@link UnknownHostException}, which every caller already treats as
 * "does not resolve": with SSRF protection on the call is refused, never
 * allowed because the address could not be checked. The lookup runs on a small
 * bounded pool of daemon threads (never one thread per lookup); a lookup that
 * outlives its deadline keeps its pool thread until the OS gives up, so a flood
 * of black-holed names fills the pool and is then refused immediately rather
 * than queuing callers.
 */
public final class BoundedHostResolver implements UrlValidationUtils.HostResolver {

    /**
     * Default deadline, matching {@code eddi.security.ssrf-protection.dns-timeout}.
     */
    public static final long DEFAULT_TIMEOUT_MILLIS = 5_000;

    private static final ExecutorService POOL = newPool();

    private final UrlValidationUtils.HostResolver delegate;
    private final long timeoutMillis;
    private final ExecutorService pool;

    /** The JDK resolver, bounded by {@code timeoutMillis}. */
    public static BoundedHostResolver jdk(long timeoutMillis) {
        return new BoundedHostResolver(InetAddress::getAllByName, timeoutMillis, POOL);
    }

    public BoundedHostResolver(UrlValidationUtils.HostResolver delegate, long timeoutMillis, ExecutorService pool) {
        this.delegate = delegate;
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS;
        this.pool = pool;
    }

    @Override
    public InetAddress[] resolveAll(String host) throws UnknownHostException {
        Future<InetAddress[]> lookup;
        try {
            lookup = pool.submit(() -> delegate.resolveAll(host));
        } catch (RejectedExecutionException e) {
            throw new UnknownHostException("DNS lookup refused: too many lookups in flight");
        }
        try {
            return lookup.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            lookup.cancel(true);
            throw new UnknownHostException("DNS lookup timed out after " + timeoutMillis + " ms");
        } catch (InterruptedException e) {
            lookup.cancel(true);
            Thread.currentThread().interrupt();
            throw new UnknownHostException("DNS lookup interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownHostException uhe) {
                throw uhe;
            }
            throw new UnknownHostException("DNS lookup failed: " + e.getCause());
        }
    }

    private static ExecutorService newPool() {
        var executor = new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "eddi-dns-lookup");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
