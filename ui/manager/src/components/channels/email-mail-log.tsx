import { useEffect, useId, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useLocation } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { Inbox, Search } from "lucide-react";
import { Badge, Button } from "@eddi/ui";
import { Input } from "@eddi/app-kit/components/ui/input";
import { Skeleton } from "@eddi/app-kit/components/ui/skeleton";
import { isApiError } from "@eddi/app-kit/lib/api-client";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { useEmailLog, useEmailLogLookup } from "@/hooks/use-email-log";
import {
  EMAIL_LOG_REASON_CATEGORIES,
  EMAIL_LOG_STATES,
  type EmailLogFilters,
  type EmailLogRow,
} from "@/lib/api/email-log";

const selectClass =
  "flex h-9 rounded-lg border border-border bg-background px-3 text-sm text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary";

function stateVariant(state: string): "success" | "destructive" | "warning" | "secondary" {
  if (state === "DONE" || state === "SENT") return "success";
  if (state === "REFUSED" || state === "FAILED" || state === "SUPPRESSED") return "destructive";
  if (state === "RECEIVED" || state === "PENDING") return "warning";
  return "secondary";
}

function formatTime(iso: string, locale: string): string {
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? iso : at.toLocaleString(locale);
}

function LogRow({ row }: { row: EmailLogRow }) {
  const { t, i18n } = useTranslation();
  return (
    <tr className="border-t border-border align-top" data-testid={`email-log-row-${row.id}`}>
      <td className="py-2 pe-3 whitespace-nowrap text-muted-foreground">{formatTime(row.createdAt, i18n.language)}</td>
      <td className="py-2 pe-3 whitespace-nowrap">
        {row.direction === "IN" ? t("emailLog.directionIn", "In") : t("emailLog.directionOut", "Out")}
        <span className="ms-1 text-[10px] text-muted-foreground">{row.kind}</span>
      </td>
      <td className="py-2 pe-3 break-all">{row.counterpart ?? "—"}</td>
      <td className="py-2 pe-3">
        <Badge variant={stateVariant(row.state)} data-testid={`email-log-state-${row.id}`}>{row.state}</Badge>
      </td>
      <td className="py-2 pe-3 font-mono text-[11px] break-all" data-testid={`email-log-reason-${row.id}`}>{row.reason ?? "—"}</td>
      <td className="py-2 font-mono text-[11px] break-all text-muted-foreground">{row.conversationId ?? "—"}</td>
    </tr>
  );
}

