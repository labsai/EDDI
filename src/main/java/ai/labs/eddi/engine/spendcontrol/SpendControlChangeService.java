/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol;

import ai.labs.eddi.engine.pricing.ModelPriceCatalogException;
import ai.labs.eddi.engine.pricing.ModelPriceCatalogService;
import ai.labs.eddi.engine.pricing.ModelPriceCatalogValidator;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange.Kind;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange.Status;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.utils.LogSanitizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes to the deployment's spending controls — the model price catalog and
 * the tenant quotas — for clients that may be a model, and the human approval
 * that the loosening ones need ({@code docs/spend-control-changes.md}).
 * <p>
 * <b>The split.</b> {@link SpendControlDiff} classifies a write. A write that
 * only makes spending stricter is applied at once with the caller as actor. A
 * write with any loosening part is not applied at all: it is stored as a
 * {@link Status#PENDING} {@link SpendControlChange} that an {@code eddi-admin}
 * approves or rejects over REST (the Manager). The approver is the actor of the
 * write; an approval by anyone else applies nothing.
 * <p>
 * <b>Why a human.</b> The MCP client of an administrator is often a model, and
 * a prompt injection can steer a model. Lowering a price to $0 or raising a
 * quota is raising a budget — exactly what an injected instruction wants. A
 * tightening write is safe to automate: the worst it does is refuse spend.
 * <p>
 * Every write is compare-and-set — on the catalog {@code revision}, on the
 * quota's {@link #quotaFingerprint fingerprint} — requires a reason, and is
 * written to the audit ledger.
 */
@ApplicationScoped
public class SpendControlChangeService {

    private static final Logger LOGGER = Logger.getLogger(SpendControlChangeService.class);

    static final String ADMIN_ROLE = "eddi-admin";

    /**
     * Undecided proposals at once; a further one is refused until one is decided.
     */
    public static final int MAX_PENDING = 20;
    /** An undecided proposal expires after this. */
    public static final Duration PENDING_TTL = Duration.ofDays(7);
    /** Decided proposals are kept this long (the audit ledger keeps the record). */
    public static final Duration RETENTION = Duration.ofDays(90);
    public static final int MAX_REASON = 1000;
    public static final int MAX_ENTRIES_PER_WRITE = 50;
    public static final int MAX_LIST = 100;
    /**
     * A quota limit above this is refused as a typo, not taken as "almost
     * unlimited".
     */
    public static final double MAX_COST_LIMIT_USD = 1_000_000_000d;

    private static final Pattern TENANT_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final String NO_QUOTA = "none";

    /** What a write did. */
    public enum Result {
        /** Stricter only: written. */
        APPLIED,
        /** Loosens: filed for approval, nothing written. */
        PROPOSED,
        /** Nothing differs. */
        UNCHANGED
    }

    /**
     * @param result
     *            what happened
     * @param applied
     *            the stored catalog or quota after an {@link Result#APPLIED} write
     * @param proposal
     *            the pending change after a {@link Result#PROPOSED} write
     */
    public record Outcome(Result result, Object applied, SpendControlChange proposal, List<String> changes, List<String> loosening) {
    }

    /** A catalog entry addressed by its key. */
    public record EntryKey(String provider, String model) {
    }

    private final ISpendControlChangeStore store;
    private final ModelPriceCatalogService catalogService;
    private final TenantQuotaService quotaService;
    private final SpendControlAudit audit;
    private final MeterRegistry meterRegistry;
    private final boolean authEnabled;
    private final Clock clock;

    @Inject
    public SpendControlChangeService(ISpendControlChangeStore store, ModelPriceCatalogService catalogService, TenantQuotaService quotaService,
            SpendControlAudit audit, MeterRegistry meterRegistry,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this(store, catalogService, quotaService, audit, meterRegistry, authEnabled, Clock.systemUTC());
    }

    SpendControlChangeService(ISpendControlChangeStore store, ModelPriceCatalogService catalogService, TenantQuotaService quotaService,
            SpendControlAudit audit, MeterRegistry meterRegistry, boolean authEnabled, Clock clock) {
        this.store = store;
        this.catalogService = catalogService;
        this.quotaService = quotaService;
        this.audit = audit;
        this.meterRegistry = meterRegistry;
        this.authEnabled = authEnabled;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ catalog

    /**
     * Upserts and removes catalog entries and optionally sets the unpriced policy.
     *
     * @param expectedRevision
     *            the revision the caller read
     * @param upserts
     *            entries to add or replace (by provider and model); each needs a
     *            {@code source} link
     * @param removals
     *            entries to remove
     * @param policy
     *            the new unpriced policy, or null to keep it
     */
    public Outcome changeModelPrices(long expectedRevision, List<ModelPriceEntry> upserts, List<EntryKey> removals, UnpricedPolicy policy,
                                     String reason, String actor, String channel) {
        String why = requireReason(reason);
        List<ModelPriceEntry> adds = upserts == null ? List.of() : upserts;
        List<EntryKey> drops = removals == null ? List.of() : removals;
        if (adds.isEmpty() && drops.isEmpty() && policy == null) {
            throw bad("Give entries to upsert, entries to remove, or an unpricedPolicy");
        }
        if (adds.size() > MAX_ENTRIES_PER_WRITE || drops.size() > MAX_ENTRIES_PER_WRITE) {
            throw bad("At most " + MAX_ENTRIES_PER_WRITE + " entries to upsert and " + MAX_ENTRIES_PER_WRITE + " to remove per call");
        }
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < adds.size(); i++) {
            ModelPriceEntry e = adds.get(i);
            if (e == null) {
                violations.add("upsertEntries[" + i + "] is empty");
                continue;
            }
            String source = e.source() == null ? "" : e.source().trim().toLowerCase(Locale.ROOT);
            if (!(source.startsWith("https://") || source.startsWith("http://"))) {
                violations.add("upsertEntries[" + i + "].source must be the http(s) link the price was taken from");
            }
        }
        if (!violations.isEmpty()) {
            throw new SpendControlException(400, SpendControlException.BAD_REQUEST, "The price change was rejected",
                    Map.of("violations", violations));
        }

        ModelPriceCatalog current = catalogService.read();
        if (current.revision() != expectedRevision) {
            throw new SpendControlException(409, SpendControlException.CONFLICT,
                    "The price catalog is at revision " + current.revision() + ", not " + expectedRevision + "; read it again",
                    Map.of("currentRevision", current.revision()));
        }
        Map<String, ModelPriceEntry> entries = new LinkedHashMap<>();
        for (ModelPriceEntry e : current.entries()) {
            entries.put(SpendControlDiff.key(e.provider(), e.model()), e);
        }
        for (EntryKey drop : drops) {
            if (drop == null || entries.remove(SpendControlDiff.key(drop.provider(), drop.model())) == null) {
                throw bad("No catalog entry " + (drop == null ? "null" : drop.provider() + "/" + drop.model()) + " to remove");
            }
        }
        for (ModelPriceEntry add : adds) {
            entries.put(SpendControlDiff.key(add.provider(), add.model()), add);
        }
        ModelPriceCatalog next = new ModelPriceCatalog(current.revision(), policy != null ? policy : current.unpricedPolicy(), current.updatedAt(),
                current.updatedBy(), new ArrayList<>(entries.values()));
        List<String> invalid = ModelPriceCatalogValidator.validate(next, catalogService.knownProviders());
        if (!invalid.isEmpty()) {
            throw new SpendControlException(400, SpendControlException.BAD_REQUEST, "The price catalog was rejected", Map.of("violations", invalid));
        }

        SpendControlDiff.Result diff = SpendControlDiff.compareCatalogs(current, next, catalogService.snapshot().effectivePolicy());
        if (diff.isEmpty()) {
            return new Outcome(Result.UNCHANGED, current, null, List.of(), List.of());
        }
        if (!diff.loosens()) {
            ModelPriceCatalog stored = replaceCatalog(expectedRevision, next, actor);
            Map<String, Object> details = details(Kind.MODEL_PRICES, null, why, diff, channel);
            details.put("revision", stored.revision());
            audit.record(SpendControlAudit.APPLIED, actor, details);
            count(Kind.MODEL_PRICES, Result.APPLIED);
            LOGGER.infof("Model price catalog revision %d written by %s over %s (stricter only): %s", stored.revision(), sanitize(actor),
                    sanitize(channel), sanitize(String.join("; ", diff.changes())));
            return new Outcome(Result.APPLIED, stored, null, diff.changes(), List.of());
        }
        SpendControlChange proposal = propose(Kind.MODEL_PRICES, null, current.revision(), null, next, null, diff, why, actor, channel);
        return new Outcome(Result.PROPOSED, null, proposal, diff.changes(), diff.loosening());
    }

    private ModelPriceCatalog replaceCatalog(long expectedRevision, ModelPriceCatalog next, String actor) {
        try {
            return catalogService.replace(expectedRevision, next, actor);
        } catch (ModelPriceCatalogException e) {
            Map<String, Object> details = new LinkedHashMap<>();
            if (!e.violations().isEmpty()) {
                details.put("violations", e.violations());
            }
            if (e.currentRevision() != null) {
                details.put("currentRevision", e.currentRevision());
            }
            boolean conflict = e.status() == ModelPriceCatalogException.CONFLICT;
            throw new SpendControlException(e.status(), conflict ? SpendControlException.CONFLICT : SpendControlException.BAD_REQUEST, e.getMessage(),
                    details);
        }
    }

    // ------------------------------------------------------------------ quota

    /**
     * The stored quota of a tenant (null: none), the tenant id defaulted and
     * checked.
     */
    public TenantQuota readQuota(String tenantId) {
        return quotaService.readStoredQuota(tenant(tenantId));
    }

    /** The tenant id, defaulted to the deployment's tenant and checked. */
    public String tenant(String tenantId) {
        String id = tenantId == null || tenantId.isBlank() ? quotaService.getDefaultTenantId() : tenantId.trim();
        if (id == null || !TENANT_ID.matcher(id).matches()) {
            throw bad("tenantId must be 1-128 characters of A-Z a-z 0-9 . _ -");
        }
        return id;
    }

    /**
     * Changes a tenant's quota. A null field keeps its value; {@code -1} is
     * unlimited.
     *
     * @param expectedFingerprint
     *            the {@link #quotaFingerprint} of the quota the caller read
     */
    public Outcome changeTenantQuota(String tenantId, String expectedFingerprint, Integer maxConversationsPerDay, Integer maxAgentsPerTenant,
                                     Integer maxApiCallsPerMinute, Double maxMonthlyCostUsd, Boolean enabled, String reason, String actor,
                                     String channel) {
        String why = requireReason(reason);
        String id = tenant(tenantId);
        if (maxConversationsPerDay == null && maxAgentsPerTenant == null && maxApiCallsPerMinute == null && maxMonthlyCostUsd == null
                && enabled == null) {
            throw bad("Give at least one limit or enabled");
        }
        requireLimit("maxConversationsPerDay", maxConversationsPerDay);
        requireLimit("maxAgentsPerTenant", maxAgentsPerTenant);
        requireLimit("maxApiCallsPerMinute", maxApiCallsPerMinute);
        if (maxMonthlyCostUsd != null && (Double.isNaN(maxMonthlyCostUsd) || Double.isInfinite(maxMonthlyCostUsd)
                || (maxMonthlyCostUsd != -1 && maxMonthlyCostUsd < 0) || maxMonthlyCostUsd > MAX_COST_LIMIT_USD)) {
            throw bad("maxMonthlyCostUsd must be -1 (unlimited) or between 0 and " + (long) MAX_COST_LIMIT_USD);
        }
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            throw bad("expectedFingerprint is required: read the quota first (read_tenant_quota)");
        }
        TenantQuota stored = quotaService.readStoredQuota(id);
        String fingerprint = quotaFingerprint(stored);
        if (!fingerprint.equals(expectedFingerprint.trim())) {
            throw new SpendControlException(409, SpendControlException.CONFLICT, "The quota of tenant '" + id + "' changed since it was read",
                    Map.of("currentFingerprint", fingerprint));
        }
        TenantQuota base = stored != null ? stored : TenantQuota.unlimited(id);
        TenantQuota next = new TenantQuota(id, maxConversationsPerDay != null ? maxConversationsPerDay : base.maxConversationsPerDay(),
                maxAgentsPerTenant != null ? maxAgentsPerTenant : base.maxAgentsPerTenant(),
                maxApiCallsPerMinute != null ? maxApiCallsPerMinute : base.maxApiCallsPerMinute(),
                maxMonthlyCostUsd != null ? maxMonthlyCostUsd : base.maxMonthlyCostUsd(), enabled != null ? enabled : base.enabled());

        SpendControlDiff.Result diff = SpendControlDiff.compareQuotas(base, next);
        if (diff.isEmpty()) {
            return new Outcome(Result.UNCHANGED, stored, null, List.of(), List.of());
        }
        if (!diff.loosens()) {
            if (!quotaService.compareAndSetQuota(stored, next)) {
                throw new SpendControlException(409, SpendControlException.CONFLICT, "The quota of tenant '" + id + "' changed concurrently",
                        Map.of("currentFingerprint", quotaFingerprint(quotaService.readStoredQuota(id))));
            }
            audit.record(SpendControlAudit.APPLIED, actor, details(Kind.TENANT_QUOTA, id, why, diff, channel));
            count(Kind.TENANT_QUOTA, Result.APPLIED);
            LOGGER.infof("Quota of tenant '%s' written by %s over %s (stricter only): %s", sanitize(id), sanitize(actor), sanitize(channel),
                    sanitize(String.join("; ", diff.changes())));
            return new Outcome(Result.APPLIED, next, null, diff.changes(), List.of());
        }
        SpendControlChange proposal = propose(Kind.TENANT_QUOTA, id, null, stored, null, next, diff, why, actor, channel);
        return new Outcome(Result.PROPOSED, null, proposal, diff.changes(), diff.loosening());
    }

    /** A short hash of a stored quota — {@code none} when no quota is stored. */
    public static String quotaFingerprint(TenantQuota quota) {
        return quota == null ? NO_QUOTA : sha256(SpendControlChangeJson.canonical(quota)).substring(0, 16);
    }

    private static void requireLimit(String field, Integer value) {
        if (value != null && value < -1) {
            throw bad(field + " must be -1 (unlimited) or >= 0, was " + value);
        }
    }

    // ------------------------------------------------------------------ proposals

    private SpendControlChange propose(Kind kind, String tenantId, Long baseRevision, TenantQuota baseQuota, ModelPriceCatalog proposedCatalog,
                                       TenantQuota proposedQuota, SpendControlDiff.Result diff, String reason, String actor, String channel) {
        long now = clock.millis();
        expireDue(now);
        String hash = sha256(kind + "|" + tenantId + "|" + baseRevision + "|" + SpendControlChangeJson.canonical(baseQuota) + "|"
                + SpendControlChangeJson.canonical(proposedCatalog) + "|" + SpendControlChangeJson.canonical(proposedQuota));
        for (SpendControlChange pending : store.list(Status.PENDING, MAX_PENDING)) {
            if (hash.equals(pending.contentHash())) {
                return pending;
            }
        }
        if (store.count(Status.PENDING) >= MAX_PENDING) {
            throw new SpendControlException(429, SpendControlException.TOO_MANY_PENDING,
                    MAX_PENDING + " spend-control changes already wait for approval; have an administrator decide them in the Manager first");
        }
        try {
            store.deleteDecidedBefore(now - RETENTION.toMillis());
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not prune decided spend-control changes: %s", sanitize(e.getMessage()));
        }
        SpendControlChange change = new SpendControlChange(UUID.randomUUID().toString(), kind, Status.PENDING, tenantId, baseRevision, baseQuota,
                proposedCatalog, proposedQuota, diff.changes(), diff.loosening(), reason, actor, channel, now, now + PENDING_TTL.toMillis(), null,
                null, null, null, hash);
        store.create(change);
        Map<String, Object> details = details(kind, tenantId, reason, diff, channel);
        details.put("changeId", change.id());
        audit.record(SpendControlAudit.PROPOSED, actor, details);
        count(kind, Result.PROPOSED);
        LOGGER.warnf("Spend-control change %s (%s) proposed by %s over %s waits for an eddi-admin's approval in the Manager: %s", change.id(),
                kind, sanitize(actor), sanitize(channel), sanitize(String.join("; ", diff.loosening())));
        return change;
    }

    /** Changes newest first, expiring those past their time. */
    public List<SpendControlChange> list(Status status, Integer limit) {
        expireDue(clock.millis());
        int n = limit == null ? 20 : Math.max(1, Math.min(MAX_LIST, limit));
        return store.list(status, n);
    }

    /** One change. */
    public SpendControlChange read(String id) {
        SpendControlChange change = load(id);
        if (change.expiredAt(clock.millis())) {
            return expire(change);
        }
        return change;
    }

    /**
     * Approves a pending change and applies it, with the approver as actor.
     *
     * @throws SpendControlException
     *             FORBIDDEN unless the approver is an {@code eddi-admin};
     *             ALREADY_DECIDED; STALE when the control changed since the
     *             proposal (then nothing is applied and the change is marked stale)
     */
    public SpendControlChange approve(String id, String note, SecurityIdentity approver) {
        String by = requireAdmin(approver, "approve");
        String decisionNote = note(note);
        SpendControlChange change = requirePending(id);
        SpendControlChange claimed = change.decided(Status.APPLIED, by, clock.millis(), decisionNote, "applying");
        if (!store.compareAndSetStatus(change.id(), Status.PENDING, claimed)) {
            throw alreadyDecided(load(id));
        }
        String outcome;
        try {
            outcome = apply(change, by);
        } catch (SpendControlException e) {
            if (!SpendControlException.STALE.equals(e.code())) {
                // put it back: an unexpected failure must not burn the proposal
                store.compareAndSetStatus(change.id(), Status.APPLIED, change);
                throw e;
            }
            SpendControlChange stale = change.decided(Status.STALE, by, clock.millis(), decisionNote, e.getMessage());
            store.compareAndSetStatus(change.id(), Status.APPLIED, stale);
            Map<String, Object> details = decisionDetails(change, decisionNote);
            details.put("outcome", e.getMessage());
            audit.record(SpendControlAudit.STALE, by, details);
            count(change.kind(), "STALE");
            throw e;
        } catch (RuntimeException e) {
            store.compareAndSetStatus(change.id(), Status.APPLIED, change);
            throw e;
        }
        SpendControlChange applied = change.decided(Status.APPLIED, by, clock.millis(), decisionNote, outcome);
        store.compareAndSetStatus(change.id(), Status.APPLIED, applied);
        Map<String, Object> details = decisionDetails(change, decisionNote);
        details.put("outcome", outcome);
        audit.record(SpendControlAudit.APPROVED, by, details);
        count(change.kind(), "APPROVED");
        LOGGER.infof("Spend-control change %s approved by %s and applied: %s", change.id(), sanitize(by), outcome);
        return applied;
    }

    private String apply(SpendControlChange change, String by) {
        if (change.kind() == Kind.MODEL_PRICES) {
            try {
                ModelPriceCatalog stored = catalogService.replace(change.baseRevision(), change.proposedCatalog(), by);
                return "applied as price catalog revision " + stored.revision();
            } catch (ModelPriceCatalogException e) {
                String why = e.status() == ModelPriceCatalogException.CONFLICT
                        ? "the price catalog changed since revision " + change.baseRevision() + "; nothing was applied — propose it again"
                        : "the proposed catalog is no longer valid (" + String.join("; ", e.violations()) + "); nothing was applied";
                throw new SpendControlException(409, SpendControlException.STALE, why);
            }
        }
        if (!quotaService.compareAndSetQuota(change.baseQuota(), change.proposedQuota())) {
            throw new SpendControlException(409, SpendControlException.STALE,
                    "the quota of tenant '" + change.tenantId() + "' changed since the proposal; nothing was applied — propose it again");
        }
        return "applied to the quota of tenant '" + change.tenantId() + "'";
    }

    /** Rejects a pending change: nothing is applied. An {@code eddi-admin} only. */
    public SpendControlChange reject(String id, String note, SecurityIdentity decider) {
        String by = requireAdmin(decider, "reject");
        String decisionNote = note(note);
        SpendControlChange change = requirePending(id);
        SpendControlChange rejected = change.decided(Status.REJECTED, by, clock.millis(), decisionNote, "rejected; nothing was applied");
        if (!store.compareAndSetStatus(change.id(), Status.PENDING, rejected)) {
            throw alreadyDecided(load(id));
        }
        audit.record(SpendControlAudit.REJECTED, by, decisionDetails(change, decisionNote));
        count(change.kind(), "REJECTED");
        return rejected;
    }

    /**
     * Withdraws a pending change its own proposer filed — nothing is applied, so
     * this is safe for an MCP client. Someone else's change answers NOT_FOUND.
     */
    public SpendControlChange withdraw(String id, String reason, String actor) {
        String why = requireReason(reason);
        SpendControlChange change = requirePending(id);
        if (actor == null || !actor.equals(change.proposedBy())) {
            throw new SpendControlException(404, SpendControlException.NOT_FOUND, "No pending spend-control change " + id + " of yours");
        }
        SpendControlChange withdrawn = change.decided(Status.WITHDRAWN, actor, clock.millis(), why, "withdrawn; nothing was applied");
        if (!store.compareAndSetStatus(change.id(), Status.PENDING, withdrawn)) {
            throw alreadyDecided(load(id));
        }
        audit.record(SpendControlAudit.WITHDRAWN, actor, decisionDetails(change, why));
        count(change.kind(), "WITHDRAWN");
        return withdrawn;
    }

    private SpendControlChange requirePending(String id) {
        SpendControlChange change = load(id);
        if (change.expiredAt(clock.millis())) {
            throw alreadyDecided(expire(change));
        }
        if (change.status() != Status.PENDING) {
            throw alreadyDecided(change);
        }
        return change;
    }

    private SpendControlChange load(String id) {
        if (id == null || id.isBlank() || id.length() > 64) {
            throw bad("A change id is required");
        }
        Optional<SpendControlChange> change = store.read(id.trim());
        return change.orElseThrow(() -> new SpendControlException(404, SpendControlException.NOT_FOUND, "No spend-control change " + id));
    }

    private void expireDue(long now) {
        try {
            for (SpendControlChange pending : store.list(Status.PENDING, MAX_PENDING * 2)) {
                if (pending.expiredAt(now)) {
                    expire(pending);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not expire spend-control changes: %s", sanitize(e.getMessage()));
        }
    }

    private SpendControlChange expire(SpendControlChange change) {
        SpendControlChange expired = change.decided(Status.EXPIRED, "system:expiry", clock.millis(), null,
                "expired after " + PENDING_TTL.toDays() + " days undecided; nothing was applied");
        if (store.compareAndSetStatus(change.id(), Status.PENDING, expired)) {
            count(change.kind(), "EXPIRED");
            return expired;
        }
        return store.read(change.id()).orElse(expired);
    }

    private static SpendControlException alreadyDecided(SpendControlChange change) {
        return new SpendControlException(409, SpendControlException.ALREADY_DECIDED,
                "Spend-control change " + change.id() + " is " + change.status() + ", not PENDING", Map.of("status", change.status().name()));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The decider's name, or FORBIDDEN. An approval by an {@code eddi-approver}, an
     * editor or the proposer's own non-admin identity must not widen a spending
     * control.
     */
    private String requireAdmin(SecurityIdentity identity, String verb) {
        if (!authEnabled) {
            return name(identity);
        }
        if (identity == null || identity.isAnonymous() || !identity.hasRole(ADMIN_ROLE)) {
            throw new SpendControlException(403, SpendControlException.FORBIDDEN,
                    "Only an eddi-admin may " + verb + " a spend-control change; nothing was applied");
        }
        return name(identity);
    }

    /** The principal's name, {@code anonymous} without one. */
    public static String name(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null || identity.getPrincipal().getName() == null
                || identity.getPrincipal().getName().isBlank()) {
            return "anonymous";
        }
        return identity.getPrincipal().getName();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw bad("reason is required");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON) {
            throw bad("reason must be at most " + MAX_REASON + " characters");
        }
        return trimmed;
    }

    private static String note(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.length() > MAX_REASON) {
            throw bad("note must be at most " + MAX_REASON + " characters");
        }
        return trimmed;
    }

    private static Map<String, Object> details(Kind kind, String tenantId, String reason, SpendControlDiff.Result diff, String channel) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind.name());
        if (tenantId != null) {
            details.put("tenantId", tenantId);
        }
        details.put("reason", reason);
        details.put("changes", diff.changes());
        details.put("loosening", diff.loosening());
        details.put("channel", channel);
        return details;
    }

    private static Map<String, Object> decisionDetails(SpendControlChange change, String note) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changeId", change.id());
        details.put("kind", change.kind().name());
        if (change.tenantId() != null) {
            details.put("tenantId", change.tenantId());
        }
        details.put("proposedBy", change.proposedBy());
        details.put("reason", change.reason());
        details.put("changes", change.changes());
        details.put("loosening", change.loosening());
        if (note != null) {
            details.put("note", note);
        }
        return details;
    }

    private void count(Kind kind, Object result) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.spendcontrol.changes", "kind", kind.name(), "result", String.valueOf(result)).increment();
        }
    }

    private static SpendControlException bad(String message) {
        return new SpendControlException(400, SpendControlException.BAD_REQUEST, message);
    }

    private static String sanitize(String s) {
        return LogSanitizer.sanitize(s);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The statuses an MCP or REST filter may name. */
    public static Status parseStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) {
            return null;
        }
        try {
            return Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("status must be one of " + Set.of(Status.values()) + " or ALL");
        }
    }
}
