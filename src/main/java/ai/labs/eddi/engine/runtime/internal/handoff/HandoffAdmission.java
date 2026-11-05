/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.handoff;

import ai.labs.eddi.configs.groups.model.HandoffPolicies.IntakeMode;

/**
 * Extension seam on the single choke point every cross-team handoff passes: the
 * tool and the {@code onDecision} rules both end in
 * {@link TeamHandoffService#file}, so one implementation sees both.
 * <p>
 * <b>Empty by default.</b> The service looks implementations up through a CDI
 * {@code Instance}; with none registered the step is a no-op. Intended
 * implementers: a guardrail that screens or redacts the text before it crosses
 * a team boundary, and a budget gate that refuses intake when the receiving
 * team's spend is exhausted.
 * <p>
 * <b>Fails closed.</b> An implementation that throws counts as a refusal, so a
 * broken screen can never silently become an open door. Implementations run
 * after the consent, loop and cap checks, on a request the receiving team has
 * already agreed to accept, and must be fast and side-effect free: they may run
 * once per attempt, and a refused attempt is not retried.
 */
public interface HandoffAdmission {

    /**
     * @param request
     *            the request, with the engine-derived source and the resolved
     *            target
     * @param subject
     *            the cleaned subject about to be filed
     * @param description
     *            the cleaned description about to be filed
     * @param mode
     *            how the target will take it (AUTO or REVIEW)
     */
    Decision check(HandoffRequest request, String subject, String description, IntakeMode mode);

    /**
     * Admit, refuse, or admit with replaced text.
     *
     * @param admitted
     *            whether the handoff may proceed
     * @param reason
     *            for a refusal: one sentence that goes to the model and the audit
     *            entry
     * @param subject
     *            replacement subject (a redaction), {@code null} to keep it
     * @param description
     *            replacement description, {@code null} to keep it
     */
    record Decision(boolean admitted, String reason, String subject, String description) {

        public static Decision admit() {
            return new Decision(true, null, null, null);
        }

        public static Decision refuse(String reason) {
            return new Decision(false, reason, null, null);
        }

        /** Admit, but file this text instead. */
        public static Decision redact(String subject, String description) {
            return new Decision(true, null, subject, description);
        }
    }
}
