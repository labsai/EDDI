import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { ExternalLink, ShieldAlert } from "lucide-react";
import { drillDownDenial, type HealthQuery } from "@/lib/api/health";
import { useHealthSessions } from "@/hooks/use-agent-health";
import { AccessibleDialog, Badge, Button, Skeleton, formatUsd, getErrorMessage } from "./deps";
import type { DrillFilter } from "./drill";

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
  const [cursor, setCursor] = useState<string | undefined>();
  const sessions = useHealthSessions(
    agentId,
    { ...query, outcome: filter?.outcome, evidence: filter?.evidence, limit: 25, cursor },
    open && filter !== null,
  );
  const denial = sessions.isError ? drillDownDenial(sessions.error) : null;
  const title = filter ? t(filter.title[0], filter.title[1]) : t("health.sessions.title", "Sessions");

  return (
    <AccessibleDialog open={open} onClose={onClose} title={title} maxWidth="max-w-3xl" testId="health-sessions-dialog">
      <div className="space-y-3">
        {filter?.evidence && (
          <p className="text-xs text-muted-foreground">
            {t("health.sessions.evidence", { list: filter.evidence.join(", "), defaultValue: "Evidence: {{list}}" })}
          </p>
        )}
        {sessions.isLoading && <Skeleton className="h-24 w-full" />}
        {denial && <DrillDownDenied reason={denial} />}
        {sessions.isError && !denial && (
          <p className="text-sm text-destructive" role="alert">{getErrorMessage(sessions.error)}</p>
        )}
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
                  <tr key={`${s.conversationId}-${s.sessionNo}`} className="border-t border-border" data-testid="health-session-row">
                    <td className="p-1.5 tabular-nums">{new Date(s.startedAt).toLocaleString()}</td>
                    <td className="p-1.5">
                      <Badge variant="secondary">{s.outcome}</Badge>
                      {s.provisional && <span className="ms-1 text-muted-foreground">{t("health.sessions.provisional", "provisional")}</span>}
                    </td>
                    <td className="p-1.5">{s.evidence}</td>
                    <td className="p-1.5">{s.origin}</td>
                    <td className="p-1.5 text-end tabular-nums">{s.turns}</td>
                    <td className="p-1.5 text-end tabular-nums">{s.costUsd === null ? "—" : formatUsd(s.costUsd)}</td>
                    <td className="p-1.5 text-end">
                      <Link
                        to={`/manage/conversationview/${encodeURIComponent(s.conversationId)}`}
                        className="inline-flex items-center gap-1 text-primary hover:underline"
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
        {sessions.data?.nextCursor && (
          <Button size="sm" variant="outline" onClick={() => setCursor(sessions.data?.nextCursor ?? undefined)}>
            {t("health.sessions.more", "Next page")}
          </Button>
        )}
      </div>
    </AccessibleDialog>
  );
}
