/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import ai.labs.eddi.engine.cluster.ClusterMessageSigner.Rejection;
import ai.labs.eddi.engine.cluster.ClusterMessageSigner.Verdict;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.impl.Headers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The replay cache used to be bounded by entry count with eviction, and kept
 * every nonce for two hours whatever its window: once it held that many, a
 * nonce whose message was still fresh could be evicted, and a captured message
 * carrying it was accepted a second time.
 */
@DisplayName("ClusterMessageSigner — the nonce cache never forgets a replayable nonce")
class ClusterNonceCapacityTest {

    private static final String KEY = "fixturefixturefixturefixturefixture-nonce-key";
    private static final String SUBJECT = "eddi.rpc.admin-drain";
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private ClusterMessageSigner signer(long maxNonces) {
        return new ClusterMessageSigner(
                ClusterSigningConfig.of(KEY, List.of(), true, WINDOW).withMaxRememberedNonces(maxNonces), new SimpleMeterRegistry())
                .withClock(now::get);
    }

    private static Signed sign(ClusterMessageSigner signer, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new Signed(signer.sign(SUBJECT, bytes, new Headers(), 1), bytes);
    }

    private static Verdict verify(ClusterMessageSigner signer, Signed message) {
        return signer.verify("rpc", SUBJECT, message.headers(), message.body(), WINDOW, true);
    }

    private record Signed(Headers headers, byte[] body) {
    }

    @Test
    @DisplayName("a full cache refuses a new message instead of evicting a nonce that could still be replayed")
    void fullCacheFailsClosed() {
        ClusterMessageSigner signer = signer(3);
        Signed captured = sign(signer, "first");
        assertTrue(verify(signer, captured).accepted());
        assertTrue(verify(signer, sign(signer, "second")).accepted());
        assertTrue(verify(signer, sign(signer, "third")).accepted());

        Verdict overflow = verify(signer, sign(signer, "fourth"));

        assertFalse(overflow.accepted(), "a nonce that cannot be remembered must not be accepted");
        assertEquals(Rejection.NONCE_CAPACITY, overflow.rejection());
        assertEquals(Rejection.REPLAYED, verify(signer, captured).rejection(), "the captured message is still refused");
    }

    @Test
    @DisplayName("a nonce is forgotten once its message has expired, which makes room again")
    void expiredNoncesFreeCapacity() {
        ClusterMessageSigner signer = signer(2);
        Signed old = sign(signer, "first");
        assertTrue(verify(signer, old).accepted());
        assertTrue(verify(signer, sign(signer, "second")).accepted());

        now.addAndGet(WINDOW.toMillis() + 1);

        assertTrue(verify(signer, sign(signer, "third")).accepted(), "expired nonces do not count against the capacity");
        assertEquals(Rejection.EXPIRED, verify(signer, old).rejection(), "the forgotten message is refused by its age");
    }

    @Test
    @DisplayName("a replay at the last millisecond of the window is still refused by its nonce")
    void nonceOutlivesTheWholeWindow() {
        ClusterMessageSigner signer = signer(10);
        Signed captured = sign(signer, "first");
        assertTrue(verify(signer, captured).accepted());

        now.addAndGet(WINDOW.toMillis());

        assertEquals(Rejection.REPLAYED, verify(signer, captured).rejection());
    }

    @Test
    @DisplayName("the capacity defaults when it is not positive")
    void nonPositiveCapacityDefaults() {
        ClusterMessageSigner signer = signer(0);
        assertTrue(verify(signer, sign(signer, "first")).accepted());
        assertTrue(verify(signer, sign(signer, "second")).accepted());
    }
}
