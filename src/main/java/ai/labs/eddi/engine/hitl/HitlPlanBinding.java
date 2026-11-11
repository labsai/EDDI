/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import org.jboss.logging.Logger;

import java.util.function.BooleanSupplier;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The one rule for whether a HITL decision may apply to a pause's plan — shared
 * by the conversation resume and the group approve so the two surfaces cannot
 * drift.
 * <p>
 * <table>
 * <caption>Outcomes</caption>
 * <tr>
 * <th>Pause</th>
 * <th>Decision</th>
 * <th>Outcome</th>
 * </tr>
 * <tr>
 * <td>has a plan hash</td>
 * <td>carries the same hash</td>
 * <td>{@link Outcome#MATCHED}</td>
 * </tr>
 * <tr>
 * <td>has a plan hash</td>
 * <td>carries a different hash</td>
 * <td>refused: {@link PlanMismatch} (a conflict)</td>
 * </tr>
 * <tr>
 * <td>has a plan hash</td>
 * <td>carries none, made by the system ({@code system:*})</td>
 * <td>{@link Outcome#SYSTEM} — a timeout or group auto-decision acts on the
 * pause it read itself</td>
 * </tr>
 * <tr>
 * <td>has a plan hash</td>
 * <td>carries none, strict binding off</td>
 * <td>{@link Outcome#NOT_PRESENTED}, logged — a client that predates plan
 * hashing keeps working</td>
 * </tr>
 * <tr>
 * <td>has a plan hash</td>
 * <td>carries none, strict binding on</td>
 * <td>refused: {@link PlanHashRequired} (a 400 — the pause is not
 * consumed)</td>
 * </tr>
 * <tr>
 * <td>has none (committed before plan hashing)</td>
 * <td>carries none</td>
 * <td>{@link Outcome#LEGACY_UNHASHED}, logged — accepted as before, also in
 * strict mode, since nothing can be bound to a plan that was never hashed</td>
 * </tr>
 * <tr>
 * <td>has none</td>
 * <td>carries a hash</td>
 * <td>refused: {@link PlanMismatch} — whatever the client hashed, it is not
 * this pause</td>
 * </tr>
 * <tr>
 * <td>{@link HitlPlanHash#UNAVAILABLE} (hashing failed at commit)</td>
 * <td>carries a hash</td>
 * <td>refused: {@link PlanMismatch} — no plan was ever hashed to match</td>
 * </tr>
 * <tr>
 * <td>{@link HitlPlanHash#UNAVAILABLE}</td>
 * <td>carries none, made by the system ({@code system:*})</td>
 * <td>{@link Outcome#SYSTEM}, as for a hashed pause</td>
 * </tr>
 * <tr>
 * <td>{@link HitlPlanHash#UNAVAILABLE}</td>
 * <td>carries none, strict binding on</td>
 * <td>refused: {@link PlanMismatch} — fail closed; no client can supply a
 * binding, so retrying cannot help</td>
 * </tr>
 * <tr>
 * <td>{@link HitlPlanHash#UNAVAILABLE}</td>
 * <td>carries none, strict binding off</td>
 * <td>{@link Outcome#PLAN_HASH_UNAVAILABLE}, logged at WARN — applied without
 * plan binding</td>
 * </tr>
 * </table>
 */
public final class HitlPlanBinding {

    private static final Logger LOGGER = Logger.getLogger(HitlPlanBinding.class);

    /** Property that turns strict plan binding on for the whole deployment. */
    public static final String STRICT_PROPERTY = "eddi.hitl.plan-hash.strict";

    /** How a decision related to the pause's plan — recorded in the audit entry. */
    public enum Outcome {
        /** The decision carried the pause's plan hash. */
        MATCHED,
        /** The pause has a plan hash; the decision carried none (non-strict). */
        NOT_PRESENTED,
        /** The pause has a plan hash; an automated decision carried none. */
        SYSTEM,
        /** The pause was committed before plan hashing existed. */
        LEGACY_UNHASHED,
        /**
         * The pause's plan hash could not be computed when it was committed
         * ({@link HitlPlanHash#UNAVAILABLE}); applied without binding (non-strict).
         */
        PLAN_HASH_UNAVAILABLE
    }

    /**
     * Strict binding refused a human decision that carried no plan hash — a
     * malformed request for this pause (400), not a conflict. Its message names the
     * field, never a value.
     */
    public static final class PlanHashRequired extends IllegalArgumentException {
        public PlanHashRequired(String message) {
            super(message);
        }
    }

    /**
     * A decision for a different plan, or a stored plan that no longer verifies.
     */
    public static final class PlanMismatch extends RuntimeException {
        public PlanMismatch(String message) {
            super(message);
        }
    }

    private HitlPlanBinding() {
    }

    /**
     * Checks a decision against the plan hash its pause was committed with.
     *
     * @param subject
     *            what the pause belongs to, for the log (a conversation or group
     *            conversation id)
     * @param storedPlanHash
     *            the pause's stored plan hash; null for a pause committed before
     *            plan hashing, {@link HitlPlanHash#UNAVAILABLE} for one whose hash
     *            could not be computed
     * @param strict
     *            whether a human decision must carry the hash — asked only when
     *            that is the question (it may read configuration)
     * @throws PlanMismatch
     *             when the decision names a different plan
     * @throws PlanHashRequired
     *             when strict binding requires a hash the decision does not carry
     */
    public static Outcome check(String subject, HitlDecision decision, String storedPlanHash, BooleanSupplier strict) {
        boolean presented = decision != null && decision.namesPlan();
        if (storedPlanHash == null || storedPlanHash.isBlank()) {
            if (presented) {
                LOGGER.infof("Refusing a HITL decision for %s: it names a plan, but the pause has no plan hash", sanitize(subject));
                throw new PlanMismatch("The decision names a plan hash, but the pending approval has none — re-read the "
                        + "approval status and decide again.");
            }
            LOGGER.infof("hitl.plan.legacy: deciding the pause of %s without plan binding — it was committed before plan hashing",
                    sanitize(subject));
            return Outcome.LEGACY_UNHASHED;
        }
        if (HitlPlanHash.isUnavailable(storedPlanHash)) {
            return checkUnavailable(subject, decision, presented, strict);
        }
        if (presented) {
            if (!HitlPlanHash.matches(storedPlanHash, decision.getPlanHash())) {
                LOGGER.infof("Refusing a HITL decision for %s: it names a plan other than the pending one", sanitize(subject));
                throw new PlanMismatch("The pending approval's plan is not the one this decision was made for — re-read the "
                        + "approval status and review the current plan before deciding again.");
            }
            return Outcome.MATCHED;
        }
        if (isSystem(decision)) {
            return Outcome.SYSTEM;
        }
        if (strict.getAsBoolean()) {
            throw new PlanHashRequired("This approval requires 'planHash' (from the approval status) so the decision "
                    + "binds to the exact plan under review; the pause was not consumed.");
        }
        LOGGER.warnf("hitl.plan.not_presented: a decision for %s carried no planHash; applied without plan binding "
                + "(set %s=true to require it)", sanitize(subject), STRICT_PROPERTY);
        return Outcome.NOT_PRESENTED;
    }

    /**
     * A pause whose plan hash could not be computed at commit. Unlike a legacy
     * pause it was committed by a version that binds plans, so strict binding fails
     * closed on it: there is no plan a decision could be bound to.
     */
    private static Outcome checkUnavailable(String subject, HitlDecision decision, boolean presented, BooleanSupplier strict) {
        if (presented) {
            LOGGER.infof("Refusing a HITL decision for %s: it names a plan, but the pause's plan hash could not be computed",
                    sanitize(subject));
            throw new PlanMismatch("The decision names a plan hash, but the pending approval has none — re-read the "
                    + "approval status and decide again.");
        }
        if (isSystem(decision)) {
            return Outcome.SYSTEM;
        }
        if (strict.getAsBoolean()) {
            LOGGER.warnf("hitl.plan.unavailable: refusing a decision for %s under strict plan binding — the pause's plan hash "
                    + "could not be computed when it was committed", sanitize(subject));
            throw new PlanMismatch("The pending approval could not be bound to a plan hash when it paused, and plan binding "
                    + "is required — it cannot be decided; nothing was run. Cancel the conversation or contact an "
                    + "administrator.");
        }
        LOGGER.warnf("hitl.plan.unavailable: deciding the pause of %s without plan binding — its plan hash could not be "
                + "computed when it was committed (set %s=true to refuse such decisions)", sanitize(subject), STRICT_PROPERTY);
        return Outcome.PLAN_HASH_UNAVAILABLE;
    }

    /**
     * Re-derives nothing itself: compares a stored plan hash with one re-derived
     * from the plan about to execute. A pause without a stored hash verifies
     * trivially (legacy), and so does one marked {@link HitlPlanHash#UNAVAILABLE}:
     * there is nothing to compare, and {@link #check} has already refused it under
     * strict binding.
     *
     * @return whether the plan about to execute is the committed one
     */
    public static boolean verifies(String storedPlanHash, String rederived) {
        if (storedPlanHash == null || storedPlanHash.isBlank() || HitlPlanHash.isUnavailable(storedPlanHash)) {
            return true;
        }
        return HitlPlanHash.matches(storedPlanHash, rederived);
    }

    private static boolean isSystem(HitlDecision decision) {
        return decision != null && decision.getDecidedBy() != null && decision.getDecidedBy().startsWith("system:");
    }

    /**
     * Validates the shape of a presented plan hash before anything is loaded: an
     * over-long value is a malformed request, not a mismatch.
     *
     * @throws IllegalArgumentException
     *             when the value is longer than {@link HitlPlanHash#MAX_LENGTH}
     */
    public static void requireWellFormed(HitlDecision decision) {
        if (decision != null && decision.getPlanHash() != null && decision.getPlanHash().length() > HitlPlanHash.MAX_LENGTH) {
            throw new IllegalArgumentException("planHash must be at most " + HitlPlanHash.MAX_LENGTH + " characters");
        }
    }
}
