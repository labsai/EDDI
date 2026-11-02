/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Cluster-wide record that a conversation is being, or has been, erased (GDPR
 * Art. 17).
 * <p>
 * A node cut off from NATS keeps the dead letters of its failed turns locally
 * and forwards them to the shared stream when it reconnects. If the erasure's
 * shared purge ran before that, the stream no longer holds the turn's input —
 * and the forwarding node would put it back, because the conversation's
 * snapshot is deleted only a few steps later and still "exists" until then.
 * <p>
 * The erasure therefore writes a tombstone <em>before</em> it purges, and
 * forwarding checks it before publishing and again right after
 * ({@link ai.labs.eddi.engine.runtime.internal.ClusterConversationCoordinator}).
 * The tombstone holds nothing but the conversation id as a key (no personal
 * data) and expires after {@code eddi.cluster.erasure-marker-ttl}: a node must
 * reconnect within that time for the marker to protect its dead letters; after
 * it, the conversation store's own "does it still exist" check has long since
 * become authoritative.
 *
 * @since 6.6.0
 */
@ApplicationScoped
public class ErasureTombstones {

    static final String BUCKET_NAME = "ERASED";
    private static final byte[] MARK = "1".getBytes(StandardCharsets.UTF_8);

    private final ISharedKv bucket;

    @Inject
    public ErasureTombstones(ISharedStateFactory sharedState,
            @ConfigProperty(name = "eddi.cluster.erasure-marker-ttl", defaultValue = "P7D") Duration ttl) {
        this(sharedState.bucket(new SharedBucket(BUCKET_NAME, ttl, 64)));
    }

    /** For tests and the in-JVM cluster ITs. */
    public ErasureTombstones(ISharedKv bucket) {
        this.bucket = bucket;
    }

    /**
     * Records that the conversation is being erased.
     *
     * @throws ClusterUnavailableException
     *             NATS is unreachable: the marker was not written
     */
    public void mark(String conversationId) {
        if (conversationId != null) {
            bucket.put(KvKeys.safe(conversationId), MARK);
        }
    }

    /**
     * Whether the conversation was marked.
     *
     * @throws ClusterUnavailableException
     *             NATS is unreachable: the answer is unknown, not "no"
     */
    public boolean isMarked(String conversationId) {
        return conversationId != null && bucket.get(KvKeys.safe(conversationId)).isPresent();
    }
}
