/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack.hitl;

import java.time.Duration;

/**
 * Shared retention default for both {@link ISlackApprovalRecordStore}
 * implementations, so a missing, zero or negative setting behaves the same on
 * MongoDB and PostgreSQL.
 */
final class SlackApprovalRecordRetention {

    static final Duration DEFAULT = Duration.ofDays(30);

    private SlackApprovalRecordRetention() {
    }

    static Duration sanitize(Duration configured) {
        return configured == null || configured.isNegative() || configured.isZero() ? DEFAULT : configured;
    }
}
