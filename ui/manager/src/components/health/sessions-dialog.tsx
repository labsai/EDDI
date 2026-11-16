import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { ExternalLink, ShieldAlert } from "lucide-react";
import { drillDownDenial, type HealthQuery } from "./api";
import { useHealthSessions } from "./use-agent-health";
import { AccessibleDialog, Badge, Button, Skeleton, formatUsd, getErrorMessage } from "./deps";
import type { DrillFilter } from "./drill";
import { enumLabel, formatDateTime } from "./format";

/** Why the drill-down refused, in words the person can act on. */
export function DrillDownDenied({ reason }: { reason: "REVIEW_DISABLED" | "FORBIDDEN" }) {
  const { t } = useTranslation();
  return (
    <div className="flex items-start gap-3 rounded-lg border border-border bg-muted/50 p-4 text-sm" role="alert" data-testid={`health-denied-${reason}`}>
      <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      {reason === "REVIEW_DISABLED" ? (
        <div className="space-y-1">
          <p className="font-medium text-foreground">{t("health.sessions.reviewDisabledTitle", "Conversation review is switched off for this version")}</p>
          <p className="text-muted-foreground">
            {t(
              "health.sessions.reviewDisabledBody",
              "Health numbers do not need it, but listing the sessions behind them does. Enable conversation review in the agent's configuration and deploy a new version.",
            )}
          </p>
        </div>
      ) : (
        <div className="space-y-1">
          <p className="font-medium text-foreground">{t("health.sessions.forbiddenTitle", "You may not read this agent's conversations")}</p>
          <p className="text-muted-foreground">
            {t("health.sessions.forbiddenBody", "Listing sessions needs permission to read the agent's conversations. Ask the agent's owner for access.")}
          </p>
        </div>
      )}
    </div>
  );
}

/**
 * The pages of one filter. Keyed by the filter at the call site, so a different
 * measure starts on page 1 with an empty list — never the previous list under a
 * new title. Paging keeps a stack of cursors, which is what makes "Previous"
 * possible against a forward-only cursor API.
 */
function SessionsBody({ agentId, query, filter, onClose }: { agentId: string; query: HealthQuery; filter: DrillFilter; onClose: () => void }) {
  const { t } = useTranslation();
  const [stack, setStack] = useState<Array<string | undefined>>([undefined]);
  const cursor = stack[stack.length - 1];
  const sessions = useHealthSessions(agentId, { ...query, outcome: filter.outcome, evidence: filter.evidence }, cursor);
  const denial = sessions.isError ? drillDownDenial(sessions.error) : null;
  const next = sessions.data?.nextCursor ?? undefined;

  return (
    <div className="space-y-3">
      {filter.evidence && (
        <p className="text-xs text-muted-foreground">
          {t("health.sessions.evidence", { list: filter.evidence.map((e) => enumLabel(t, "evidence", e)).join(", "), defaultValue: "Evidence: {{list}}" })}
        </p>
      )}
      {sessions.isLoading && <Skeleton className="h-24 w-full" />}
      {denial && <DrillDownDenied reason={denial} />}
      {sessions.isError && !denial && <p className="text-sm text-destructive" role="alert">{getErrorMessage(sessions.error)}</p>}
      {sessions.data && sessions.data.items.length === 0 && (
        <p className="text-sm text-muted-foreground">{t("health.sessions.empty", "No sessions match.")}</p>
      )}
      {sessions.data && sessions.data.items.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-start text-xs" data-testid="health-sessions-table">
            <thead className="text-muted-foreground">
              <tr>
                <th className="p-1.5 text-start font-medium">{t("health.sessions.started", "Started")}</th>
                <th className="p-1.5 text-start font-medium">{t("health.sessions.outcome", "Outcome")}</th>
                <th className="p-1.5 text-start font-medium">{t("health.sessions.evidenceCol", "Evidence")}</th>
                <th className="p-1.5 text-start font-medium">{t("health.sessions.origin", "Origin")}</th>
                <th className="p-1.5 text-end font-medium">{t("health.sessions.turns", "Turns")}</th>
                <th className="p-1.5 text-end font-medium">{t("health.sessions.cost", "Cost")}</th>
                <th className="p-1.5" />
              </tr>
            </thead>
            <tbody>
              {sessions.data.items.map((s) => (
                <tr key={`${s.conversationId}-${s.sessionNo}`} className="border-t border-border" data-testid="health-session-row" data-outcome={s.outcome} data-evidence={s.evidence}>
                  <td className="p-1.5 tabular-nums">{formatDateTime(s.startedAt)}</td>
                  <td className="p-1.5">
                    <Badge variant="secondary">{enumLabel(t, "outcome", s.outcome)}</Badge>
                    {s.provisional && <span className="ms-1 text-muted-foreground">{t("health.sessions.provisional", "provisional")}</span>}
                  </td>
                  <td className="p-1.5">{enumLabel(t, "evidence", s.evidence)}</td>
                  <td className="p-1.5">{enumLabel(t, "origin", s.origin)}</td>
                  <td className="p-1.5 text-end tabular-nums">{s.turns}</td>
                  <td className="p-1.5 text-end tabular-nums">{s.costUsd === null ? "—" : formatUsd(s.costUsd)}</td>
                  <td className="p-1.5 text-end">
                    <Link
                      to={`/manage/conversationview/${encodeURIComponent(s.conversationId)}`}
                      className="inline-flex items-center gap-1 text-brand-700 hover:underline dark:text-brand-300"
                      onClick={onClose}
                    >
                      {t("health.sessions.open", "Open")}
                      <ExternalLink className="h-3 w-3" aria-hidden="true" />
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {(stack.length > 1 || next) && (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" disabled={stack.length < 2} onClick={() => setStack((s) => s.slice(0, -1))} data-testid="health-sessions-prev">
            {t("health.sessions.previous", "Previous page")}
          </Button>
          <Button size="sm" variant="outline" disabled={!next} onClick={() => setStack((s) => [...s, next])} data-testid="health-sessions-next">
            {t("health.sessions.more", "Next page")}
          </Button>
        </div>
      )}
    </div>
  );
}

/** Sessions behind a measure (`/health/sessions`), each linking to the conversation view. */
export function SessionsDialog({
  open,
  onClose,
  agentId,
  query,
  filter,
}: {
  open: boolean;
  onClose: () => void;
  agentId: string;
  query: HealthQuery;
  filter: DrillFilter | null;
}) {
  const { t } = useTranslation();
  const title = filter ? t(filter.title[0], filter.title[1]) : t("health.sessions.title", "Sessions");
  const identity = JSON.stringify([filter?.outcome, filter?.evidence, query.period, query.version, [...(query.origins ?? [])].sort()]);
  return (
    <AccessibleDialog open={open} onClose={onClose} title={title} maxWidth="max-w-3xl" testId="health-sessions-dialog">
      {filter && <SessionsBody key={identity} agentId={agentId} query={query} filter={filter} onClose={onClose} />}
    </AccessibleDialog>
  );
}
