/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.engine.spendcontrol.SpendControlChangeService;
import ai.labs.eddi.engine.spendcontrol.SpendControlChangeService.EntryKey;
import ai.labs.eddi.engine.spendcontrol.SpendControlChangeService.Outcome;
import ai.labs.eddi.engine.spendcontrol.SpendControlException;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.readCallerJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;
import static ai.labs.eddi.engine.mcp.McpToolUtils.toolFailure;

/**
 * MCP tools for the deployment's spending controls — the model price catalog
 * and the tenant quota ({@code docs/spend-control-changes.md}) — for an
 * administrator's AI client.
 * <p>
 * <b>Stricter applies, looser waits for a human.</b> A write that only makes
 * spending stricter (a new or higher price under the {@code ALLOW} policy, a
 * stricter unpriced policy, a lower limit, enforcement switched on) is written
 * at once with the caller as actor. A write with any loosening part writes
 * nothing: {@link SpendControlChangeService} files it as a pending change that
 * an {@code eddi-admin} approves in the Manager. There is no tool to approve,
 * by design: an MCP client is often a model, and a prompt injection that
 * reaches one wants exactly a $0 price or a higher quota. The client may
 * withdraw its own pending change — that applies nothing.
 * <p>
 * Every tool is {@link McpRoles#ADMIN_ONLY}: the quota REST resource and the
 * catalog write are admin-only, and the reads here show a tenant's spend. Every
 * write is compare-and-set (catalog {@code revision}, quota
 * {@code fingerprint}), needs a reason and is written to the audit ledger.
 *
 * @since 6.8.0
 */
@ApplicationScoped
@McpErrorResults
public class McpSpendControlTools {

    private static final Logger LOGGER = Logger.getLogger(McpSpendControlTools.class);

    /** The largest JSON an entry argument may be — 50 entries fit comfortably. */
    static final int MAX_ENTRIES_JSON = 64 * 1024;
    static final String CHANNEL = "mcp";

