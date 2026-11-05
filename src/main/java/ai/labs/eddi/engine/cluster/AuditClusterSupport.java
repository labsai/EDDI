/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import ai.labs.eddi.engine.audit.IAuditClusterSupport;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.cluster.events.IClusterEventBus;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Cluster side of the audit ledger. Inert on a single node — no NATS bean is
 * resolved unless {@code eddi.messaging.type=nats}.
 * <p>
 * <b>Sequence allocation</b>: one counter per conversation in the
 * {@code AUDIT_SEQ} bucket ({@code s.<conversation>}, the next free position),
 * advanced by compare-and-set for every entry. The turn holds the
 * conversation's lease, so the counter is uncontended in practice; ranges were
 * rejected because a node hand-over would leave gaps that verification reports
 * as deletions. A missing counter is seeded from the store's highest stored
 * position. While NATS is unreachable the entry is recorded unsequenced — never
 * with a position that might already have been handed out elsewhere — and,
 * after such a failure, for one NATS request timeout without asking again, so a
 * NATS that hangs instead of refusing costs one timeout, not one per entry.
 */
@ApplicationScoped
public class AuditClusterSupport implements IAuditClusterSupport {

    private static final Logger LOGGER = Logger.getLogger(AuditClusterSupport.class);
    private static final SharedBucket AUDIT_SEQ = new SharedBucket("AUDIT_SEQ", Duration.ofDays(7), 256);
    private static final int MAX_ATTEMPTS = 10;

    private final ClusterConfig config;
    private final Instance<NatsSharedStateFactory> sharedState;
    private final Instance<JetStreamDeadLetterStore> deadLetters;
    private final Instance<IClusterEventBus> events;
    private final MeterRegistry meterRegistry;
    private volatile ISharedKv counters;
    /**
     * Until when (monotonic nanos) entries are recorded unsequenced without asking
     * NATS, after a call found it unreachable. A NATS that keeps the connection
     * open but does not answer costs every call the full request timeout; without
     * this pause each audit entry of a turn waited that long in turn.
     */
    private volatile long unavailableUntilNanos = System.nanoTime();

    /**
     * Seals the user-id hash of {@code gdpr.user-erased} when a signing key is set
     * (B5). Field-injected; null in tests.
     */
    @Inject
    Instance<ClusterMessageSigner> signer;

    private ClusterMessageSigner signer() {
        return signer != null && signer.isResolvable() ? signer.get() : null;
    }

    @Inject
    public AuditClusterSupport(ClusterConfig config, Instance<NatsSharedStateFactory> sharedState,
            Instance<JetStreamDeadLetterStore> deadLetters, Instance<IClusterEventBus> events, MeterRegistry meterRegistry) {
        this.config = config;
        this.sharedState = sharedState;
        this.deadLetters = deadLetters;
        this.events = events;
        this.meterRegistry = meterRegistry;
    }

    /** For tests: a counter bucket supplied directly. */
    AuditClusterSupport(ISharedKv counters, MeterRegistry meterRegistry) {
        this(counters, ClusterConfig.defaults().withMessagingType(ClusterConfig.NATS), meterRegistry);
    }

    /** For tests: a counter bucket and a configuration supplied directly. */
    AuditClusterSupport(ISharedKv counters, ClusterConfig config, MeterRegistry meterRegistry) {
        this(config, null, null, null, meterRegistry);
        this.counters = counters;
    }

    @Override
    public boolean isClustered() {
        return config.isNats();
    }

    private ISharedKv counters() {
        ISharedKv kv = counters;
        if (kv == null) {
            kv = sharedState.get().bucket(AUDIT_SEQ);
            counters = kv;
        }
        return kv;
    }

    @Override
    public OptionalLong nextSequence(String conversationId, LongSupplier seed) {
        String key = "s." + KvKeys.safe(conversationId);
        if (System.nanoTime() - unavailableUntilNanos < 0) {
            count("unsequenced");
            return OptionalLong.empty();
        }
        try {
            ISharedKv kv = counters();
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                Optional<ISharedKv.Versioned> current = kv.get(key);
                if (current.isEmpty()) {
                    long first = seed.getAsLong();
                    if (kv.create(key, bytes(first + 1)).isPresent()) {
                        // Counted (B5): a counter that is re-seeded after it was lost — the bucket
                        // recreated, or a conversation idle past its TTL — restarts from the store's
                        // highest position, and a position handed out but not yet stored (queued,
                        // or waiting in the dead-letter stream) would be handed out again. A
                        // margin would avoid that, but it is a gap in the chain, which
                        // verification grades BROKEN; this makes such a duplicate attributable.
                        count("seeded");
                        return allocated(first);
                    }
                    continue; // another node seeded it first
                }
                long next = Long.parseLong(new String(current.get().value(), StandardCharsets.UTF_8).trim());
                if (kv.update(key, bytes(next + 1), current.get().revision()).isPresent()) {
                    return allocated(next);
                }
            }
            count("conflict");
            return OptionalLong.empty();
        } catch (ClusterUnavailableException e) {
            unavailableUntilNanos = System.nanoTime() + config.natsRequestTimeout().toNanos();
            count("unsequenced");
            return OptionalLong.empty();
        } catch (NumberFormatException e) {
            count("unsequenced");
            return OptionalLong.empty();
        }
    }

    private OptionalLong allocated(long position) {
        count("allocated");
        return OptionalLong.of(position);
    }

    private void count(String outcome) {
        meterRegistry.counter("eddi.cluster.audit.sequence", "outcome", outcome).increment();
    }

    private static byte[] bytes(long value) {
        return Long.toString(value).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean publishDeadLetter(String json) {
        if (!isClustered()) {
            return false;
        }
        try {
            deadLetters.get().appendAudit(json.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (ClusterUnavailableException e) {
            return false;
        }
    }

    /**
     * The user-id hash of a {@code gdpr.user-erased} event, sealed or plain; null
     * when it cannot be opened.
     */
    String userIdHashOf(ClusterEvent event) {
        String sealed = event.getString("userIdSealed");
        if (sealed == null) {
            return event.getString("userIdHash");
        }
        ClusterMessageSigner s = signer();
        Optional<String> opened = s == null ? Optional.empty() : s.unseal(sealed);
        if (opened.isEmpty()) {
            LOGGER.warnf("A gdpr.user-erased event from node %s could not be opened: eddi.cluster.signing.key differs on this node",
                    event.originNode());
            return null;
        }
        return opened.get();
    }

    @Override
    public void announceUserErased(String userId) {
        if (isClustered() && userId != null) {
            String hash = KvKeys.sha256(userId);
            ClusterMessageSigner s = signer();
            String sealed = s == null ? null : s.seal(hash);
            events.get().publish(ClusterEvent.GDPR_USER_ERASED, sealed != null ? Map.of("userIdSealed", sealed) : Map.of("userIdHash", hash));
        }
    }

    @Override
    public void onUserErased(Consumer<String> userIdHashHandler) {
        if (isClustered()) {
            events.get().subscribe(ClusterEvent.GDPR_USER_ERASED, event -> {
                String hash = userIdHashOf(event);
                if (hash != null) {
                    userIdHashHandler.accept(hash);
                }
            });
        }
    }
}
