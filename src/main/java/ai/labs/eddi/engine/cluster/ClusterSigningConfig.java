/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Settings of the signed cluster control plane (C4 of the cluster
 * production-readiness review): an HMAC key that every EDDI node holds and NATS
 * does not, with which node RPCs, their replies and cluster events are signed.
 * <p>
 * Kept apart from {@link ClusterConfig} on purpose: the key is a secret, it is
 * mounted from a Kubernetes Secret or a vault rather than the NATS credentials,
 * and whoever holds only the NATS credentials must not be able to produce a
 * message another node accepts.
 * <ul>
 * <li>{@code eddi.cluster.signing.key} — the current key (at least
 * {@value #MIN_KEY_LENGTH} characters). Unset: messages go out unsigned and a
 * WARN says so at boot — existing installs keep working.</li>
 * <li>{@code eddi.cluster.signing.previous-keys} — keys still accepted for
 * verification during a rotation (comma-separated).</li>
 * <li>{@code eddi.cluster.signing.required} — {@code false} (default): an
 * unsigned message is still accepted (counted and logged), which is what lets a
 * node of the previous version take part in a rolling upgrade. {@code true}:
 * unsigned messages are refused, and a node without a key refuses to
 * start.</li>
 * <li>{@code eddi.cluster.signing.max-age} — how old (or how far in the future)
 * a signed RPC may be; also the window in which a repeated nonce is refused.
 * Events use the event retention instead, because a reconnecting node
 * legitimately receives events that old.</li>
 * </ul>
 * Field-injected with defaults, like {@link ClusterConfig}; tests use
 * {@link #of}.
 */
@ApplicationScoped
public class ClusterSigningConfig {

    /**
     * The shortest key accepted: 32 characters, i.e. at least 128 bits of a random
     * token.
     */
    public static final int MIN_KEY_LENGTH = 32;

    @ConfigProperty(name = "eddi.cluster.signing.key")
    Optional<String> key = Optional.empty();

    @ConfigProperty(name = "eddi.cluster.signing.previous-keys")
    Optional<List<String>> previousKeys = Optional.empty();

    @ConfigProperty(name = "eddi.cluster.signing.required", defaultValue = "false")
    boolean required;

    @ConfigProperty(name = "eddi.cluster.signing.max-age", defaultValue = "60s")
    Duration maxAge = Duration.ofSeconds(60);

    public static ClusterSigningConfig of(String key, List<String> previousKeys, boolean required, Duration maxAge) {
        ClusterSigningConfig config = new ClusterSigningConfig();
        config.key = Optional.ofNullable(key).filter(k -> !k.isBlank());
        config.previousKeys = Optional.ofNullable(previousKeys);
        config.required = required;
        config.maxAge = maxAge;
        return config;
    }

    public Optional<String> key() {
        return key.map(String::trim).filter(k -> !k.isEmpty());
    }

    public List<String> previousKeys() {
        return previousKeys.orElse(List.of()).stream().map(String::trim).filter(k -> !k.isEmpty()).toList();
    }

    public boolean required() {
        return required;
    }

    public Duration maxAge() {
        return maxAge;
    }
}
