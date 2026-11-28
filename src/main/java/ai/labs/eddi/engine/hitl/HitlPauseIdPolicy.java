/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Whether a human HITL decision must name the pause it was made for
 * ({@link HitlDecision#getPauseId()}).
 * <p>
 * A decision without a {@code pauseId} applies to whatever pause is current.
 * After a resume and a re-pause that is a request the decider never saw — the
 * tool name and arguments on their card belong to the previous one. Naming the
 * pause turns that into a 409; requiring it closes the remaining case, a client
 * that simply leaves it out.
 * <p>
 * Off by default (the behaviour before this existed). Switched on per agent by
 * {@code hitlConfig.toolApprovals.requirePauseId} (tool-call pauses), or for
 * the whole deployment by {@value #PROPERTY}. Engine decisions —
 * {@code system:timeout}, {@code system:no-progress}, {@code system:group} —
 * are exempt: they are not cards a person read, and a timeout schedule armed
 * before pause ids existed carries none.
 *
 * @since 6.7.0
 */
public final class HitlPauseIdPolicy {

    /** Deployment-wide switch. */
    public static final String PROPERTY = "eddi.hitl.require-pause-id";

    /**
     * {@code decidedBy} prefix of the engine's own decisions (a reserved
     * principal).
     */
    private static final String SYSTEM_PREFIX = "system:";

    private HitlPauseIdPolicy() {
    }

    /** Whether {@value #PROPERTY} is on. False when no config can be read. */
    public static boolean requiredDeploymentWide() {
        try {
            return ConfigProvider.getConfig().getOptionalValue(PROPERTY, Boolean.class).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether {@code decision} must be refused: the pause requires a
     * {@code pauseId}, the decision is a person's, and it names none.
     */
    public static boolean refuses(HitlDecision decision, boolean required) {
        if (!required || decision == null) {
            return false;
        }
        String decidedBy = decision.getDecidedBy();
        if (decidedBy != null && decidedBy.startsWith(SYSTEM_PREFIX)) {
            return false;
        }
        return decision.getPauseId() == null || decision.getPauseId().isBlank();
    }

    /** The message of the refusal, the same on every surface. */
    public static String message() {
        return "This decision must name the pause it was made for (pauseId from approval-status): "
                + "a decision without one would apply to whatever request is pending now";
    }
}
