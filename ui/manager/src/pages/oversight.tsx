import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router-dom";
import { Bot, ScanEye } from "lucide-react";
import { AgentPicker } from "@/components/shared/agent-picker";
import { EmptyState } from "@/components/shared/empty-state";
import { ApprovalAnalytics } from "@/components/oversight/approval-analytics";
import { AuditSampleQueue } from "@/components/oversight/audit-sample-queue";
import { AutonomyPanel } from "@/components/oversight/autonomy-panel";
import { useMayUse } from "@/hooks/use-auth";
import { useAutonomy, usePendingSampleCount } from "@/hooks/use-hitl-oversight";
import { ROLE_ADMIN, ROLE_APPROVER } from "@/lib/roles";
import type { AutonomyTier } from "@/lib/api/hitl-oversight";

type OversightTab = "analytics" | "samples" | "autonomy";
const TABS: OversightTab[] = ["analytics", "samples", "autonomy"];
const PERIODS = [7, 30, 90] as const;

/**
 * Oversight — approval analytics, the sampled-audit review queue and earned
 * autonomy (docs/hitl-oversight.md). Tab, agent and period live in the URL.
 *
 * Scoping is the backend's: administrators and approvers see every agent;
 * anyone else picks an agent they can edit first, rather than meeting a 400.
 */
export function OversightPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = (TABS as string[]).includes(params.get("tab") ?? "") ? (params.get("tab") as OversightTab) : "analytics";
  const agentId = params.get("agentId") ?? "";
  // Only the offered periods: an arbitrary ?days= (e.g. 1000000000) would make new Date(...).toISOString() throw.
  const requestedDays = Number(params.get("days"));
  const days = (PERIODS as readonly number[]).includes(requestedDays) ? requestedDays : 30;
  const seesAll = useMayUse()([ROLE_ADMIN, ROLE_APPROVER]);
  const needsAgent = !seesAll && !agentId;
  const [now] = useState(() => Date.now());

  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  const query = useMemo(
    () => ({ agentId: agentId || undefined, from: new Date(now - days * 86_400_000).toISOString() }),
    [agentId, days, now],
  );
  const autonomy = useAutonomy(agentId || undefined, !needsAgent);
  const pending = usePendingSampleCount(agentId || undefined, !needsAgent);
  const tiers = useMemo(() => {
    const map: Record<string, AutonomyTier> = {};
    for (const view of autonomy.data ?? []) map[view.category] = view.tier;
    return map;
  }, [autonomy.data]);

  const labels: Record<OversightTab, string> = {
    analytics: t("oversight.tabAnalytics", "Analytics"),
    samples: t("oversight.tabSamples", { n: pending.data ?? 0, defaultValue: "Audit samples ({{n}})" }),
    autonomy: t("oversight.tabAutonomy", "Autonomy"),
  };

  return (
    <div className="space-y-6" data-testid="oversight-page">
      <div>
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <ScanEye className="h-8 w-8 text-primary" />
          {t("oversight.title", "Oversight")}
        </h1>
        <p className="mt-1 text-muted-foreground">
          {t("oversight.subtitle", "How approvals turn out, what ran without approval, and which categories have earned less friction.")}
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div className="w-full max-w-sm" data-testid="oversight-agent-picker">
          <span className="mb-1 block text-xs text-muted-foreground">
            {seesAll ? t("oversight.agentOptional", "Agent (all when empty)") : t("oversight.agentRequired", "Agent")}
          </span>
          <AgentPicker value={agentId} onChange={(id) => set("agentId", id)} placeholder={t("oversight.pickAgent", "Pick an agent")} />
        </div>
        <label className="text-xs text-muted-foreground">
          <span className="mb-1 block">{t("oversight.period", "Period")}</span>
          <select
            className="rounded-md border border-input bg-background px-2 py-1.5 text-sm text-foreground"
            value={days}
            onChange={(e) => set("days", e.target.value)}
            data-testid="oversight-period"
          >
            {PERIODS.map((d) => (
              <option key={d} value={d}>
                {t("oversight.lastDays", { n: d, defaultValue: "Last {{n}} days" })}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div role="tablist" aria-label={t("oversight.views", "Oversight views")} className="flex gap-1 border-b border-border">
        {TABS.map((key) => (
          <button
            key={key}
            type="button"
            role="tab"
            id={`oversight-tab-${key}`}
            aria-selected={tab === key}
            aria-controls={`oversight-panel-${key}`}
            tabIndex={tab === key ? 0 : -1}
            onClick={() => set("tab", key)}
            className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
              tab === key ? "border-primary text-foreground" : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
            data-testid={`oversight-tab-${key}`}
          >
            {labels[key]}
          </button>
        ))}
      </div>

      <div role="tabpanel" id={`oversight-panel-${tab}`} aria-labelledby={`oversight-tab-${tab}`}>
        {needsAgent ? (
          <EmptyState
            icon={Bot}
            title={t("oversight.pickAgentTitle", "Pick an agent")}
            description={t("oversight.pickAgentDescription", "You see the oversight of the agents you can edit, one agent at a time.")}
          />
        ) : tab === "analytics" ? (
          <ApprovalAnalytics query={query} tiers={tiers} />
        ) : tab === "samples" ? (
          <AuditSampleQueue agentId={agentId || undefined} />
        ) : (
          <AutonomyPanel states={autonomy} />
        )}
      </div>
    </div>
  );
}
