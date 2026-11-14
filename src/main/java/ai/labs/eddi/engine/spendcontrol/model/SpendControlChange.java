/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol.model;

import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A change to a deployment spending control — the model price catalog or a
 * tenant quota — that loosens it, filed by an MCP client and waiting for an
 * {@code eddi-admin} to approve it in the Manager
 * ({@code docs/spend-control-changes.md}).
 * <p>
 * The change carries the <em>whole</em> proposed document together with the
 * state it was computed against ({@code baseRevision} or {@code baseQuota}).
 * Approving it applies exactly that document, and only while the stored state
 * is still the base: anything that changed in between makes the proposal
 * {@link Status#STALE} instead of merging, so an approver never applies
 * something other than what was shown.
 *
 * @param id
 *            random id
 * @param kind
 *            which control
 * @param status
 *            where it stands
 * @param tenantId
 *            the tenant for a {@link Kind#TENANT_QUOTA} change, null otherwise
 * @param baseRevision
 *            the catalog revision the change was computed against
 * @param baseQuota
 *            the quota the change was computed against (null: none was stored)
 * @param proposedCatalog
 *            the catalog that approval writes
 * @param proposedQuota
 *            the quota that approval writes
 * @param changes
 *            every difference, in words
 * @param loosening
 *            the differences that loosen spending — why this needs approval
 * @param reason
 *            the proposer's reason
 * @param proposedBy
 *            the proposing principal
 * @param channel
 *            where it was proposed ({@code mcp})
 * @param proposedAtMillis
 *            when it was proposed
 * @param expiresAtMillis
 *            when an undecided proposal expires
 * @param decidedBy
 *            who approved, rejected or withdrew it
 * @param decidedAtMillis
 *            when
 * @param decisionNote
 *            the decider's note
 * @param outcome
 *            what happened, in words (also why a proposal became stale)
 * @param contentHash
 *            hash of kind, target, base and proposed document — a repeat of the
 *            same proposal returns the pending one
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SpendControlChange(String id, Kind kind, Status status, String tenantId, Long baseRevision, TenantQuota baseQuota,
        ModelPriceCatalog proposedCatalog, TenantQuota proposedQuota, List<String> changes, List<String> loosening, String reason,
        String proposedBy, String channel, long proposedAtMillis, long expiresAtMillis, String decidedBy, Long decidedAtMillis,
        String decisionNote, String outcome, String contentHash) {

    /** Which spending control a change is for. */
    public enum Kind {
        MODEL_PRICES, TENANT_QUOTA
    }

    /**
     * The life cycle: PENDING, then REJECTED, WITHDRAWN or EXPIRED — or, on
     * approval, APPLYING (claimed by compare-and-set before the write) and then
     * APPLIED, STALE or FAILED. A change still APPLYING after its approval returned
     * was interrupted mid-write: its outcome is unknown, and it is never applied
     * again.
     */
    public enum Status {
        PENDING, APPLYING, APPLIED, REJECTED, WITHDRAWN, STALE, FAILED, EXPIRED
    }

    public SpendControlChange {
        changes = changes == null ? List.of() : List.copyOf(changes);
        loosening = loosening == null ? List.of() : List.copyOf(loosening);
    }

    /** The same change, decided. */
    public SpendControlChange decided(Status newStatus, String by, long atMillis, String note, String outcomeText) {
        return new SpendControlChange(id, kind, newStatus, tenantId, baseRevision, baseQuota, proposedCatalog, proposedQuota, changes, loosening,
                reason, proposedBy, channel, proposedAtMillis, expiresAtMillis, by, atMillis, note, outcomeText, contentHash);
    }

    /** Whether it is pending and past its expiry. */
    public boolean expiredAt(long nowMillis) {
        return status == Status.PENDING && nowMillis >= expiresAtMillis;
    }
}
