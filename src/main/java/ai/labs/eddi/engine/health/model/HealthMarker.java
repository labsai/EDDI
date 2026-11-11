/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.Instant;

/**
 * Something that changed and may explain a change in an agent's numbers — a
 * deployment, a global variable or prompt snippet edit, the first turn of a new
 * version. Shown on health charts.
 *
 * @param id
 *            unique id ({@code m-<uuid>} for stored markers,
 *            {@code vft-<version>} for the derived first-turn markers)
 * @param agentId
 *            the agent it concerns, or {@code null} for a change that concerns
 *            every agent (a global variable, a prompt snippet)
 * @param kind
 *            one of the {@code KIND_*} constants
 * @param label
 *            what happened, for display; never carries a value or any content
 * @param version
 *            the agent version it concerns, or {@code null}
 */
public record HealthMarker(String id, String agentId, Instant at, String kind, String label, Integer version) {

    public static final String KIND_DEPLOYMENT = "DEPLOYMENT";
    public static final String KIND_VERSION_FIRST_TURN = "VERSION_FIRST_TURN";
    public static final String KIND_SNIPPET_CHANGE = "SNIPPET_CHANGE";
    public static final String KIND_GLOBAL_VARIABLE_CHANGE = "GLOBAL_VARIABLE_CHANGE";
}