function LogTable({ rows, testId }: { rows: EmailLogRow[]; testId: string }) {
  const { t } = useTranslation();
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs" data-testid={testId}>
        <thead className="text-start text-muted-foreground">
          <tr>
            <th className="py-1 pe-3 text-start font-medium">{t("emailLog.colTime", "Time")}</th>
            <th className="py-1 pe-3 text-start font-medium">{t("emailLog.colDirection", "Direction")}</th>
            <th className="py-1 pe-3 text-start font-medium">{t("emailLog.colCounterpart", "Sender / recipient")}</th>
            <th className="py-1 pe-3 text-start font-medium">{t("emailLog.colState", "State")}</th>
            <th className="py-1 pe-3 text-start font-medium">{t("emailLog.colReason", "Reason")}</th>
            <th className="py-1 text-start font-medium">{t("emailLog.colConversation", "Conversation")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <LogRow key={row.id} row={row} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** "Look up Message-ID": why EDDI did or did not answer one mail. */
function MessageIdLookup({ channelId }: { channelId: string }) {
  const { t } = useTranslation();
  const uid = useId();
  const [messageId, setMessageId] = useState("");
  const lookup = useEmailLogLookup(channelId);
  const notFound = lookup.isError && isApiError(lookup.error) && lookup.error.status === 404;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const value = messageId.trim();
    if (value) lookup.mutate(value);
  };

  return (
    <div className="space-y-2">
      <form className="flex flex-wrap items-end gap-2" onSubmit={submit}>
        <div className="min-w-64 flex-1 space-y-1">
          <label htmlFor={`${uid}-message-id`} className="text-xs font-medium">{t("emailLog.lookupLabel", "Look up Message-ID")}</label>
          <Input
            id={`${uid}-message-id`}
            data-testid="email-log-lookup-input"
            value={messageId}
            onChange={(e) => setMessageId(e.target.value)}
            placeholder="<CAF1234@mail.example.com>"
          />
        </div>
        <Button type="submit" variant="outline" size="sm" disabled={!messageId.trim() || lookup.isPending} data-testid="email-log-lookup-btn">
          <Search />
          {t("emailLog.lookupAction", "Look up")}
        </Button>
      </form>
      <p className="text-[10px] text-muted-foreground">
        {t("emailLog.lookupHint", "The Message-ID header of the mail, from the mail client's \"show original\" or \"view source\".")}
      </p>
      {notFound && (
        <p className="text-xs text-muted-foreground" data-testid="email-log-lookup-not-found">
          {t("emailLog.lookupNotFound", "No mail with this Message-ID in the log. It was never fetched (another folder, spam), or it is older than the log's retention.")}
        </p>
      )}
      {lookup.isError && !notFound && <ErrorState error={lookup.error} />}
      {lookup.data && <LogTable rows={lookup.data} testId="email-log-lookup-result" />}
    </div>
  );
}

/**
 * The mail log of an email integration: every mail it received or sent during
 * the ledger's retention, and for each one it did not act on, why. No subjects
 * or bodies — the backend has none to give.
 */
export function EmailMailLog({ channelId }: { channelId: string }) {
  const { t } = useTranslation();
  const uid = useId();
  const [filters, setFilters] = useState<EmailLogFilters>({});
  const log = useEmailLog(channelId, filters);
  const rows = log.data?.pages.flatMap((page) => page.rows) ?? [];
  // "All" removes the key, so the query key matches the unfiltered list again.
  const setFilter = (key: keyof EmailLogFilters, value: string) =>
    setFilters((prev) => {
      const next = { ...prev };
      if (value) next[key] = value as never;
      else delete next[key];
      return next;
    });
  const { hash, key: locationKey } = useLocation();
  // The owner digest links to `…#mail-log`; the section only exists once the
  // channel has loaded, after the router's own (hash-less) scroll handling.
  // Keyed on the navigation too, so following the same anchor again scrolls again.
  useEffect(() => {
    if (hash === "#mail-log") document.getElementById("mail-log")?.scrollIntoView?.({ block: "start" });
  }, [hash, locationKey]);
  // Edit access withdrawn while the page is open: a 403 on ANY of the log's
  // queries hides every row and the lookup until a later successful read confirms
  // access again (a cached list under another filter is not that). Other
  // failures (network, a failed next page) keep what is already shown.
  const [deniedAt, setDeniedAt] = useState<number | null>(null);
  // Watch every mail-log query of this integration, not only the selected one: a
  // request still in flight when the filter changed can come back 403 after its
  // query is no longer on screen, and that denial counts all the same.
  const queryClient = useQueryClient();
  useEffect(() => {
    const prefix = JSON.stringify(["channels", channelId, "email-log"]).slice(0, -1);
    const noteDenial = (error: unknown, at: number) => {
      if (isApiError(error) && error.status === 403) setDeniedAt((prev) => (prev === null || at > prev ? at : prev));
    };
    for (const query of queryClient.getQueryCache().getAll()) {
      if (JSON.stringify(query.queryKey).startsWith(prefix)) noteDenial(query.state.error, query.state.errorUpdatedAt);
    }
    return queryClient.getQueryCache().subscribe((event) => {
      if (event.type !== "updated" || !JSON.stringify(event.query.queryKey).startsWith(prefix)) return;
      noteDenial(event.query.state.error, event.query.state.errorUpdatedAt);
    });
  }, [queryClient, channelId]);
  const accessConfirmed = deniedAt !== null && log.isSuccess && !log.isError && log.dataUpdatedAt > deniedAt;
  // A read newer than the denial confirms access: lift it for every filter, so a
  // list cached before it shows again (and a later network error keeps it).
  useEffect(() => {
    if (accessConfirmed) setDeniedAt(null);
  }, [accessConfirmed]);
  const forbidden = deniedAt !== null && !accessConfirmed;

  return (
    <div className="space-y-4" data-testid="email-mail-log">
      <p className="text-xs text-muted-foreground">
        {t(
          "emailLog.description",
          "Every mail this integration received or sent within the log's retention (30 days unless the deployment changed it), and why a mail was not acted on. The mail log does not store subjects or bodies.",
        )}
      </p>
      {!forbidden && <MessageIdLookup key={channelId} channelId={channelId} />}
      <div className="flex flex-wrap gap-2">
        <label className="sr-only" htmlFor={`${uid}-direction`}>{t("emailLog.colDirection", "Direction")}</label>
        <select id={`${uid}-direction`} className={selectClass} data-testid="email-log-filter-direction" value={filters.direction ?? ""} onChange={(e) => setFilter("direction", e.target.value)}>
          <option value="">{t("emailLog.allDirections", "All directions")}</option>
          <option value="IN">{t("emailLog.directionIn", "In")}</option>
          <option value="OUT">{t("emailLog.directionOut", "Out")}</option>
        </select>
        <label className="sr-only" htmlFor={`${uid}-state`}>{t("emailLog.colState", "State")}</label>
        <select id={`${uid}-state`} className={selectClass} data-testid="email-log-filter-state" value={filters.state ?? ""} onChange={(e) => setFilter("state", e.target.value)}>
          <option value="">{t("emailLog.allStates", "All states")}</option>
          {EMAIL_LOG_STATES.map((state) => (
            <option key={state} value={state}>{state}</option>
          ))}
        </select>
        <label className="sr-only" htmlFor={`${uid}-reason`}>{t("emailLog.colReason", "Reason")}</label>
        <select id={`${uid}-reason`} className={selectClass} data-testid="email-log-filter-reason" value={filters.reason ?? ""} onChange={(e) => setFilter("reason", e.target.value)}>
          <option value="">{t("emailLog.allReasons", "All reasons")}</option>
          {EMAIL_LOG_REASON_CATEGORIES.map((reason) => (
            <option key={reason} value={reason}>{reason}</option>
          ))}
        </select>
      </div>
      {log.isLoading && <Skeleton className="h-24 w-full" />}
      {log.isError && <ErrorState error={log.error} onRetry={() => void log.refetch()} />}
      {log.isSuccess && rows.length === 0 && (
        <EmptyState icon={Inbox} title={t("emailLog.empty", "No mail in the log")} description={t("emailLog.emptyHint", "Nothing matches these filters, or the integration has not fetched any mail yet.")} />
      )}
      {rows.length > 0 && !forbidden && <LogTable rows={rows} testId="email-log-table" />}
      {log.hasNextPage && !forbidden && (
        <Button variant="outline" size="sm" onClick={() => void log.fetchNextPage()} disabled={log.isFetching} data-testid="email-log-load-more">
          {t("emailLog.loadMore", "Load more")}
        </Button>
      )}
    </div>
  );
}