    private final SpendControlChangeService service;
    private final TenantQuotaService quotaService;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpSpendControlTools(SpendControlChangeService service, TenantQuotaService quotaService, IJsonSerialization jsonSerialization,
            SecurityIdentity identity, @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.service = service;
        this.quotaService = quotaService;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "read_tenant_quota", description = "Administrators only. Read a tenant's quota — maxConversationsPerDay, "
            + "maxAgentsPerTenant, maxApiCallsPerMinute, maxMonthlyCostUsd (-1 = unlimited) and whether it is enabled — with its "
            + "fingerprint (pass it to update_tenant_quota), the current usage (conversations today, API calls this minute, this "
            + "month's cost and reserved USD) and the remaining monthly budget. Without tenantId: the deployment's default tenant.")
    public String readTenantQuota(@ToolArg(description = "Tenant id (default: the deployment's tenant)", required = false) String tenantId) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        try {
            String id = service.tenant(tenantId);
            TenantQuota stored = service.readQuota(id);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tenantId", id);
            result.put("configured", stored != null);
            result.put("quota", stored != null ? stored : TenantQuota.unlimited(id));
            result.put("fingerprint", SpendControlChangeService.quotaFingerprint(stored));
            result.put("usage", quotaService.getUsage(id));
            var remaining = quotaService.remainingCostUsd(id);
            result.put("remainingMonthlyCostUsd", remaining.isPresent() ? remaining.getAsDouble() : null);
            result.put("pendingChanges", service.list(SpendControlChange.Status.PENDING, SpendControlChangeService.MAX_PENDING).stream()
                    .filter(c -> c.kind() == SpendControlChange.Kind.TENANT_QUOTA && id.equals(c.tenantId())).map(SpendControlChange::id).toList());
            return jsonSerialization.serialize(result);
        } catch (SpendControlException e) {
            return refusal(e);
        } catch (Exception e) {
            return toolFailure(LOGGER, "read_tenant_quota", e);
        }
    }

    @Tool(name = "update_model_prices", description = "Administrators only. Add, change or remove entries of the model price catalog, "
            + "or set its unpricedPolicy, in one compare-and-set write: give expectedRevision from read_model_prices. A change that "
            + "only makes spending stricter is applied at once (result APPLIED): a higher price, a stricter unpricedPolicy, or a new "
            + "entry under the ALLOW policy that costs at least as much as every entry that may price the same models. Anything that "
            + "could lower what a call costs or admit a refused call — a lower or cleared price, a removed entry, a looser policy, a "
            + "new price while unpriced calls are refused — is NOT applied: it is filed for an administrator's approval in the Manager "
            + "(result PROPOSED, with the changeId and why it loosens). Lowering a price is raising a budget: propose it only when "
            + "the person you act for asked for it. Each upserted entry needs a source link (http/https) to the provider's price page.")
    public String updateModelPrices(@ToolArg(description = "The catalog revision you read (required)") Long expectedRevision,
                                    @ToolArg(description = "JSON array of entries to add or replace by provider+model, at most 50: "
                                            + "[{\"provider\":\"openai\",\"model\":\"gpt-4o-mini*\",\"inputPricePer1M\":0.15,"
                                            + "\"outputPricePer1M\":0.6,\"asOf\":\"2026-11-01\",\"source\":\"https://...\"}]",
                                             required = false) String upsertEntries,
                                    @ToolArg(description = "JSON array of entries to remove, at most 50: [{\"provider\":\"openai\","
                                            + "\"model\":\"gpt-4o\"}]",
                                             required = false) String removeEntries,
                                    @ToolArg(description = "ALLOW, REFUSE_WHEN_BUDGETED or REFUSE (default: keep)",
                                             required = false) String unpricedPolicy,
                                    @ToolArg(description = "Why (required, at most 1000 characters; kept in the audit ledger)") String reason) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        if (expectedRevision == null) {
            return errorJson("expectedRevision is required: read the catalog first (read_model_prices)", SpendControlException.BAD_REQUEST, null);
        }
        try {
            List<ModelPriceEntry> upserts = upsertEntries == null || upsertEntries.isBlank()
                    ? List.of()
                    : Arrays.asList(parseJson("upsertEntries", upsertEntries, ModelPriceEntry[].class));
            List<EntryKey> removals = removeEntries == null || removeEntries.isBlank()
                    ? List.of()
                    : Arrays.asList(parseJson("removeEntries", removeEntries, EntryKey[].class));
            UnpricedPolicy policy = parsePolicy(unpricedPolicy);
            Outcome outcome = service.changeModelPrices(expectedRevision, upserts, removals, policy, reason, SpendControlChangeService.name(identity),
                    CHANNEL);
            return jsonSerialization.serialize(describe(outcome));
        } catch (SpendControlException e) {
            return refusal(e);
        } catch (IllegalArgumentException e) {
            return errorJson(e.getMessage(), SpendControlException.BAD_REQUEST, null);
        } catch (Exception e) {
            return toolFailure(LOGGER, "update_model_prices", e);
        }
    }

    @Tool(name = "update_tenant_quota", description = "Administrators only. Change a tenant's quota: give only the fields to change "
            + "(-1 = unlimited) and expectedFingerprint from read_tenant_quota. Lowering a limit or enabling the quota is applied at "
            + "once (result APPLIED). Raising a limit, making it unlimited or disabling the quota is NOT applied: it is filed for an "
            + "administrator's approval in the Manager (result PROPOSED). A change with both parts is proposed as a whole. Raising a "
            + "budget is spending the user's money: propose it only when the person you act for asked for this amount.")
    public String updateTenantQuota(@ToolArg(description = "Tenant id (default: the deployment's tenant)", required = false) String tenantId,
                                    @ToolArg(description = "The quota fingerprint you read (required)") String expectedFingerprint,
                                    @ToolArg(description = "Daily conversation limit, -1 = unlimited",
                                             required = false) Integer maxConversationsPerDay,
                                    @ToolArg(description = "Distinct-agent limit, -1 = unlimited", required = false) Integer maxAgentsPerTenant,
                                    @ToolArg(description = "Per-minute API call limit, -1 = unlimited",
                                             required = false) Integer maxApiCallsPerMinute,
                                    @ToolArg(description = "Monthly cost budget in USD, -1 = unlimited", required = false) Double maxMonthlyCostUsd,
                                    @ToolArg(description = "Whether the quota is enforced", required = false) Boolean enabled,
                                    @ToolArg(description = "Why (required, at most 1000 characters; kept in the audit ledger)") String reason) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        try {
            Outcome outcome = service.changeTenantQuota(tenantId, expectedFingerprint, maxConversationsPerDay, maxAgentsPerTenant,
                    maxApiCallsPerMinute, maxMonthlyCostUsd, enabled, reason, SpendControlChangeService.name(identity), CHANNEL);
            Map<String, Object> result = describe(outcome);
            if (outcome.result() == SpendControlChangeService.Result.APPLIED && outcome.applied() instanceof TenantQuota quota) {
                result.put("fingerprint", SpendControlChangeService.quotaFingerprint(quota));
            }
            return jsonSerialization.serialize(result);
        } catch (SpendControlException e) {
            return refusal(e);
        } catch (Exception e) {
            return toolFailure(LOGGER, "update_tenant_quota", e);
        }
    }

    @Tool(name = "list_spend_control_changes", description = "Administrators only. List price and quota changes filed for approval, "
            + "newest first: what each changes, which part loosens spending, who proposed it and why, and its status — PENDING "
            + "(waits for an administrator in the Manager), APPLIED, REJECTED, WITHDRAWN, STALE (the catalog or quota changed before "
            + "approval; nothing applied) or EXPIRED (7 days undecided). Approving is a human action in the Manager; no tool approves.")
    public String listSpendControlChanges(@ToolArg(description = "PENDING (default), APPLIED, REJECTED, WITHDRAWN, STALE, EXPIRED or ALL",
                                                   required = false) String status,
                                          @ToolArg(description = "At most this many, 1..100 (default 20)", required = false) Integer limit) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        try {
            SpendControlChange.Status filter = status == null || status.isBlank()
                    ? SpendControlChange.Status.PENDING
                    : SpendControlChangeService.parseStatus(status);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (SpendControlChange change : service.list(filter, limit)) {
                rows.add(summary(change));
            }
            return jsonSerialization.serialize(Map.of("changes", rows));
        } catch (SpendControlException e) {
            return refusal(e);
        } catch (Exception e) {
            return toolFailure(LOGGER, "list_spend_control_changes", e);
        }
    }

    @Tool(name = "withdraw_spend_control_change", description = "Administrators only. Withdraw a pending price or quota change you "
            + "proposed yourself — nothing is applied. Someone else's change cannot be withdrawn here.")
    public String withdrawSpendControlChange(@ToolArg(description = "The changeId (required)") String changeId,
                                             @ToolArg(description = "Why (required)") String reason) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        try {
            return jsonSerialization.serialize(summary(service.withdraw(changeId, reason, SpendControlChangeService.name(identity))));
        } catch (SpendControlException e) {
            return refusal(e);
        } catch (Exception e) {
            return toolFailure(LOGGER, "withdraw_spend_control_change", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private <T> T parseJson(String argument, String json, Class<T> type) throws Exception {
        if (json.length() > MAX_ENTRIES_JSON) {
            throw new IllegalArgumentException(argument + " is larger than " + MAX_ENTRIES_JSON / 1024 + " KB");
        }
        T parsed = readCallerJson(argument, () -> jsonSerialization.deserialize(json, type));
        if (parsed == null) {
            throw new IllegalArgumentException(argument + " must be a JSON array");
        }
        return parsed;
    }

    private static UnpricedPolicy parsePolicy(String policy) {
        if (policy == null || policy.isBlank()) {
            return null;
        }
        try {
            return UnpricedPolicy.valueOf(policy.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unpricedPolicy must be ALLOW, REFUSE_WHEN_BUDGETED or REFUSE");
        }
    }

    private static Map<String, Object> describe(Outcome outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", outcome.result().name());
        result.put("changes", outcome.changes());
        switch (outcome.result()) {
            case APPLIED -> {
                result.put("applied", outcome.applied());
                result.put("note", "Applied now: every change makes spending stricter.");
            }
            case PROPOSED -> {
                result.put("loosening", outcome.loosening());
                result.put("change", summary(outcome.proposal()));
                result.put("note", "Nothing was applied. An administrator must approve this change in the Manager "
                        + "(Model Prices or Quotas, Pending changes). Tell the person you act for; do not retry.");
            }
            case UNCHANGED -> result.put("note", "Nothing differs from what is stored.");
        }
        return result;
    }

    private static Map<String, Object> summary(SpendControlChange change) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("changeId", change.id());
        row.put("kind", change.kind().name());
        row.put("status", change.status().name());
        if (change.tenantId() != null) {
            row.put("tenantId", change.tenantId());
        }
        if (change.baseRevision() != null) {
            row.put("baseRevision", change.baseRevision());
        }
        row.put("changes", change.changes());
        row.put("loosening", change.loosening());
        row.put("reason", change.reason());
        row.put("proposedBy", change.proposedBy());
        row.put("proposedAt", Instant.ofEpochMilli(change.proposedAtMillis()).toString());
        row.put("expiresAt", Instant.ofEpochMilli(change.expiresAtMillis()).toString());
        if (change.decidedBy() != null) {
            row.put("decidedBy", change.decidedBy());
        }
        if (change.outcome() != null) {
            row.put("outcome", change.outcome());
        }
        return row;
    }

    private String refusal(SpendControlException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("errorCode", e.code());
        body.putAll(e.details());
        try {
            return jsonSerialization.serialize(body);
        } catch (Exception serialization) {
            return errorJson(e.getMessage(), e.code(), null);
        }
    }
}
