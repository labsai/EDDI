import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { BellRing, Bot, CheckCheck, Pencil, Plus, Trash2 } from "lucide-react";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AgentPicker } from "@/components/shared/agent-picker";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { AlertEventsTable } from "@/components/alerts/alert-events-table";
import { AlertRuleEditor } from "@/components/alerts/alert-rule-editor";
import { useIsAdminOrUnknown } from "@/hooks/use-auth";
import { alertErrorCode, useAcknowledgeAlerts, useAlertEvents, useAlertRules, useDeleteAlertRule } from "@/hooks/use-alerts";
import { blankRule, type AlertEventQuery, type AlertRule, type AlertSource } from "@/lib/api/alerts";

const PAGE_SIZE = 25;
const selectClass =
  "h-10 rounded-lg border border-input bg-background px-3 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ring";

type AlertsTab = "inbox" | "rules";

/**
 * Alerts — the inbox of fired alerts and the rules that fire them.
 *
 * The inbox opens on what is not yet acknowledged; each alert shows its delivery
 * per target (Slack, webhook) with Retry for a failed one, and can be
 * acknowledged one by one or in bulk. Rules pick a source (a rating, a failed
 * turn), the agents, the condition, where to deliver ("Send test alert" tries
 * it), and the throttle and digest.
 *
 * Scoping is the backend's: a non-administrator picks an agent first (the
 * backend answers 400 AGENT_ID_REQUIRED otherwise) and writes rules for that
 * agent only.
 */
export function AlertsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const isAdmin = useIsAdminOrUnknown();
  const tab: AlertsTab = params.get("tab") === "rules" ? "rules" : "inbox";
  const agentId = params.get("agentId") ?? "";
  const needsAgent = !isAdmin && !agentId;

  const setParam = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  const tabs: [AlertsTab, string][] = [
    ["inbox", t("alerts.tabInbox", "Inbox")],
    ["rules", t("alerts.tabRules", "Rules")],
  ];

  return (
    <div className="space-y-6" data-testid="alerts-page">
      <div>
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <BellRing className="h-8 w-8 text-primary" />
          {t("alerts.title", "Alerts")}
        </h1>
        <p className="mt-1 text-muted-foreground">
          {t("alerts.subtitle", "Hear about low ratings and failed turns — in this inbox, in Slack or at a webhook.")}
        </p>
      </div>

      <div className="w-full max-w-sm" data-testid="alerts-agent-picker">
        <span className="mb-1 block text-xs text-muted-foreground">
          {isAdmin ? t("feedback.agentOptional", "Agent (all when empty)") : t("feedback.agentRequired", "Agent")}
        </span>
        <AgentPicker value={agentId} onChange={(id) => setParam("agentId", id)} placeholder={t("feedback.pickAgent", "Pick an agent")} />
      </div>

      <div role="tablist" aria-label={t("alerts.views", "Alert views")} className="flex gap-1 border-b border-border">
        {tabs.map(([key, label]) => (
          <button
            key={key}
            type="button"
            role="tab"
            id={`alerts-tab-${key}`}
            aria-selected={tab === key}
            aria-controls={`alerts-panel-${key}`}
            tabIndex={tab === key ? 0 : -1}
            onClick={() => setParam("tab", key === "inbox" ? "" : key)}
            onKeyDown={(e) => {
              if (e.key === "ArrowRight" || e.key === "ArrowLeft") {
                e.preventDefault();
                const next = key === "inbox" ? "rules" : "inbox";
                setParam("tab", next === "inbox" ? "" : next);
                document.getElementById(`alerts-tab-${next}`)?.focus();
              }
            }}
            className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
              tab === key ? "border-primary text-foreground" : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
            data-testid={`alerts-tab-${key}`}
          >
            {label}
          </button>
        ))}
      </div>

      <div role="tabpanel" id={`alerts-panel-${tab}`} aria-labelledby={`alerts-tab-${tab}`} className="space-y-4">
        {needsAgent ? (
          <EmptyState
            icon={Bot}
            title={t("alerts.pickAgentTitle", "Pick an agent to see its alerts")}
            description={t("alerts.pickAgentDescription", "You see and write the alerts of the agents you can edit, one agent at a time.")}
          />
        ) : tab === "inbox" ? (
          <Inbox key={agentId} agentId={agentId} params={params} setParam={setParam} />
        ) : (
          <Rules agentId={agentId} isAdmin={isAdmin} />
        )}
      </div>
    </div>
  );
}

