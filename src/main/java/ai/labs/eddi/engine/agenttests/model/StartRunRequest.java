/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests.model;

/**
 * Body of {@code POST /agenttests/runs}.
 *
 * @param suiteId
 *            the suite to run (required)
 * @param suiteVersion
 *            absent: the suite's current version
 * @param agentId
 *            the agent under test (required)
 * @param agentVersion
 *            absent: the agent's current version; it is deployed to the
 *            {@code test} environment if it is not already
 * @param keepConversations
 *            keep the test conversations for inspection instead of deleting
 *            them
 * @since 6.5.0
 */
public record StartRunRequest(String suiteId, Integer suiteVersion, String agentId, Integer agentVersion, Boolean keepConversations) {
}
