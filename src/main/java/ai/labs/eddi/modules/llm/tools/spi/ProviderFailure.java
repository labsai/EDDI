/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

/**
 * One tool source that contributed nothing, and why (R2 step 1-2). Generalizes
 * {@code McpToolProviderManager.McpServerFailure} (server-scoped) to any
 * {@link ToolSourceProvider} — an empty {@link ToolContribution} alone cannot
 * tell "rejected as misconfigured" apart from "genuinely has nothing to
 * contribute this turn".
 *
 * @param source
 *            the reporting provider's {@link ToolSourceProvider#source()}
 * @param identifier
 *            what specifically failed (a server name, an endpoint URL, an agent
 *            id — provider-specific; never a secret)
 * @param kind
 *            failure classification
 * @param message
 *            human-readable reason
 */
public record ProviderFailure(String source, String identifier, Kind kind, String message) {

    public enum Kind {
        /** Rejected before any request was made — fix the config. */
        INVALID_CONFIGURATION,
        /** Reached, but the discovery call itself failed. */
        CONNECTION_FAILURE,
        /** Skipped because the circuit breaker is open for this source. */
        CIRCUIT_OPEN
    }
}