function Inbox({ agentId, params, setParam }: { agentId: string; params: URLSearchParams; setParam: (key: string, value: string) => void }) {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const showAcknowledged = params.get("acknowledged") === "all";
  const source = (params.get("source") ?? "") as AlertSource | "";
  const query: AlertEventQuery = {
    agentId: agentId || undefined,
    source: source || undefined,
    acknowledged: showAcknowledged ? undefined : false,
  };
  const list = useAlertEvents(query, page * PAGE_SIZE, PAGE_SIZE);
  const bulk = useAcknowledgeAlerts();

  const acknowledgeSelected = async () => {
    try {
      const n = await bulk.mutateAsync({ ids: [...selected] });
      toast.success(t("alerts.acknowledgedN", { n, defaultValue: "Acknowledged {{n}}." }));
      setSelected(new Set());
    } catch {
      toast.error(t("alerts.acknowledgeFailed", "The alert could not be acknowledged."));
    }
  };

  const acknowledgeAll = async () => {
    try {
      const n = await bulk.mutateAsync({ ids: null, query: { agentId: agentId || undefined, source: source || undefined } });
      toast.success(t("alerts.acknowledgedN", { n, defaultValue: "Acknowledged {{n}}." }));
      setSelected(new Set());
    } catch {
      toast.error(t("alerts.acknowledgeFailed", "The alert could not be acknowledged."));
    }
  };

  const events = list.data?.events ?? [];
  const total = list.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const knownTotal = list.data?.total;

  // Acknowledging the last events of the last page shrinks the list under us:
  // move back to the page that now exists instead of showing an empty one.
  useEffect(() => {
    if (knownTotal === undefined) return;
    const last = Math.max(0, Math.ceil(knownTotal / PAGE_SIZE) - 1);
    if (page > last) setPage(last);
  }, [knownTotal, page]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <select
          className={selectClass}
          value={source}
          onChange={(e) => {
            setParam("source", e.target.value);
            setPage(0);
            setSelected(new Set());
          }}
          aria-label={t("alerts.filterSource", "Source")}
          data-testid="alerts-filter-source"
        >
          <option value="">{t("alerts.allSources", "All sources")}</option>
          <option value="RATING">{t("alerts.sourceRatingShort", "Ratings")}</option>
          <option value="CONVERSATION_ERROR">{t("alerts.sourceErrorShort", "Failed turns")}</option>
          <option value="OUTPUT_MATCH">{t("alerts.sourceOutputShort", "Turn results")}</option>
        </select>
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="h-4 w-4 accent-primary"
            checked={showAcknowledged}
            onChange={(e) => {
              setParam("acknowledged", e.target.checked ? "all" : "");
              setPage(0);
              setSelected(new Set());
            }}
            data-testid="alerts-show-acknowledged"
          />
          {t("alerts.showAcknowledged", "Show acknowledged")}
        </label>
        <div className="ms-auto flex gap-2">
          <Button size="sm" variant="outline" disabled={selected.size === 0 || bulk.isPending} onClick={() => void acknowledgeSelected()} data-testid="alerts-ack-selected">
            <CheckCheck className="h-4 w-4" />
            {t("alerts.acknowledgeSelected", { n: selected.size, defaultValue: "Acknowledge selected ({{n}})" })}
          </Button>
          <Button size="sm" variant="ghost" disabled={bulk.isPending || total === 0} onClick={() => void acknowledgeAll()} data-testid="alerts-ack-all">
            {t("alerts.acknowledgeAll", "Acknowledge everything here")}
          </Button>
        </div>
      </div>

      {list.isLoading || (events.length === 0 && total > 0) ? (
        // Also while moving back from a page that emptied: there are alerts, just not here.
        <div className="space-y-2" data-testid="alerts-loading">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-14 w-full" />
          ))}
        </div>
      ) : list.isError && !list.data ? (
        <ErrorState message={t("common.error", "Something went wrong")} onRetry={() => void list.refetch()} retryLabel={t("common.retry", "Retry")} />
      ) : events.length === 0 ? (
        <EmptyState
          icon={CheckCheck}
          title={t("alerts.inboxEmpty", "No open alerts")}
          description={t("alerts.inboxEmptyDescription", "Alerts land here when a rule fires. Create rules on the Rules tab.")}
        />
      ) : (
        <>
          <AlertEventsTable events={events} selected={selected} onSelectedChange={setSelected} />
          {pages > 1 && (
            <div className="flex items-center justify-end gap-2">
              <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)} data-testid="alerts-prev">
                {t("feedback.previous", "Previous")}
              </Button>
              <span className="text-xs text-muted-foreground">
                {page + 1} / {pages}
              </span>
              <Button size="sm" variant="outline" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)} data-testid="alerts-next">
                {t("feedback.next", "Next")}
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

