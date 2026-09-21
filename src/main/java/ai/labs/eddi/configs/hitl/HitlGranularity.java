/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

/**
 * At what level a group discussion pauses for human approval: after each gated
 * phase ({@code PHASE}), or per task inside a gated EXECUTE phase
 * ({@code TASK}). Group surface only.
 *
 * @since 6.0.0
 */
public enum HitlGranularity {
    PHASE, TASK
}
