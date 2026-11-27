/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.gdpr.ErasedSubjects;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionRecorded;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Projects every recorded HITL decision into the approval-decision store, off
 * the decision's critical path ({@code @ObservesAsync} on
 * {@link HitlDecisionRecorded}). Idempotent: a unit is keyed by its decision id
 * and position, so the event and a later rebuild from the ledger store it once.
 * A failed write is logged and counted; a rebuild repairs it.
 * <p>
 * <b>Erased identities.</b> A row carries two identities, the conversation
 * owner ({@code userId}) and the decider ({@code decidedBy}). A ledger entry
 * names a decider that erasure does not rewrite, so for a decision made no
 * later than that person's erasure ({@link ErasedSubjects}) the row must hold
 * what erasure leaves in a stored row
 * ({@link IApprovalDecisionStore#eraseUser}): no {@code userId}, the ledger's
 * pseudonym as decider.
 * <p>
 * <b>The stored state is always the safe one.</b> A row with an identity is
 * inserted <em>masked</em> (no {@code userId}, decider pseudonymised) and
 * <em>pending</em>, which hides it from every read. Then it is settled
 * ({@link #settle}): a fresh check says which identities may be kept; they are
 * written back with a compare-and-set on this row only, still pending; a second
 * fresh check confirms them, or reverts the row (again by compare-and-set) when
 * an erasure was recorded in between; only then is the pending mark cleared.
 * Erasure records the person before it pseudonymises the stored rows, so a raw
 * identity written back before the record is reached by erasure's own pass, and
 * one written back after it is seen by the confirming check.
 * <p>
 * A failure or a crash at any step leaves the row pending, hidden from reads,
 * and in the masked state unless it failed between writing an identity back and
 * confirming it. {@link ApprovalBackfillService#reconcilePending} settles
 * pending rows again from the ledger, hourly and after every rebuild.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ApprovalDecisionProjector {

    private static final Logger LOGGER = Logger.getLogger(ApprovalDecisionProjector.class);

    private final IApprovalDecisionStore store;
    private final OversightMetrics metrics;
    private final Instance<AutonomyService> autonomy;
    private final ErasedSubjects erasedSubjects;

    @Inject
    public ApprovalDecisionProjector(IApprovalDecisionStore store, OversightMetrics metrics, Instance<AutonomyService> autonomy,
            ErasedSubjects erasedSubjects) {
        this.store = store;
        this.metrics = metrics;
        this.autonomy = autonomy;
        this.erasedSubjects = erasedSubjects;
    }

    /** Without the erased-subject record, for tests that do not exercise it. */
    ApprovalDecisionProjector(IApprovalDecisionStore store, OversightMetrics metrics, Instance<AutonomyService> autonomy) {
        this(store, metrics, autonomy, null);
    }

    void onDecisionRecorded(@ObservesAsync HitlDecisionRecorded event) {
        if (event != null) {
            project(event.units(), true);
        }
    }

    /**
     * Stores the units of one decision.
     *
     * @param evaluate
     *            whether newly stored rejections are handed to earned autonomy
     *            (false for a rebuild of history)
     * @return how many rows were new (a row left pending counts: it is stored)
     */
    public int project(List<HitlDecisionUnit> units, boolean evaluate) {
        if (units == null || units.isEmpty()) {
            return 0;
        }
        int stored = 0;
        List<ApprovalDecision> settled = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            HitlDecisionUnit unit = units.get(i);
            if (unit == null || unit.decisionId() == null || unit.decisionOutcome() == null) {
                continue;
            }
            try {
                ApprovalDecision row = ApprovalDecision.of(unit, i);
                if (erasedSubjects == null || masked(row).equals(row)) {
                    if (!store.insertIfAbsent(row)) {
                        continue;
                    }
                    stored++;
                    settled.add(row);
                    metrics.decisionProjected(row.surface(), row.pauseType(), row.latencyMs());
                    continue;
                }
                ApprovalDecision safe = masked(row);
                if (!store.insertPendingIfAbsent(safe)) {
                    continue;
                }
                stored++;
                metrics.decisionProjected(row.surface(), row.pauseType(), row.latencyMs());
                try {
                    ApprovalDecision now = settle(row, safe, IApprovalDecisionStore.INITIAL_VERSION);
                    if (now != null) {
                        settled.add(now);
                    }
                } catch (RuntimeException e) {
                    metrics.projectionFailed();
                    LOGGER.warnf("Approval decision %s#%d is stored but not yet checked against the erased-subject record; it stays "
                            + "hidden until maintenance settles it: %s", sanitize(unit.decisionId()), i, e.getMessage());
                }
            } catch (RuntimeException e) {
                metrics.projectionFailed();
                LOGGER.warnf("Could not store approval decision %s#%d for analytics (a rebuild repairs it): %s", sanitize(unit.decisionId()),
                        i, e.getMessage());
            }
        }
        if (evaluate) {
            evaluate(settled);
        }
        return stored;
    }

    /**
     * Hands settled rows to earned autonomy, whose demote-coded rejections reset
     * the evidence window. Idempotent, so a row handed over twice is harmless.
     */
    void evaluate(List<ApprovalDecision> settled) {
        if (settled.isEmpty() || autonomy == null || !autonomy.isResolvable()) {
            return;
        }
        try {
            autonomy.get().onDecisions(settled);
        } catch (RuntimeException e) {
            LOGGER.warnf("Earned autonomy could not evaluate decision %s: %s", sanitize(settled.getFirst().decisionId()), e.getMessage());
        }
    }

    /**
     * Settles a pending row: decides from fresh reads of the erased-subject record
     * which identities of {@code raw} it may hold, writes them back and confirms
     * them, each step a compare-and-set on this row alone.
     *
     * @param raw
     *            the row as the ledger has it
     * @param current
     *            the row as it is stored now
     * @param version
     *            its settlement version, which every compare-and-set is bound to
     *            (an erasure increments it, so a stale settler cannot match a row
     *            erasure has rewritten, even to the same values)
     * @return the row as finally stored, or null when another writer changed it
     *         first (it stays pending and is settled again later)
     * @throws RuntimeException
     *             the record or the store could not be read or written; the row
     *             stays pending
     */
    ApprovalDecision settle(ApprovalDecision raw, ApprovalDecision current, long version) {
        long expected = version;
        ApprovalDecision allowed = withoutErasedIdentities(raw);
        if (!sameIdentities(allowed, current)) {
            if (!store.updateIdentities(current.id(), expected, allowed.userId(), allowed.decidedBy(), true)) {
                return null;
            }
            expected++;
        }
        // Confirm with a second fresh read: an erasure recorded since the first one
        // reverts what was written back. An erasure's own pass bumped the version, so
        // this write then loses and the row stays pending.
        ApprovalDecision confirmed = withoutErasedIdentities(raw);
        if (!store.updateIdentities(current.id(), expected, confirmed.userId(), confirmed.decidedBy(), false)) {
            return null;
        }
        return confirmed;
    }

    /**
     * Settles a pending row whose ledger entry is gone or no longer names anyone
     * (redacted): it keeps the masked identities, and the pending mark is cleared.
     *
     * @return the row as settled, or null when another writer changed it first
     */
    ApprovalDecision settleMasked(ApprovalDecision current, long version) {
        ApprovalDecision safe = masked(current);
        return store.updateIdentities(current.id(), version, safe.userId(), safe.decidedBy(), false) ? safe : null;
    }

    /** Up to {@code limit} rows still pending, with their versions. */
    List<IApprovalDecisionStore.Pending> pendingRows(int limit) {
        return store.pendingCheck(limit);
    }

    /**
     * The row with every personal identity replaced as erasure would replace it: no
     * {@code userId}, the ledger's pseudonym as decider. Identities that are not a
     * person ({@code system:*}, {@code unknown}, existing pseudonyms) stay.
     */
    ApprovalDecision masked(ApprovalDecision row) {
        if (erasedSubjects == null) {
            return row;
        }
        String userId = ErasedSubjects.isPersonalIdentity(row.userId()) ? null : row.userId();
        String decidedBy = ErasedSubjects.isPersonalIdentity(row.decidedBy()) ? erasedSubjects.pseudonymOf(row.decidedBy()) : row.decidedBy();
        return Objects.equals(userId, row.userId()) && Objects.equals(decidedBy, row.decidedBy())
                ? row
                : row.withPersonalFields(userId, decidedBy);
    }

    /**
     * The row as erasure would have left it, for each of its identities erased at
     * or after the decision.
     */
    ApprovalDecision withoutErasedIdentities(ApprovalDecision row) {
        if (erasedSubjects == null) {
            return row;
        }
        Instant at = row.decidedAt() != null ? row.decidedAt() : row.pausedAt();
        String userId = row.userId();
        String decidedBy = row.decidedBy();
        boolean changed = false;
        if (userId != null && erasedSubjects.covers(userId, at)) {
            userId = null;
            changed = true;
        }
        if (decidedBy != null && erasedSubjects.covers(decidedBy, at)) {
            decidedBy = erasedSubjects.pseudonymOf(decidedBy);
            changed = true;
        }
        return changed ? row.withPersonalFields(userId, decidedBy) : row;
    }

    private static boolean sameIdentities(ApprovalDecision a, ApprovalDecision b) {
        return Objects.equals(a.userId(), b.userId()) && Objects.equals(a.decidedBy(), b.decidedBy());
    }
}