function Rules({ agentId, isAdmin }: { agentId: string; isAdmin: boolean }) {
  const { t } = useTranslation();
  const rules = useAlertRules(agentId || undefined);
  const remove = useDeleteAlertRule();
  const [editing, setEditing] = useState<AlertRule | null>(null);
  const [deleting, setDeleting] = useState<AlertRule | null>(null);

  const doDelete = async (rule: AlertRule) => {
    setDeleting(null);
    if (!rule.id) return;
    try {
      await remove.mutateAsync(rule.id);
      toast.success(t("alerts.deleted", "Alert rule deleted."));
    } catch (err) {
      toast.error(
        alertErrorCode(err) === "ADMIN_SCOPE_REQUIRED" || alertErrorCode(err) === "WEBHOOK_ADMIN_ONLY"
          ? t("alerts.errorAdminRule", "Only an administrator can change this rule.")
          : t("alerts.deleteFailed", "The rule could not be deleted."),
      );
    }
  };

  if (editing) {
    return <AlertRuleEditor initial={editing} isAdmin={isAdmin} onDone={() => setEditing(null)} />;
  }

  const list = rules.data?.rules ?? [];
  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <Button onClick={() => setEditing(blankRule("RATING", agentId))} data-testid="alerts-new-rule">
          <Plus className="h-4 w-4" />
          {t("alerts.newRule", "New rule")}
        </Button>
      </div>
      {rules.isLoading ? (
        <Skeleton className="h-24 w-full" />
      ) : rules.isError && !rules.data ? (
        <ErrorState message={t("common.error", "Something went wrong")} onRetry={() => void rules.refetch()} retryLabel={t("common.retry", "Retry")} />
      ) : list.length === 0 ? (
        <EmptyState
          icon={BellRing}
          title={t("alerts.noRules", "No alert rules yet")}
          description={t("alerts.noRulesDescription", "A rule watches ratings or failed turns and tells you where you want to hear about them.")}
        />
      ) : (
        <ul className="space-y-2" data-testid="alert-rules-list">
          {list.map((rule) => (
            <li key={rule.id ?? rule.name} className="flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card p-4" data-testid={`alert-rule-row-${rule.id}`}>
              <div className="min-w-0 flex-1">
                <p className="font-medium text-foreground">{rule.name}</p>
                <p className="text-xs text-muted-foreground">
                  {rule.source === "RATING"
                    ? t("alerts.sourceRatingShort", "Ratings")
                    : rule.source === "OUTPUT_MATCH"
                      ? t("alerts.sourceOutputShort", "Turn results")
                      : t("alerts.sourceErrorShort", "Failed turns")}{" "}
                  ·{" "}
                  {rule.scope.allAgents
                    ? t("alerts.allAgents", "Every agent")
                    : t("alerts.agentCount", { n: rule.scope.agentIds.length, defaultValue: "Agents: {{n}}" })}{" "}
                  · {t("alerts.targetCount", { n: rule.delivery.length, defaultValue: "Targets besides the inbox: {{n}}" })}
                </p>
              </div>
              {!rule.enabled && <Badge variant="secondary">{t("alerts.disabled", "Disabled")}</Badge>}
              <Button size="sm" variant="outline" onClick={() => setEditing(rule)} data-testid={`alert-rule-edit-${rule.id}`}>
                <Pencil className="h-4 w-4" />
                {t("alerts.edit", "Edit")}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setDeleting(rule)} disabled={remove.isPending} data-testid={`alert-rule-delete-${rule.id}`}>
                <Trash2 className="h-4 w-4" />
                <span className="sr-only">{t("alerts.delete", "Delete")}</span>
              </Button>
            </li>
          ))}
        </ul>
      )}
      <AlertDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("alerts.deleteTitle", "Delete this alert rule?")}
        description={t("alerts.deleteDescription", "It stops firing at once. The alerts it already raised stay in the inbox.")}
        confirmLabel={t("alerts.delete", "Delete")}
        cancelLabel={t("common.cancel", "Cancel")}
        variant="destructive"
        isPending={remove.isPending}
        onConfirm={() => deleting && void doDelete(deleting)}
      />
    </div>
  );
}
