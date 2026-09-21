/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;

/**
 * Supplies a live OAuth access token for one connection and principal.
 * <p>
 * An interface so {@link ConnectionResolver} carries no OAuth machinery. That
 * is not future-proofing for its own sake: the resolver runs on every outbound
 * request of every turn, and everything it depends on it drags into every unit
 * test of every call site. The token service owns the grant store, the refresh
 * lease and an HTTP client; the resolver owns none of them.
 * <p>
 * Implementations must never return null, never return an expired token, and
 * never throw anything but {@link ConnectionException} — a caller
 * distinguishing "reconnect required" from "the provider blipped" does so by
 * {@link ConnectionException#getReason()}, not by exception type.
 */
public interface AccessTokenSupplier {

    /**
     * @param connection
     *            an {@code OAUTH2_*} connection
     * @param principal
     *            a user id, or {@link ConnectionResolver#SERVICE_PRINCIPAL}
     * @return a live access token
     * @throws ConnectionException
     *             when no usable grant exists or the token endpoint refuses
     */
    String accessToken(ConnectionConfiguration connection, String principal);
}
