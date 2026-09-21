/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

/**
 * What happens when a human does not decide a pending HITL approval within the
 * configured {@code approvalTimeout}. Shared by the regular (agent) and group
 * surfaces.
 *
 * @since 6.0.0
 */
public enum HitlTimeoutPolicy {
    WAIT_INDEFINITELY, AUTO_APPROVE, AUTO_REJECT, ABORT
}
