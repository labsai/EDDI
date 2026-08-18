/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api.model;

/**
 * One synthetic tool call to classify against an agent's stored approval policy
 * — without executing anything.
 *
 * @param agentId
 *            the agent whose {@code hitlConfig.toolApprovals} is consulted
 * @param version
 *            pinned agent version; the classification must run against the
 *            exact document a conversation would pin, not "latest"
 * @param toolName
 *            dispatch name of the synthetic call, e.g. {@code patchDescriptor}
 * @param source
 *            tool source as the gate knows it ({@code http}, {@code mcp}, ...);
 *            null defaults to {@code http}
 * @param endpoint
 *            the {@code method:path} address for http tools, e.g.
 *            {@code patch:/descriptorstore/descriptors/{id}} — the form
 *            {@code ToolApprovalGate.addressesOf} combines with the source into
 *            {@code http.patch:/...}; null for non-http sources
 */
public record OperatorGateDryRunRequest(String agentId, Integer version, String toolName, String source, String endpoint) {
}
