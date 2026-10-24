import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { Check, ExternalLink, RefreshCw } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useAcknowledgeAlert, useRetryAlert } from "@/hooks/use-alerts";
import type { AlertEvent, DeliveryStatus, EventState } from "@/lib/api/alerts";

interface AlertEventsTableProps {
  events: AlertEvent[];
  selected: ReadonlySet<string>;
  onSelectedChange: (next: Set<string>) => void;
}

const STATE_VARIANT: Record<EventState, "default" | "secondary" | "success" | "warning" | "destructive" | "outline"> = {
  PENDING: "warning",
  DELIVERED: "success",
  FAILED: "destructive",
  INBOX_ONLY: "secondary",
  DIGEST_PENDING: "outline",
  DIGESTED: "secondary",
};

const DELIVERY_VARIANT: Record<DeliveryStatus, "success" | "warning" | "destructive"> = {
  PENDING: "warning",
  DELIVERED: "success",
  FAILED: "destructive",
};

/**
 * The alert inbox. An event holds no user id and no conversation text; the one
 * piece of user-written text is a rating comment excerpt, rendered as plain text
 * (React escapes it).
 */
export function AlertEventsTable({ events, selected, onSelectedChange }: AlertEventsTableProps) {
  const { t, i18n } = useTranslation();
  const acknowledge = useAcknowledgeAlert();
  const retry = useRetryAlert();

  const toggle = (id: string) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    onSelectedChange(next);
  };

  const date = (iso: string) => new Date(iso).toLocaleString(i18n.language, { dateStyle: "medium", timeStyle: "short" });

  const doAcknowledge = async (event: AlertEvent) => {
    try {
      await acknowledge.mutateAsync(event.id);
    } catch {
      toast.error(t("alerts.acknowledgeFailed", "The alert could not be acknowledged."));
    }
  };

  const doRetry = async (event: AlertEvent) => {
    try {
      await retry.mutateAsync(event.id);
      toast.success(t("alerts.retryQueued", "Delivery is being retried."));
    } catch {
      toast.error(t("alerts.retryFailed", "The retry could not be started."));
    }
  };

  const selectable = events.filter((e) => !e.acknowledgedAt);

  return (
    <div className="overflow-x-auto rounded-xl border border-border/50" data-testid="alert-events-table">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b bg-muted/50 text-muted-foreground">
            <th className="w-10 px-4 py-3">
              <input
                type="checkbox"
                aria-label={t("alerts.selectAll", "Select all shown")}
                checked={selectable.length > 0 && selectable.every((e) => selected.has(e.id))}
                onChange={(e) => onSelectedChange(e.target.checked ? new Set(selectable.map((ev) => ev.id)) : new Set())}
                className="h-4 w-4 accent-primary"
                data-testid="alert-events-select-all"
              />
            </th>
            <th className="px-4 py-3 text-start font-medium">{t("alerts.colAlert", "Alert")}</th>
            <th className="px-4 py-3 text-start font-medium">{t("alerts.colFired", "Fired")}</th>
            <th className="px-4 py-3 text-start font-medium">{t("alerts.colDelivery", "Delivery")}</th>
            <th className="px-4 py-3 text-end font-medium">{t("alerts.colActions", "Actions")}</th>
          </tr>
        </thead>
        <tbody>
          {events.map((event) => {
            const failed = event.deliveries.some((d) => d.status === "FAILED");
            return (
              <tr key={event.id} className="border-b border-border/50 align-top last:border-0" data-testid={`alert-event-row-${event.id}`}>
                <td className="px-4 py-3">
                  {!event.acknowledgedAt && (
                    <input
                      type="checkbox"
                      aria-label={t("alerts.selectOne", "Select this alert")}
                      checked={selected.has(event.id)}
                      onChange={() => toggle(event.id)}
                      className="h-4 w-4 accent-primary"
                      data-testid={`alert-event-select-${event.id}`}
                    />
                  )}
                </td>
                <td className="max-w-xl px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant={STATE_VARIANT[event.state]} data-testid={`alert-event-state-${event.id}`}>
                      {t(`alerts.state.${event.state}`, event.state)}
                    </Badge>
                    {event.kind === "DIGEST" && <Badge variant="outline">{t("alerts.digest", "Digest")}</Badge>}
                    {event.acknowledgedAt && (
                      <Badge variant="secondary" data-testid={`alert-event-acknowledged-${event.id}`}>
                        {t("alerts.acknowledged", "Acknowledged")}
                      </Badge>
                    )}
                  </div>
                  <p className="mt-1 font-medium text-foreground">{event.summary}</p>
                  {event.match?.commentExcerpt && (
                    <blockquote className="mt-1 border-s-2 border-border ps-2 text-muted-foreground" data-testid={`alert-event-comment-${event.id}`}>
                      {event.match.commentExcerpt}
                    </blockquote>
                  )}
                  {event.kind === "DIGEST" && event.digestItems && (
                    <ul className="mt-1 list-disc ps-5 text-xs text-muted-foreground">
                      {event.digestItems.slice(0, 5).map((item) => (
                        <li key={item}>{item}</li>
                      ))}
                      {(event.digestCount ?? 0) > 5 && (
                        <li>{t("alerts.digestMore", { n: (event.digestCount ?? 0) - 5, defaultValue: "… and {{n}} more" })}</li>
                      )}
                    </ul>
                  )}
                  {event.duplicates > 0 && (
                    <p className="mt-1 text-xs text-muted-foreground">
                      {t("alerts.duplicates", { n: event.duplicates, defaultValue: "Repeated {{n}} more times in this window" })}
                    </p>
                  )}
                </td>
                <td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{date(event.firedAt)}</td>
                <td className="px-4 py-3">
                  {event.deliveries.length === 0 ? (
                    <span className="text-xs text-muted-foreground">{t("alerts.inboxOnly", "Inbox only")}</span>
                  ) : (
                    <ul className="space-y-1">
                      {event.deliveries.map((d) => (
                        <li key={d.targetKey} className="flex flex-wrap items-center gap-2" data-testid={`alert-delivery-${event.id}-${d.targetKey}`}>
                          <Badge variant={DELIVERY_VARIANT[d.status]}>{t(`alerts.delivery.${d.status}`, d.status)}</Badge>
                          <span className="text-xs">{d.label}</span>
                          {d.lastError && (
                            <span className="text-xs text-destructive" title={d.lastError}>
                              {d.lastError}
                            </span>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </td>
                <td className="px-4 py-3">
                  <div className="flex justify-end gap-1">
                    {event.conversationId && (
                      <Button asChild size="sm" variant="ghost">
                        <Link to={`/manage/conversationview/${encodeURIComponent(event.conversationId)}`} data-testid={`alert-open-conversation-${event.id}`}>
                          <ExternalLink className="h-4 w-4" />
                          <span className="sr-only">{t("alerts.openConversation", "Open conversation")}</span>
                        </Link>
                      </Button>
                    )}
                    {failed && (
                      <Button size="sm" variant="outline" onClick={() => void doRetry(event)} disabled={retry.isPending} data-testid={`alert-retry-${event.id}`}>
                        <RefreshCw className="h-4 w-4" />
                        {t("alerts.retry", "Retry")}
                      </Button>
                    )}
                    {!event.acknowledgedAt && (
                      <Button size="sm" variant="outline" onClick={() => void doAcknowledge(event)} disabled={acknowledge.isPending} data-testid={`alert-acknowledge-${event.id}`}>
                        <Check className="h-4 w-4" />
                        {t("alerts.acknowledge", "Acknowledge")}
                      </Button>
                    )}
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
