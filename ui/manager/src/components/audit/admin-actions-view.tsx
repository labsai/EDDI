import { useId, useMemo, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { History, Lock, Search, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { useAdminActions } from "@/hooks/use-audit";
import {
  ADMIN_ACTION_METHODS,
  adminActionsFailure,
  filterAdminActions,
  olderPagesCanMatch,
  statusTone,
  type AdminActionWindow,
} from "@/lib/admin-actions";

const PAGE_SIZE = 100;

const WINDOW_LABELS: Record<AdminActionWindow, [string, string]> = {
  any: ["audit.adminActions.windowAny", "Any time"],
  hour: ["audit.adminActions.windowHour", "Last hour"],
  day: ["audit.adminActions.windowDay", "Last 24 hours"],
  week: ["audit.adminActions.windowWeek", "Last 7 days"],
  month: ["audit.adminActions.windowMonth", "Last 30 days"],
};

const STATUS_VARIANT = {
  ok: "success",
  refused: "warning",
  failed: "destructive",
  unknown: "outline",
} as const;

/**
 * Who changed, deployed, imported, erased — or was refused — what, and when
 * (`GET /auditstore/admin-actions`, EDDI 6.6+, admin only).
 *
 * Every row is a signed ledger record of one mutating REST call outside the
 * chat APIs: caller, method, path, endpoint and status, never a request body.
 * The endpoint filters by actor; the method and time filters narrow the rows
 * loaded so far, and "Load older" pages further back.
 */
export function AdminActionsView() {
  const { t } = useTranslation();
  const actorId = useId();
  const methodId = useId();
  const windowId = useId();

  const [actorDraft, setActorDraft] = useState("");
  const [actor, setActor] = useState("");
  const [method, setMethod] = useState("");
  const [timeWindow, setTimeWindow] = useState<AdminActionWindow>("any");

  const query = useAdminActions(actor, PAGE_SIZE);
  const loaded = useMemo(() => query.data?.pages.flat() ?? [], [query.data]);
  const rows = useMemo(() => filterAdminActions(loaded, { method, window: timeWindow }), [loaded, method, timeWindow]);
  const canLoadOlder = !!query.hasNextPage && olderPagesCanMatch(loaded, timeWindow);

  const applyActor = (e: FormEvent) => {
    e.preventDefault();
    setActor(actorDraft.trim());
  };

  const filtered = method !== "" || timeWindow !== "any" || actor !== "";

  return (
    <div className="space-y-4" data-testid="admin-actions-view">
      <p className="text-sm text-muted-foreground">
        {t(
          "audit.adminActions.description",
          "Every administrative change made through the REST API — deploys, configuration writes, imports, erasures, and the calls that were refused — signed into the audit ledger. Request bodies are never recorded, and the id of a person an action was about is pseudonymised.",
        )}
      </p>

      {/* Filters */}
      <div className="flex flex-wrap items-end gap-3 rounded-xl border border-border bg-card p-4">
        <form onSubmit={applyActor} className="flex items-end gap-2" role="search">
          <div>
            <label htmlFor={actorId} className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("audit.adminActions.actor", "Actor")}
            </label>
            <input
              id={actorId}
              type="text"
              value={actorDraft}
              onChange={(e) => setActorDraft(e.target.value)}
              placeholder={t("audit.adminActions.actorPlaceholder", "Principal, e.g. alice")}
              className="h-9 w-56 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              data-testid="admin-actions-actor"
            />
          </div>
          <Button type="submit" variant="outline" size="sm" data-testid="admin-actions-apply">
            <Search className="h-3.5 w-3.5" aria-hidden="true" />
            {t("audit.adminActions.apply", "Apply")}
          </Button>
        </form>
        <div>
          <label htmlFor={methodId} className="mb-1 block text-xs font-medium text-muted-foreground">
            {t("audit.adminActions.method", "Method")}
          </label>
          <select
            id={methodId}
            value={method}
            onChange={(e) => setMethod(e.target.value)}
            className="h-9 rounded-lg border border-input bg-background px-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="admin-actions-method"
          >
            <option value="">{t("audit.adminActions.allMethods", "All methods")}</option>
            {ADMIN_ACTION_METHODS.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={windowId} className="mb-1 block text-xs font-medium text-muted-foreground">
            {t("audit.adminActions.window", "Time")}
          </label>
          <select
            id={windowId}
            value={timeWindow}
            onChange={(e) => setTimeWindow(e.target.value as AdminActionWindow)}
            className="h-9 rounded-lg border border-input bg-background px-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="admin-actions-window"
          >
            {(Object.keys(WINDOW_LABELS) as AdminActionWindow[]).map((w) => (
              <option key={w} value={w}>
                {t(WINDOW_LABELS[w][0], WINDOW_LABELS[w][1])}
              </option>
            ))}
          </select>
        </div>
        {filtered && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setActorDraft("");
              setActor("");
              setMethod("");
              setTimeWindow("any");
            }}
            data-testid="admin-actions-clear"
          >
            <X className="h-3.5 w-3.5" aria-hidden="true" />
            {t("audit.adminActions.clear", "Clear filters")}
          </Button>
        )}
      </div>

      {/* Results */}
      {query.isLoading ? (
        <div className="space-y-2" data-testid="admin-actions-loading" aria-busy="true">
          {Array.from({ length: 5 }, (_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      ) : query.isError && loaded.length === 0 ? (
        <AdminActionsError error={query.error} onRetry={() => query.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyState
          icon={History}
          title={
            filtered
              ? t("audit.adminActions.noMatch", "No administrative actions match these filters")
              : t("audit.adminActions.empty", "No administrative actions recorded yet")
          }
          description={
            filtered
              ? canLoadOlder
                ? t("audit.adminActions.noMatchLoadOlder", "Only the newest rows are loaded — load older ones to search further back.")
                : undefined
              : t(
                  "audit.adminActions.emptyHint",
                  "EDDI records them from version 6.6 on, unless eddi.audit.admin-actions.enabled is false. An older server shows nothing here.",
                )
          }
        />
      ) : (
        <div className="overflow-x-auto rounded-xl border border-border bg-card">
          <table className="w-full text-sm" data-testid="admin-actions-table">
            <caption className="sr-only">{t("audit.adminActions.caption", "Administrative actions, newest first")}</caption>
            <thead>
              <tr className="border-b border-border bg-muted/30 text-start text-xs uppercase tracking-wider text-muted-foreground">
                <th scope="col" className="px-4 py-2 text-start font-medium">{t("audit.adminActions.colTime", "Time")}</th>
                <th scope="col" className="px-4 py-2 text-start font-medium">{t("audit.adminActions.colActor", "Actor")}</th>
                <th scope="col" className="px-4 py-2 text-start font-medium">{t("audit.adminActions.colAction", "Action")}</th>
                <th scope="col" className="px-4 py-2 text-start font-medium">{t("audit.adminActions.colEndpoint", "Endpoint")}</th>
                <th scope="col" className="px-4 py-2 text-start font-medium">{t("audit.adminActions.colStatus", "Status")}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id} className="border-b border-border/50 last:border-0" data-testid={`admin-action-${row.id}`}>
                  <td className="whitespace-nowrap px-4 py-2 text-xs tabular-nums text-muted-foreground">
                    <time dateTime={row.timestamp}>{formatTime(row.timestamp)}</time>
                  </td>
                  <td className="px-4 py-2 text-xs font-medium text-foreground">
                    <button
                      type="button"
                      className="rounded hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      onClick={() => {
                        setActorDraft(row.actor);
                        setActor(row.actor);
                      }}
                      title={t("audit.adminActions.filterByActor", "Show only this actor's actions")}
                    >
                      {row.actor}
                    </button>
                  </td>
                  <td className="px-4 py-2">
                    {/* Two columns, so a long path wraps under itself and never
                        under the method (seen live on …/schedules/{id}/disable). */}
                    <div className="flex items-start gap-2">
                      <span className="w-14 shrink-0 font-mono text-[10px] font-semibold text-primary">{row.method}</span>
                      {/* A floor on the width: in a narrow pane the table scrolls
                          sideways instead of wrapping the path a few letters a line. */}
                      <code className="min-w-[14rem] break-all font-mono text-xs text-foreground">{row.path}</code>
                    </div>
                  </td>
                  <td className="px-4 py-2 font-mono text-[10px] text-muted-foreground">{row.endpoint ?? "—"}</td>
                  <td className="px-4 py-2">
                    <Badge variant={STATUS_VARIANT[statusTone(row.status)]} data-testid={`admin-action-status-${row.id}`}>
                      {row.status ?? "—"}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {loaded.length > 0 && (
        <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
          <span data-testid="admin-actions-count">
            {t("audit.adminActions.shown", {
              defaultValue: "{{shown}} of {{loaded}} loaded rows shown",
              shown: rows.length,
              loaded: loaded.length,
            })}
          </span>
          {query.isError && (
            <span className="text-destructive" role="alert">
              {t("audit.adminActions.loadOlderError", "Loading older rows failed.")}
            </span>
          )}
          {canLoadOlder && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => query.fetchNextPage()}
              disabled={query.isFetchingNextPage}
              data-testid="admin-actions-load-older"
            >
              {query.isFetchingNextPage
                ? t("common.loading", "Loading…")
                : t("audit.adminActions.loadOlder", "Load older")}
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

function AdminActionsError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const { t } = useTranslation();
  const failure = adminActionsFailure(error);
  if (failure === "forbidden") {
    return (
      <EmptyState
        icon={Lock}
        title={t("audit.adminActions.forbidden", "Only administrators can read administrative actions")}
        description={t(
          "audit.adminActions.forbiddenHint",
          "The log records every administrator's changes, so reading it needs the eddi-admin role.",
        )}
      />
    );
  }
  if (failure === "unsupported") {
    return (
      <EmptyState
        icon={History}
        title={t("audit.adminActions.unsupported", "This EDDI does not record administrative actions")}
        description={t("audit.adminActions.unsupportedHint", "They are recorded from EDDI 6.6 on.")}
      />
    );
  }
  return (
    <ErrorState
      message={t("audit.adminActions.loadError", "Could not load the administrative actions.")}
      onRetry={onRetry}
      retryLabel={t("common.retry", "Retry")}
    />
  );
}

function formatTime(iso: string): string {
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? iso : at.toLocaleString();
}
