/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Rate limits of the public owner report endpoint, per node: token buckets that
 * refill continuously. They are arranged so that requests with tokens that lead
 * nowhere can never use up the capacity of the links that exist.
 * <ul>
 * <li><b>Lookups of unknown tokens</b> are charged <em>before</em> the database
 * lookup, to two buckets: {@value #CLIENT_LOOKUPS_PER_MINUTE} a minute per
 * client address and {@value #LOOKUPS_PER_MINUTE} a minute across all clients.
 * A token that turns out to belong to a live link gets both back
 * ({@link #found}), and a token hash found within the last
 * {@value #KNOWN_MINUTES} minutes is not charged at all — so a scan exhausting
 * these buckets costs at most that many database lookups and blocks only the
 * <em>first</em> read of a link on this node, never a link already being read.
 * Guessing a 256-bit token is hopeless anyway.</li>
 * <li><b>Per link</b>: {@value #PER_LINK_PER_MINUTE} reads a minute, charged
 * only once the link is found — an owner reloading a page is far below it; a
 * script hammering one link is not.</li>
 * <li><b>All found reports</b>: {@value #GLOBAL_PER_MINUTE} a minute, charged
 * only once a link is found, so the endpoint cannot load the health stores —
 * and unknown tokens never count against it.</li>
 * </ul>
 * The client address is the remote address as Quarkus resolves it: the peer, or
 * — only with {@code quarkus.http.proxy.proxy-address-forwarding} and a peer in
 * {@code quarkus.http.proxy.trusted-proxies} — the forwarded client. A raw
 * {@code X-Forwarded-For} is never read: it is the caller's to forge. Behind a
 * proxy that is not configured that way every client shares the proxy's
 * address, which is why the per-client bucket only ever charges unknown tokens.
 */
@ApplicationScoped
public class HealthReportRateLimiter {

    static final int PER_LINK_PER_MINUTE = 30;
    static final int GLOBAL_PER_MINUTE = 300;
    static final int LOOKUPS_PER_MINUTE = 60;
    static final int CLIENT_LOOKUPS_PER_MINUTE = 10;
    static final int KNOWN_MINUTES = 10;
    private static final double MINUTE_NANOS = 60_000_000_000.0;

    /** A token bucket refilled at {@code limit} per minute. */
    static final class Bucket {
        private final int limit;
        private double tokens;
        private long last;

        Bucket(int limit, long now) {
            this.limit = limit;
            this.tokens = limit;
            this.last = now;
        }

        synchronized boolean tryAcquire(long now) {
            long elapsed = now - last;
            if (elapsed > 0) {
                tokens = Math.min(limit, tokens + (double) elapsed * limit / MINUTE_NANOS);
                last = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized void refund() {
            tokens = Math.min(limit, tokens + 1.0);
        }
    }

    /** What a lookup was charged to, so a found link can be refunded. */
    public record Charge(boolean allowed, String client, boolean charged) {
    }

    private final LongSupplier nanos;
    private final Bucket global;
    private final Bucket lookups;
    private final Cache<String, Bucket> links;
    private final Cache<String, Bucket> clients;
    private final Cache<String, Boolean> known;

    public HealthReportRateLimiter() {
        this(System::nanoTime);
    }

    HealthReportRateLimiter(LongSupplier nanos) {
        this.nanos = nanos;
        long now = nanos.getAsLong();
        this.global = new Bucket(GLOBAL_PER_MINUTE, now);
        this.lookups = new Bucket(LOOKUPS_PER_MINUTE, now);
        this.links = Caffeine.newBuilder().maximumSize(10_000).expireAfterAccess(Duration.ofMinutes(2)).build();
        this.clients = Caffeine.newBuilder().maximumSize(50_000).expireAfterAccess(Duration.ofMinutes(2)).build();
        this.known = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(KNOWN_MINUTES)).build();
    }

    /**
     * Charges one database lookup of {@code tokenHash} for {@code client} — before
     * the lookup. Free for a hash recently found; else it takes one from the
     * client's bucket and one from the shared lookup bucket, and is refused when
     * either is empty.
     */
    public Charge tryLookup(String tokenHash, String client) {
        if (known.getIfPresent(tokenHash) != null) {
            return new Charge(true, client, false);
        }
        long now = nanos.getAsLong();
        String key = client == null || client.isBlank() ? "unknown" : client;
        Bucket perClient = clients.get(key, k -> new Bucket(CLIENT_LOOKUPS_PER_MINUTE, now));
        if (!perClient.tryAcquire(now)) {
            return new Charge(false, key, false);
        }
        if (!lookups.tryAcquire(now)) {
            perClient.refund();
            return new Charge(false, key, false);
        }
        return new Charge(true, key, true);
    }

    /**
     * The lookup found a live link: what it was charged is given back, and the hash
     * is known for {@value #KNOWN_MINUTES} minutes.
     */
    public void found(String tokenHash, Charge charge) {
        known.put(tokenHash, Boolean.TRUE);
        if (charge != null && charge.charged()) {
            lookups.refund();
            Bucket perClient = clients.getIfPresent(charge.client());
            if (perClient != null) {
                perClient.refund();
            }
        }
    }

    /** The lookup found nothing (unknown, expired, revoked): no longer known. */
    public void missed(String tokenHash) {
        known.invalidate(tokenHash);
    }

    /**
     * Whether the found link with this token hash may be read once more — its own
     * bucket, then the shared one for found reports.
     */
    public boolean tryRead(String tokenHash) {
        long now = nanos.getAsLong();
        Bucket link = links.get(tokenHash, k -> new Bucket(PER_LINK_PER_MINUTE, now));
        if (!link.tryAcquire(now)) {
            return false;
        }
        if (!global.tryAcquire(now)) {
            link.refund();
            return false;
        }
        return true;
    }
}
