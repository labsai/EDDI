/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api.model;

/**
 * The address this EDDI deployment can be reached at <em>from itself</em>,
 * which is what the Platform Operator's generated tools must target.
 *
 * @param baseUrl
 *            {@code scheme://host[:port]}, no trailing slash
 * @param source
 *            {@code configured} when {@code eddi.self.base-url} set it,
 *            {@code loopback} when it was derived from
 *            {@code quarkus.http.port} — so a caller can say which of the two
 *            it is about to provision rather than presenting a derived address
 *            as a deployment decision
 * @since 6.4.0
 */
public record OperatorSelfUrl(String baseUrl, String source) {
}
