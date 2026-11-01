/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * TXT lookups for sender authentication: the DKIM key at
 * {@code <selector>._domainkey.<domain>} and the DMARC record at
 * {@code _dmarc.<domain>} (email plan §8).
 * <p>
 * Everything a lookup returns was written by whoever controls the queried
 * domain, which for a forged message is the attacker. So:
 * <ul>
 * <li><b>Bounded per message.</b> Each message gets a {@link Budget} of network
 * lookups ({@code eddi.email.dns.max-lookups-per-message}, default 10). A
 * message with many signatures cannot turn one mail into many queries; cache
 * hits are free.</li>
 * <li><b>Bounded per answer.</b> A record over {@value #MAX_RECORD_LENGTH}
 * characters (after joining its strings) or an answer with more than
 * {@value #MAX_RECORDS} TXT records is refused as {@link Status#OVERSIZE}: the
 * largest real DKIM key (RSA-4096) is under 800.</li>
 * <li><b>A cache that honours the record's TTL, capped</b>
 * ({@code eddi.email.dns.cache-max-ttl}, default 1 h) and bounded in size
 * ({@code eddi.email.dns.cache-max-entries}). Negative answers are cached for
 * the zone's SOA minimum, capped the same way. Temporary failures are not
 * cached.</li>
 * <li><b>Timeouts.</b> {@code eddi.email.dns.timeout} (default 5 s) per query,
 * one retry.</li>
 * <li><b>DNSSEC is not required.</b> The answer is taken from the configured
 * resolver as is; operating a validating resolver is how a deployment adds
 * it.</li>
 * </ul>
 * The servers are {@code eddi.email.dns.servers} ({@code host} or
 * {@code host:port}, IPv6 in brackets), or the system's resolvers when unset —
 * an air-gapped deployment points this at its own resolver. Queries go through
 * dnsjava, created on first use; the {@link TxtSource} seam is what tests
 * replace to run fully offline.
 * <p>
 * Never throws: every failure is a {@link Status}. Thread-safe; a
 * {@link Budget} is per message and not.
 */
@ApplicationScoped
public class MailDnsResolver {

    private static final Logger LOGGER = Logger.getLogger(MailDnsResolver.class);

    static final String METER_LOOKUPS = "eddi.email.dns.lookups";

    /** Characters of one TXT record, its strings joined. */
    public static final int MAX_RECORD_LENGTH = 4096;

    /** TXT records in one answer. */
    public static final int MAX_RECORDS = 10;

    /** How long a negative answer without an SOA is cached. */
    private static final Duration DEFAULT_NEGATIVE_TTL = Duration.ofMinutes(1);

    /**
     * Letters, digits, hyphen and underscore ({@code _dmarc}, {@code _domainkey}).
     */
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?");

    /** What a lookup came to. */
    public enum Status {
        /** At least one TXT record. */
        FOUND,
        /** The name does not exist, or has no TXT records. */
        NOT_FOUND,
        /** Timeout, SERVFAIL, REFUSED or a network error: try again later. */
        TEMPFAIL,
        /** The message's lookup budget is spent; nothing was sent. */
        BUDGET_EXHAUSTED,
        /** An answer over the size limits, refused. */
        OVERSIZE,
        /** Not a DNS name; nothing was sent. */
        INVALID_NAME;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The result of one lookup.
     *
     * @param status
     *            the outcome
     * @param records
     *            the TXT records, each with its strings concatenated (RFC 6376
     *            §3.6.2.2, RFC 7489 §6.6.3); empty unless {@link Status#FOUND}
     */
    public record TxtLookup(Status status, List<String> records) {
        public TxtLookup {
            Objects.requireNonNull(status, "status");
            records = records == null ? List.of() : List.copyOf(records);
        }

        static TxtLookup of(Status status) {
            return new TxtLookup(status, List.of());
        }
    }

    /**
     * What a {@link TxtSource} answered.
     *
     * @param status
     *            {@link Status#FOUND}, {@link Status#NOT_FOUND},
     *            {@link Status#TEMPFAIL} or {@link Status#OVERSIZE}
     * @param records
     *            the records, strings joined
     * @param ttl
     *            how long the answer may be cached, or null for "unknown"
     */
    public record RawAnswer(Status status, List<String> records, Duration ttl) {
        public RawAnswer {
            Objects.requireNonNull(status, "status");
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    /** Where answers come from: dnsjava in production, a map in tests. */
    @FunctionalInterface
    public interface TxtSource {
        /**
         * Queries the TXT records of an absolute, validated name. May throw; the
         * resolver turns any exception into {@link Status#TEMPFAIL}.
         */
        RawAnswer query(String name) throws Exception;
    }

    /**
     * The network lookups one message may still cause. Not thread-safe: one per
     * message, used by one thread.
     */
    public static final class Budget {
        private int remaining;

        public Budget(int lookups) {
            this.remaining = Math.max(0, lookups);
        }

        /** Lookups left. */
        public int remaining() {
            return remaining;
        }

        boolean take() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }

    private record Cached(TxtLookup lookup, long ttlNanos) {
    }

    private final TxtSource source;
    private final Cache<String, Cached> cache;
    private final Duration maxTtl;
    private final int maxLookupsPerMessage;
    private final MeterRegistry meterRegistry;

    @Inject
    public MailDnsResolver(@ConfigProperty(name = "eddi.email.dns.servers") Optional<List<String>> servers,
            @ConfigProperty(name = "eddi.email.dns.timeout", defaultValue = "5s") Duration timeout,
            @ConfigProperty(name = "eddi.email.dns.cache-max-entries", defaultValue = "10000") int cacheMaxEntries,
            @ConfigProperty(name = "eddi.email.dns.cache-max-ttl", defaultValue = "1h") Duration cacheMaxTtl,
            @ConfigProperty(name = "eddi.email.dns.max-lookups-per-message", defaultValue = "10") int maxLookupsPerMessage,
            MeterRegistry meterRegistry) {
        this(new DnsjavaTxtSource(servers.orElse(List.of()), timeout), cacheMaxEntries, cacheMaxTtl, maxLookupsPerMessage, meterRegistry,
                Ticker.systemTicker());
    }

    /** For tests: any source, any clock. */
    public MailDnsResolver(TxtSource source, int cacheMaxEntries, Duration cacheMaxTtl, int maxLookupsPerMessage,
            MeterRegistry meterRegistry, Ticker ticker) {
        this.source = Objects.requireNonNull(source, "source");
        this.maxTtl = cacheMaxTtl == null || cacheMaxTtl.isNegative() || cacheMaxTtl.isZero() ? Duration.ofHours(1) : cacheMaxTtl;
        this.maxLookupsPerMessage = maxLookupsPerMessage > 0 ? maxLookupsPerMessage : 10;
        this.meterRegistry = meterRegistry;
        this.cache = Caffeine.newBuilder().maximumSize(cacheMaxEntries > 0 ? cacheMaxEntries : 10_000).ticker(ticker)
                .expireAfter(new Expiry<String, Cached>() {
                    @Override
                    public long expireAfterCreate(String key, Cached value, long currentTime) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Cached value, long currentTime, long currentDuration) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, Cached value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                }).build();
    }

    /** A fresh budget for one message. */
    public Budget newBudget() {
        return new Budget(maxLookupsPerMessage);
    }

    /**
     * Looks up the TXT records of a name.
     *
     * @param name
     *            a DNS name, with or without the trailing dot
     * @param budget
     *            the message's budget; a network query takes one lookup from it
     */
    public TxtLookup lookupTxt(String name, Budget budget) {
        String key = normalise(name);
        if (key == null) {
            count(Status.INVALID_NAME, false);
            return TxtLookup.of(Status.INVALID_NAME);
        }
        Cached hit = cache.getIfPresent(key);
        if (hit != null) {
            count(hit.lookup().status(), true);
            return hit.lookup();
        }
        if (budget == null || !budget.take()) {
            count(Status.BUDGET_EXHAUSTED, false);
            return TxtLookup.of(Status.BUDGET_EXHAUSTED);
        }
        RawAnswer answer;
        try {
            answer = source.query(key);
        } catch (Exception e) {
            LOGGER.debugf("TXT lookup failed (%s)", e.getClass().getSimpleName());
            answer = null;
        }
        TxtLookup lookup = interpret(answer);
        count(lookup.status(), false);
        if (lookup.status() != Status.TEMPFAIL) {
            Duration ttl = answer != null && answer.ttl() != null
                    ? answer.ttl()
                    : lookup.status() == Status.FOUND ? Duration.ZERO : DEFAULT_NEGATIVE_TTL;
            long nanos = clampTtl(ttl).toNanos();
            if (nanos > 0) {
                cache.put(key, new Cached(lookup, nanos));
            }
        }
        return lookup;
    }

    private static TxtLookup interpret(RawAnswer answer) {
        if (answer == null) {
            return TxtLookup.of(Status.TEMPFAIL);
        }
        return switch (answer.status()) {
            case FOUND -> {
                if (answer.records().isEmpty()) {
                    yield TxtLookup.of(Status.NOT_FOUND);
                }
                if (answer.records().size() > MAX_RECORDS) {
                    yield TxtLookup.of(Status.OVERSIZE);
                }
                for (String record : answer.records()) {
                    if (record == null || record.length() > MAX_RECORD_LENGTH) {
                        yield TxtLookup.of(Status.OVERSIZE);
                    }
                }
                yield new TxtLookup(Status.FOUND, answer.records());
            }
            case NOT_FOUND, OVERSIZE -> TxtLookup.of(answer.status());
            default -> TxtLookup.of(Status.TEMPFAIL);
        };
    }

    private Duration clampTtl(Duration ttl) {
        if (ttl.isNegative()) {
            return Duration.ZERO;
        }
        return ttl.compareTo(maxTtl) > 0 ? maxTtl : ttl;
    }

    /** Lower-case, absolute (trailing dot), or null when not a DNS name. */
    static String normalise(String name) {
        if (name == null) {
            return null;
        }
        String n = name.strip().toLowerCase(Locale.ROOT);
        if (n.endsWith(".")) {
            n = n.substring(0, n.length() - 1);
        }
        if (n.isEmpty() || n.length() > 253) {
            return null;
        }
        for (String label : n.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                return null;
            }
        }
        return n + ".";
    }

    private void count(Status status, boolean cached) {
        if (meterRegistry != null) {
            meterRegistry.counter(METER_LOOKUPS, "result", status.tag(), "cached", Boolean.toString(cached)).increment();
        }
    }
}
