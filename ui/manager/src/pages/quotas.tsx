import { useState, useCallback, useEffect } from "react";
import { useOnboarding } from "@/hooks/use-onboarding";
import { useTranslation } from "react-i18next";
import {
  SlidersHorizontal,
  Activity,
  RefreshCw,
  Save,
  ToggleLeft,
  ToggleRight,
  MessageSquare,
  Bot,
  Zap,
  DollarSign,
  Info,
} from "lucide-react";
import { useQuota, useQuotaUsage, useUpdateQuota, useResetUsage } from "@/hooks/use-quotas";
import type { TenantQuota } from "@/lib/api/quotas";

const DEFAULT_TENANT = "default";

export function QuotasPage() {
  const { t } = useTranslation();
  const { data: quota, isLoading: quotaLoading } = useQuota(DEFAULT_TENANT);
  const { data: usage, isLoading: usageLoading } = useQuotaUsage(DEFAULT_TENANT);
  const updateMutation = useUpdateQuota();
  const resetMutation = useResetUsage();

  // Local form state — initialized from server data
  const [form, setForm] = useState<TenantQuota | null>(null);
  const [dirty, setDirty] = useState(false);

  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => { const t = setTimeout(() => maybeAutoStart("quotas"), 500); return () => clearTimeout(t); }, [maybeAutoStart]);

  // Sync form with server data when it arrives (or after save)
  useEffect(() => {
    if (quota && !dirty) {
      setForm(quota);
    }
  }, [quota, dirty]);

  const handleChange = useCallback(
    (field: keyof TenantQuota, value: number | boolean) => {
      setForm((prev) => (prev ? { ...prev, [field]: value } : prev));
      setDirty(true);
    },
    [],
  );

  const handleSave = useCallback(() => {
    if (!form) return;
    updateMutation.mutate(
      { tenantId: DEFAULT_TENANT, quota: form },
      {
        onSuccess: (data) => {
          setForm(data);
          setDirty(false);
        },
      },
    );
  }, [form, updateMutation]);

  const handleReset = useCallback(() => {
    resetMutation.mutate(DEFAULT_TENANT);
  }, [resetMutation]);

  const loading = quotaLoading || usageLoading;

  return (
    <div className="space-y-6" data-testid="quotas-page">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10">
            <SlidersHorizontal className="h-5 w-5 text-primary" />
          </div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-foreground">
              {t("quotas.title", "Tenant Quotas")}
            </h1>
            <p className="text-sm text-muted-foreground">
              {t("quotas.description", "Configure rate limits, usage caps, and cost budgets per tenant.")}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {dirty && (
            <span className="text-xs font-medium text-amber-500" data-testid="quotas-dirty-indicator">
              {t("editor.dirty", "Unsaved changes")}
            </span>
          )}
          <button
            data-testid="quotas-save"
            onClick={handleSave}
            disabled={!dirty || updateMutation.isPending}
            className="inline-flex items-center gap-2 rounded-lg bg-sidebar-accent px-4 py-2 text-sm font-medium text-white shadow-sm transition-all hover:bg-sidebar-accent/90 disabled:opacity-50"
          >
            <Save className="h-4 w-4" />
            {updateMutation.isPending ? t("editor.saving", "Saving...") : t("editor.save", "Save")}
          </button>
        </div>
      </div>

      {loading ? (
        <LoadingSkeleton />
      ) : (
        <>
          {/* Enforcement disabled banner */}
          {form && !form.enabled && (
            <div
              className="flex items-start gap-3 rounded-lg border border-amber-500/30 bg-amber-500/5 px-4 py-3"
              data-testid="quotas-disabled-banner"
            >
              <Info className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
              <div className="text-sm text-muted-foreground">
                <span className="font-medium text-amber-500">
                  {t("quotas.enforcementOff", "Enforcement is off.")}
                </span>{" "}
                {t("quotas.enforcementOffHint", "Quotas are not being enforced. Enable enforcement and save to start applying limits.")}
              </div>
            </div>
          )}

          <div className="grid gap-6 lg:grid-cols-2">
            {/* Quota Configuration Card */}
            <div className="rounded-xl border border-border bg-card p-6 shadow-sm" data-tour="quotas-config">
              <div className="mb-5 flex items-center justify-between">
                <h2 className="text-lg font-semibold text-foreground">
                  {t("quotas.configuration", "Configuration")}
                </h2>
                {form && (
                  <button
                    data-testid="quotas-toggle-enabled"
                    onClick={() => handleChange("enabled", !form.enabled)}
                    className="flex items-center gap-2 text-sm font-medium transition-colors"
                    title={form.enabled ? t("quotas.disable", "Disable") : t("quotas.enable", "Enable")}
                  >
                    {form.enabled ? (
                      <ToggleRight className="h-6 w-6 text-emerald-500" />
                    ) : (
                      <ToggleLeft className="h-6 w-6 text-muted-foreground" />
                    )}
                    <span className={form.enabled ? "text-emerald-500" : "text-muted-foreground"}>
                      {form.enabled ? t("quotas.enabled", "Enabled") : t("quotas.disabled", "Disabled")}
                    </span>
                  </button>
                )}
              </div>

              {form && (
                <div className="space-y-4">
                  <QuotaField
                    icon={<MessageSquare className="h-4 w-4 text-muted-foreground" />}
                    label={t("quotas.maxConversationsPerDay", "Max Conversations / Day")}
                    value={form.maxConversationsPerDay}
                    onChange={(v) => handleChange("maxConversationsPerDay", v)}
                    hint={t("quotas.limitHint", "-1 = unlimited")}
                    testId="quota-max-conversations"
                    dimmed={!form.enabled}
                  />
                  <QuotaField
                    icon={<Bot className="h-4 w-4 text-muted-foreground" />}
                    label={t("quotas.maxAgentsPerTenant", "Max Agents / Tenant")}
                    value={form.maxAgentsPerTenant}
                    onChange={(v) => handleChange("maxAgentsPerTenant", v)}
                    hint={t("quotas.limitHint", "-1 = unlimited")}
                    testId="quota-max-agents"
                    dimmed={!form.enabled}
                  />
                  <QuotaField
                    icon={<Zap className="h-4 w-4 text-muted-foreground" />}
                    label={t("quotas.maxApiCallsPerMinute", "Max API Calls / Minute")}
                    value={form.maxApiCallsPerMinute}
                    onChange={(v) => handleChange("maxApiCallsPerMinute", v)}
                    hint={t("quotas.limitHint", "-1 = unlimited")}
                    testId="quota-max-api-calls"
                    dimmed={!form.enabled}
                  />
                  <QuotaField
                    icon={<DollarSign className="h-4 w-4 text-muted-foreground" />}
                    label={t("quotas.maxMonthlyCostUsd", "Max Monthly Cost (USD)")}
                    value={form.maxMonthlyCostUsd}
                    onChange={(v) => handleChange("maxMonthlyCostUsd", v)}
                    hint={t("quotas.limitHint", "-1 = unlimited")}
                    testId="quota-max-cost"
                    step={0.01}
                    dimmed={!form.enabled}
                  />
                </div>
              )}
            </div>

            {/* Usage Card */}
            <div className="rounded-xl border border-border bg-card p-6 shadow-sm" data-tour="quotas-usage">
              <div className="mb-5 flex items-center justify-between">
                <h2 className="text-lg font-semibold text-foreground">
                  <Activity className="me-2 inline-block h-5 w-5 text-muted-foreground" />
                  {t("quotas.liveUsage", "Live Usage")}
                </h2>
                <button
                  data-testid="quotas-reset-usage"
                  onClick={handleReset}
                  disabled={resetMutation.isPending}
                  className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:opacity-50"
                >
                  <RefreshCw className={`h-3.5 w-3.5 ${resetMutation.isPending ? "animate-spin" : ""}`} />
                  {t("quotas.resetCounters", "Reset Counters")}
                </button>
              </div>

              {usage && (
                <div className="grid gap-3 sm:grid-cols-2">
                  <UsageCard
                    label={t("quotas.conversationsToday", "Conversations Today")}
                    value={usage.conversationsToday}
                    limit={form?.maxConversationsPerDay ?? -1}
                    testId="usage-conversations"
                  />
                  <UsageCard
                    label={t("quotas.apiCallsMinute", "API Calls / Minute")}
                    value={usage.apiCallsThisMinute}
                    limit={form?.maxApiCallsPerMinute ?? -1}
                    testId="usage-api-calls"
                  />
                  <UsageCard
                    label={t("quotas.monthlyCost", "Monthly Cost")}
                    value={usage.monthlyCostUsd}
                    limit={form?.maxMonthlyCostUsd ?? -1}
                    prefix="$"
                    testId="usage-cost"
                  />
                  <div className="flex flex-col justify-center rounded-lg border border-border/50 bg-muted/30 p-3">
                    <span className="text-xs text-muted-foreground">
                      {t("quotas.tenantId", "Tenant ID")}
                    </span>
                    <span className="text-sm font-mono font-medium text-foreground" data-testid="usage-tenant-id">
                      {usage.tenantId}
                    </span>
                  </div>
                </div>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/* ─── Sub-components ─── */

function LoadingSkeleton() {
  return (
    <div className="grid gap-6 lg:grid-cols-2" data-testid="quotas-loading">
      {[0, 1].map((i) => (
        <div key={i} className="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div className="mb-5 h-6 w-32 animate-pulse rounded bg-muted" />
          <div className="space-y-4">
            {[0, 1, 2, 3].map((j) => (
              <div key={j} className="space-y-2">
                <div className="h-4 w-40 animate-pulse rounded bg-muted" />
                <div className="h-9 w-full animate-pulse rounded-lg bg-muted" />
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function QuotaField({
  icon,
  label,
  value,
  onChange,
  hint,
  testId,
  step = 1,
  dimmed = false,
}: {
  icon: React.ReactNode;
  label: string;
  value: number;
  onChange: (v: number) => void;
  hint: string;
  testId: string;
  step?: number;
  dimmed?: boolean;
}) {
  return (
    <div className={dimmed ? "opacity-60 transition-opacity" : "transition-opacity"}>
      <label className="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
        {icon}
        {label}
      </label>
      <div className="flex items-center gap-2">
        <input
          data-testid={testId}
          type="number"
          value={value}
          step={step}
          onChange={(e) => onChange(Number(e.target.value))}
          className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground shadow-sm transition-colors focus:border-sidebar-accent focus:outline-none focus:ring-1 focus:ring-sidebar-accent"
        />
      </div>
      <p className="mt-0.5 text-xs text-muted-foreground">{hint}</p>
    </div>
  );
}

function UsageCard({
  label,
  value,
  limit,
  prefix = "",
  testId,
}: {
  label: string;
  value: number;
  limit: number;
  prefix?: string;
  testId: string;
}) {
  const isUnlimited = limit < 0;
  const ratio = !isUnlimited && limit > 0 ? value / limit : 0;
  const barColor =
    ratio >= 0.9 ? "bg-red-500" : ratio >= 0.7 ? "bg-amber-500" : "bg-emerald-500";

  return (
    <div className="rounded-lg border border-border/50 bg-muted/30 p-3" data-testid={testId}>
      <span className="text-xs text-muted-foreground">{label}</span>
      <div className="mt-1 flex items-baseline gap-1">
        <span className="text-xl font-bold text-foreground">
          {prefix}{typeof value === "number" && value % 1 !== 0 ? value.toFixed(2) : value}
        </span>
        <span className="text-xs text-muted-foreground">
          / {isUnlimited ? "∞" : `${prefix}${limit}`}
        </span>
      </div>
      {!isUnlimited && (
        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
          <div
            className={`h-full rounded-full transition-all ${barColor}`}
            style={{ width: `${Math.min(ratio * 100, 100)}%` }}
          />
        </div>
      )}
    </div>
  );
}
