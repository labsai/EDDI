import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import { History, Inbox, RefreshCw, Rocket, ShieldCheck } from "lucide-react";
import { getAuditTrail } from "@/lib/api/audit";
import type { ActionResult } from "@/lib/api/cluster";
import { useForwardLocalDeadLetters, useReconcileDeployments, useResyncCaches } from "@/hooks/use-cluster";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/shared/error-state";

type ActionId = "resync" | "reconcile" | "forward";

/**
 * The cluster-wide recovery actions. Each one says what it does before it runs,
 * is recorded in the audit trail and on the activity timeline, and is safe to
 * repeat. Lease release and drain live where their target is shown (lease
 * table, node card).
 */
export function ClusterRecoveryActions({ localDeadLetters, clustered }: { localDeadLetters: number; clustered: boolean }) {
  const { t } = useTranslation();
  const resync = useResyncCaches();
  const reconcile = useReconcileDeployments();
  const forward = useForwardLocalDeadLetters();
  const [open, setOpen] = useState<ActionId | null>(null);
  const [last, setLast] = useState<ActionResult | null>(null);

  const run = (id: ActionId) => {
    const mutation = id === "resync" ? resync : id === "reconcile" ? reconcile : forward;
    mutation.mutate(undefined, {
      onSuccess: (r) => {
        setLast(r);
        toast.success(r.message);
        setOpen(null);
      },
      onError: (e) => {
        toast.error(e instanceof Error ? e.message : String(e));
        setOpen(null);
      },
    });
  };

  const dialogs: Record<ActionId, { title: string; description: string; confirm: string }> = {
    resync: {
      title: t("cluster.action.resyncTitle", "Resync every cache in the cluster?"),
      description: t(
        "cluster.action.resyncText",
        "Every node flushes its invalidatable caches (secrets, variables, connections, deployments, triggers, conversation states) and reloads them from the database. No data changes; expect a short burst of database reads. Use it when a node serves stale configuration.",
      ),
      confirm: t("cluster.action.resync", "Resync caches"),
    },
    reconcile: {
      title: t("cluster.action.reconcileTitle", "Reconcile deployments now?"),
      description: t(
        "cluster.action.reconcileText",
        "Every node runs its deployment sweep at once instead of within 10 seconds: it deploys what the database lists as deployed and undeploys what it no longer lists. The same work as the periodic sweep, so it is harmless to repeat.",
      ),
      confirm: t("cluster.action.reconcile", "Reconcile deployments"),
    },
    forward: {
      title: t("cluster.action.forwardTitle", "Forward locally kept dead letters?"),
      description: t(
        "cluster.action.forwardText",
        "Nodes that could not reach NATS kept their dead letters in memory, where a restart would lose them. Each node now moves them to the shared stream (it also does this by itself on reconnect). Nothing is replayed.",
      ),
      confirm: t("cluster.action.forward", "Forward dead letters"),
    },
  };

  const pending = resync.isPending || reconcile.isPending || forward.isPending;
  const current = open ? dialogs[open] : null;

  return (
    <section className="rounded-xl border border-border bg-card p-5" aria-labelledby="cluster-recovery-heading" data-testid="cluster-recovery">
      <h2 id="cluster-recovery-heading" className="flex items-center gap-2 text-lg font-semibold text-foreground">
        <ShieldCheck className="h-5 w-5 text-primary" aria-hidden="true" />
        {t("cluster.action.title", "Recovery actions")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t(
          "cluster.action.subtitle",
          "Safe to repeat and audited. Force-releasing a lease and draining a node are on the lease and node they act on. Nothing here can delete data or bypass fencing.",
        )}
      </p>
      <div className="mt-4 flex flex-wrap gap-2">
        <Button variant="outline" disabled={!clustered || pending} onClick={() => setOpen("resync")} data-testid="cluster-action-resync">
          <RefreshCw aria-hidden="true" />
          {dialogs.resync.confirm}
        </Button>
        <Button variant="outline" disabled={pending} onClick={() => setOpen("reconcile")} data-testid="cluster-action-reconcile">
          <Rocket aria-hidden="true" />
          {dialogs.reconcile.confirm}
        </Button>
        <Button variant="outline" disabled={!clustered || pending} onClick={() => setOpen("forward")} data-testid="cluster-action-forward">
          <Inbox aria-hidden="true" />
          {dialogs.forward.confirm}
          {localDeadLetters > 0 ? ` (${localDeadLetters})` : ""}
        </Button>
      </div>
      {last && (
        <p className="mt-3 text-sm text-foreground" role="status" data-testid="cluster-action-result">
          <span className="font-semibold">{last.outcome}</span> — {last.message}
        </p>
      )}
      <AlertDialog
        open={current !== null}
        onOpenChange={(o) => !o && setOpen(null)}
        variant="warning"
        title={current?.title ?? ""}
        description={current?.description ?? ""}
        confirmLabel={current?.confirm ?? ""}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={() => open && run(open)}
        isPending={pending}
      />
    </section>
  );
}

/** The newest entries of the `cluster-admin` audit trail: who did what, when. */
export function ClusterAuditTrail() {
  const { t } = useTranslation();
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ["audit", "trail", "cluster-admin", 0, 50],
    queryFn: () => getAuditTrail("cluster-admin", 0, 50),
    refetchInterval: 10000,
  });
  const entries = [...(data ?? [])].sort((a, b) => (b.timestamp ?? "").localeCompare(a.timestamp ?? "")).slice(0, 10);
  return (
    <section className="rounded-xl border border-border bg-card p-5" aria-labelledby="cluster-audit-heading" data-testid="cluster-audit">
      <h2 id="cluster-audit-heading" className="flex items-center gap-2 text-lg font-semibold text-foreground">
        <History className="h-5 w-5 text-primary" aria-hidden="true" />
        {t("cluster.audit.title", "Audit trail of recovery actions")}
      </h2>
      {isLoading ? (
        <p className="mt-3 text-sm text-muted-foreground">{t("common.loading", "Loading...")}</p>
      ) : isError && !data ? (
        <div className="mt-3">
          <ErrorState message={t("common.error", "Something went wrong")} onRetry={() => refetch()} retryLabel={t("common.retry", "Retry")} />
        </div>
      ) : entries.length === 0 ? (
        <p className="mt-3 text-sm text-muted-foreground" data-testid="cluster-audit-empty">
          {t("cluster.audit.empty", "No recovery action has been taken yet.")}
        </p>
      ) : (
        <ul className="mt-3 divide-y divide-border/50 text-sm">
          {entries.map((e) => (
            <li key={e.id} className="flex flex-wrap items-baseline gap-x-3 py-2" data-testid={`cluster-audit-${e.id}`}>
              <time className="tabular-nums text-muted-foreground" dateTime={e.timestamp}>
                {new Date(e.timestamp).toLocaleString()}
              </time>
              <span className="font-mono text-xs">{(e.actions ?? []).join(", ")}</span>
              <span className="text-foreground">{e.userId ?? "—"}</span>
              <span className="text-muted-foreground">{String((e.output ?? {})["outcome"] ?? "")}</span>
            </li>
          ))}
        </ul>
      )}
      <p className="mt-2 text-xs text-muted-foreground">
        {t("cluster.audit.hint", "Recorded in the tamper-evident audit ledger under the trail “cluster-admin”, a few seconds after each action.")}
      </p>
    </section>
  );
}
