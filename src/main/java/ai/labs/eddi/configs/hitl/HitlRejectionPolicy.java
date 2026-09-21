/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

/**
 * What happens to a task rejected by the human reviewer: fail it permanently
 * ({@code FAIL}) or reset it to ASSIGNED with the reviewer's feedback so the
 * agent retries ({@code RETRY}). Group surface only.
 *
 * @since 6.0.0
 */
public enum HitlRejectionPolicy {
    FAIL, RETRY
}
