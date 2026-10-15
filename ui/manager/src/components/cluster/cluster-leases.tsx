import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { AlertTriangle, KeyRound, Search, Unlock } from "lucide-react";
import type { ActionResult, LeaseView } from "@/lib/api/cluster";
import { formatDuration, leaseFlagHelp, leaseFlagLabel } from "@/lib/cluster-labels";
import { useClusterLeases, useReleaseLease } from "@/hooks/use-cluster";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { ApiClientError } from "@/lib/api-client";

/**
 * Every lease in the cluster, suspicious first: a holder with no presence, a
 * lease that stopped being renewed, one held longer than the acquire timeout.
 */
export function ClusterLeasesPanel({ canAct }: { canAct: boolean }) {
  const { t } = useTranslation();
  const [q, setQ] = useState("");
  const [flagged, setFlagged] = useState(false);
  const { data, isLoading, isError, error, refetch } = useClusterLeases(q.trim(), flagged);
  const [target, setTarget] = useState<{ lease: LeaseView; renewed?: ActionResult } | null>(null);
  const release = useReleaseLease();

  const confirm = () => {
    if (!target?.lease.conversationId) return;
    const expected = target.renewed ? String(target.renewed.details["currentRevision"]) : target.lease.revision;
    release.mutate(
      { conversationId: target.lease.conversationId, expectedRevision: expected },
      {
        onSuccess: (r) => {
          if (r.outcome === "RENEWED") {
            // The holder renewed since the table was drawn: it is alive. Ask again,
            // with what that means, instead of releasing behind the admin's back.
            setTarget({ lease: target.lease, renewed: r });
            return;
          }
          toast.success(r.message);
          setTarget(null);
        },
        onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
      },
    );
  };

  return (
    <section className="space-y-4" aria-labelledby="cluster-leases-heading" data-testid="cluster-leases">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <h2 id="cluster-leases-heading" className="flex items-center gap-2 text-lg font-semibold text-foreground">
          <KeyRound className="h-5 w-5 text-primary" aria-hidden="true" />
          {t("cluster.leases.title", "Conversation leases")}
        </h2>
        {data && (
          <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground" data-testid="cluster-leases-count">
            {t("cluster.leases.count", "{{total}} held · {{suspicious}} suspicious", { total: data.total, suspicious: data.suspicious })}
          </span>
        )}
        <div className="flex flex-1 flex-wrap items-center justify-end gap-2">
          <div className="relative w-full max-w-xs">
            <Search className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input
              className="ps-9"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder={t("cluster.leases.search", "Conversation, agent or node…")}
              aria-label={t("cluster.leases.search", "Conversation, agent or node…")}
              data-testid="cluster-leases-search"
            />
          </div>
          <label className="inline-flex items-center gap-2 text-sm text-foreground">
            <input type="checkbox" checked={flagged} onChange={(e) => setFlagged(e.target.checked)} data-testid="cluster-leases-flagged" />
            {t("cluster.leases.onlySuspicious", "Only suspicious")}
          </label>
        </div>
      </div>
      <p className="text-sm text-muted-foreground">
        {t(
          "cluster.leases.explain",
          "A lease lets exactly one node run a conversation's turn. Its revision is the fencing token: the database refuses a write that carries an older one.",
        )}
      </p>

      {isLoading ? (
        <div className="space-y-2" data-testid="cluster-leases-loading">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      ) : isError && !data ? (
        <ErrorState
          message={
            error instanceof ApiClientError && error.status === 409
              ? t("cluster.leases.natsDown", "Leases live in NATS, which the answering node cannot reach right now. They show again as soon as it reconnects.")
              : t("common.error", "Something went wrong")
          }
          onRetry={() => refetch()}
          retryLabel={t("common.retry", "Retry")}
        />
      ) : !data || data.leases.length === 0 ? (
        <EmptyState
          icon={KeyRound}
          title={q || flagged ? t("common.noResults", "No results found") : t("cluster.leases.empty", "No lease is held — no turn is running")}
        />
      ) : (
        <div className="overflow-x-auto rounded-xl border border-border/50">
          <table className="w-full text-sm" data-testid="cluster-leases-table">
            <thead>
              <tr className="border-b border-border bg-muted/50 text-muted-foreground">
                <th className="px-4 py-3 text-start font-medium">{t("cluster.leases.colConversation", "Conversation / key")}</th>
                <th className="px-4 py-3 text-start font-medium">{t("cluster.leases.colAgent", "Agent")}</th>
                <th className="px-4 py-3 text-start font-medium">{t("cluster.leases.colHolder", "Holder")}</th>
                <th className="px-4 py-3 text-end font-medium">{t("cluster.leases.colAge", "Held for")}</th>
                <th className="px-4 py-3 text-end font-medium">{t("cluster.leases.colRenewed", "Renewed")}</th>
                <th className="px-4 py-3 text-end font-medium">{t("cluster.leases.colFence", "Fence")}</th>
                <th className="px-4 py-3 text-start font-medium">{t("cluster.leases.colFlags", "Flags")}</th>
                {canAct && <th className="px-4 py-3 text-end font-medium">{t("cluster.colActions", "Actions")}</th>}
              </tr>
            </thead>
            <tbody>
              {data.leases.map((lease) => (
                <tr
                  key={lease.key}
                  className={`border-b border-border/30 ${lease.flags.length > 0 ? "bg-warning/5" : ""}`}
                  data-testid={`cluster-lease-${lease.key}`}
                >
                  <td className="max-w-[16rem] px-4 py-2">
                    {lease.conversationId ? (
                      <Link to={`/manage/conversationview/${lease.conversationId}`} className="truncate font-mono text-xs text-foreground hover:text-primary">
                        {lease.conversationId}
                      </Link>
                    ) : (
                      <span className="font-mono text-xs text-muted-foreground">{lease.key}</span>
                    )}
                    {lease.conversationState && <div className="text-[11px] text-muted-foreground">{lease.conversationState}</div>}
                  </td>
                  <td className="px-4 py-2 font-mono text-xs text-muted-foreground">{lease.agentId ?? "—"}</td>
                  <td className="px-4 py-2">
                    <span className="font-mono text-xs">{lease.holderNode}</span>{" "}
                    {lease.holderStatus !== "LIVE" && (
                      <Badge variant="destructive" className="ms-1">
                        {lease.holderStatus}
                      </Badge>
                    )}
                  </td>
                  <td className="px-4 py-2 text-end tabular-nums">{formatDuration(lease.ageMs)}</td>
                  <td className="px-4 py-2 text-end tabular-nums">
                    {lease.sinceRenewalMs >= 0 ? t("cluster.ago", "{{duration}} ago", { duration: formatDuration(lease.sinceRenewalMs) }) : "—"}
                  </td>
                  <td className="px-4 py-2 text-end font-mono text-xs tabular-nums">{lease.revision}</td>
                  <td className="px-4 py-2">
                    <ul className="flex flex-wrap gap-1">
                      {lease.flags.map((f) => (
                        <li key={f}>
                          <Badge variant="warning" title={leaseFlagHelp(t, f)} data-testid={`cluster-lease-flag-${f}`}>
                            <AlertTriangle className="me-1 h-3 w-3" aria-hidden="true" />
                            {leaseFlagLabel(t, f)}
                          </Badge>
                        </li>
                      ))}
                    </ul>
                  </td>
                  {canAct && (
                    <td className="px-4 py-2 text-end">
                      {lease.kind === "conversation" && (
                        <Button
                          size="sm"
                          variant={lease.flags.length > 0 ? "warning" : "ghost"}
                          onClick={() => setTarget({ lease })}
                          aria-label={t("cluster.leases.releaseAria", "Force-release the lease of {{id}}", { id: lease.conversationId })}
                          data-testid={`cluster-release-${lease.conversationId}`}
                        >
                          <Unlock aria-hidden="true" />
                          {t("cluster.leases.release", "Release")}
                        </Button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
          {data.truncated && (
            <p className="p-3 text-xs text-muted-foreground">{t("cluster.leases.truncated", "Only the first 5,000 leases were read.")}</p>
          )}
        </div>
      )}

      <AlertDialog
        open={target !== null}
        onOpenChange={(o) => !o && setTarget(null)}
        variant={target?.renewed ? "destructive" : "warning"}
        title={
          target?.renewed
            ? t("cluster.release.renewedTitle", "The holder is alive — release anyway?")
            : t("cluster.release.title", "Force-release the lease of {{id}}?", { id: target?.lease.conversationId ?? "" })
        }
        description={
          target?.renewed
            ? t(
                "cluster.release.renewedText",
                "{{node}} renewed this lease after you looked, so its turn is still running. Releasing it cancels that turn at its next step; if it still tries to write, the fence refuses the write and the turn is dead-lettered for replay.",
                { node: target.lease.holderNode },
              )
            : t(
                "cluster.release.text",
                "The lease held by {{node}} is deleted and the next turn of this conversation can run at once, on any node, with a newer fencing token. This is safe even if {{node}} is still alive: the fence refuses its late write and the turn is dead-lettered, where you can replay it. Nothing else changes.",
                { node: target?.lease.holderNode ?? "" },
              )
        }
        confirmLabel={t("cluster.leases.release", "Release")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={confirm}
        isPending={release.isPending}
      />
    </section>
  );
}
