import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Crown, PauseCircle, PlayCircle, Server, ServerOff } from "lucide-react";
import type { ClusterNode } from "@/lib/api/cluster";
import { formatDuration, nodeStateLabel } from "@/lib/cluster-labels";
import { useDrainNode } from "@/hooks/use-cluster";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface NodeGridProps {
  nodes: ClusterNode[];
  canAct: boolean;
  leaseTtlMs: number;
}

/** "Which node is doing what?" — one card per replica, live or gone. */
export function ClusterNodeGrid({ nodes, canAct, leaseTtlMs }: NodeGridProps) {
  const { t } = useTranslation();
  const drain = useDrainNode();
  const [target, setTarget] = useState<{ node: ClusterNode; drain: boolean } | null>(null);

  const confirm = () => {
    if (!target) return;
    drain.mutate(
      { nodeId: target.node.nodeId, drain: target.drain },
      {
        onSuccess: (r) => {
          toast.success(r.message);
          setTarget(null);
        },
        onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
      },
    );
  };

  return (
    <section aria-labelledby="cluster-nodes-heading" data-testid="coordinator-queues">
      <h2 id="cluster-nodes-heading" className="mb-3 text-lg font-semibold text-foreground">
        {t("cluster.nodes.title", "Nodes")}
      </h2>
      <ul className="cq-card-grid" data-testid="cluster-nodes">
        {nodes.map((node) => (
          <NodeCard key={node.nodeId} node={node} canAct={canAct} leaseTtlMs={leaseTtlMs} onDrain={(d) => setTarget({ node, drain: d })} />
        ))}
      </ul>
      <AlertDialog
        open={target !== null}
        onOpenChange={(open) => !open && setTarget(null)}
        variant="warning"
        title={
          target?.drain
            ? t("cluster.drain.title", "Drain node {{node}}?", { node: target?.node.nodeId ?? "" })
            : t("cluster.undrain.title", "Undrain node {{node}}?", { node: target?.node.nodeId ?? "" })
        }
        description={
          target?.drain
            ? t(
                "cluster.drain.description",
                "The node stops taking new turns: a turn that reaches it is answered 409 with Retry-After and the client retries on another node. Its readiness check reports DOWN so Kubernetes stops routing to it. Turns already running finish. Use it before restarting the node; undrain or a restart ends it.",
              )
            : t("cluster.undrain.description", "The node takes turns again and reports itself ready.")
        }
        confirmLabel={target?.drain ? t("cluster.drain.confirm", "Drain") : t("cluster.undrain.confirm", "Undrain")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={confirm}
        isPending={drain.isPending}
      />
    </section>
  );
}

function Metric({ label, value, testId }: { label: string; value: string | number; testId?: string }) {
  return (
    <div className="min-w-0">
      <dt className="truncate text-[11px] uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className="truncate font-mono text-sm tabular-nums text-foreground" data-testid={testId}>
        {value}
      </dd>
    </div>
  );
}

function NodeCard({
  node,
  canAct,
  leaseTtlMs,
  onDrain,
}: {
  node: ClusterNode;
  canAct: boolean;
  leaseTtlMs: number;
  onDrain: (drain: boolean) => void;
}) {
  const { t } = useTranslation();
  const gone = node.state === "LOST" || node.state === "LEFT";
  const variant =
    node.state === "LIVE" ? (node.degraded ? "warning" : "success") : node.state === "LEFT" || node.state === "UNKNOWN" ? "secondary" : "destructive";
  const Icon = gone ? ServerOff : Server;
  return (
    <li
      className={cn(
        "rounded-xl border bg-card p-4",
        node.state === "LOST" ? "border-destructive/50" : node.state === "STALE" ? "border-warning/50" : "border-border",
        gone && "opacity-80",
      )}
      data-testid={`cluster-node-${node.nodeId}`}
      data-state={node.state}
    >
      <div className="flex items-start gap-3">
        <Icon className={cn("mt-0.5 h-5 w-5 shrink-0", gone ? "text-destructive" : "text-primary")} aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <h3 className="truncate font-mono text-sm font-semibold text-foreground" title={node.nodeId}>
              {node.nodeId}
            </h3>
            <Badge variant={variant} data-testid={`cluster-node-state-${node.nodeId}`}>
              {nodeStateLabel(t, node.state)}
            </Badge>
            {node.self && <Badge variant="outline">{t("cluster.node.self", "answering")}</Badge>}
            {node.hitlLeader && (
              <Badge variant="secondary" title={t("cluster.node.leaderHelp", "Runs the HITL crash-recovery sweep for the cluster")}>
                <Crown className="me-1 h-3 w-3" aria-hidden="true" />
                {t("cluster.node.leader", "HITL leader")}
              </Badge>
            )}
            {node.draining && (
              <Badge variant="warning" data-testid={`cluster-node-draining-${node.nodeId}`}>
                {t("cluster.node.draining", "Drained")}
              </Badge>
            )}
            {node.degraded && !gone && <Badge variant="warning">{t("cluster.node.degraded", "No NATS")}</Badge>}
          </div>
          <p className="mt-0.5 truncate text-xs text-muted-foreground">
            {[node.host, node.version ? `v${node.version}` : null].filter(Boolean).join(" · ")}
          </p>
        </div>
      </div>

      {gone ? (
        <p className="mt-3 text-sm text-foreground/90" data-testid={`cluster-node-gone-${node.nodeId}`}>
          {node.state === "LOST"
            ? Date.now() - (node.lastHeartbeat || Date.now()) > leaseTtlMs
              ? t(
                  "cluster.node.lostExpiredText",
                  "Stopped heartbeating {{ago}} ago without leaving — killed, crashed or cut off from NATS. Its leases have expired: other nodes run those conversations now.",
                  { ago: formatDuration(Date.now() - (node.lastHeartbeat || Date.now())) },
                )
              : t(
                  "cluster.node.lostText",
                  "Stopped heartbeating {{ago}} ago without leaving — killed, crashed or cut off from NATS. The leases it held ({{leases}}) are taken over within {{ttl}}.",
                  { ago: formatDuration(Date.now() - (node.lastHeartbeat || Date.now())), leases: node.leasesHeld, ttl: formatDuration(leaseTtlMs) },
                )
            : t("cluster.node.leftText", "Shut down cleanly {{ago}} ago and released its leases.", {
                ago: formatDuration(Date.now() - (node.goneSince ?? Date.now())),
              })}
        </p>
      ) : (
        <dl className="mt-3 grid grid-cols-3 gap-x-3 gap-y-2">
          <Metric label={t("cluster.node.heartbeat", "Heartbeat")} value={t("cluster.ago", "{{duration}} ago", { duration: formatDuration(node.heartbeatAgeMs) })} />
          <Metric label={t("cluster.node.uptime", "Uptime")} value={node.startedAt ? formatDuration(Date.now() - node.startedAt) : "—"} />
          <Metric label={t("cluster.node.rtt", "NATS RTT")} value={node.natsRtt >= 0 ? `${node.natsRtt} ms` : "—"} />
          <Metric label={t("cluster.node.inFlight", "Active turns")} value={node.activeConversations} testId={`cluster-node-active-${node.nodeId}`} />
          <Metric label={t("cluster.node.leases", "Leases")} value={node.leasesHeld} testId={`cluster-node-leases-${node.nodeId}`} />
          <Metric label={t("cluster.node.queue", "Queued")} value={node.queueDepthTotal} />
          {node.state === "UNKNOWN" && (
            <div className="col-span-3 text-xs text-muted-foreground" data-testid={`cluster-node-unknown-${node.nodeId}`}>
              {t("cluster.node.unknownText", "The answering node cannot reach NATS, so it cannot see whether this node still runs. These are its last known numbers.")}
            </div>
          )}
          {node.localDeadLetters > 0 && (
            <div className="col-span-3 text-xs text-warning">
              {t("cluster.node.localDeadLetters", "{{n}} dead letters kept locally, waiting for NATS", { n: node.localDeadLetters })}
            </div>
          )}
        </dl>
      )}

      {canAct && !gone && node.state !== "UNKNOWN" && (
        <div className="mt-3 flex justify-end">
          {node.draining ? (
            <Button size="sm" variant="outline" onClick={() => onDrain(false)} data-testid={`cluster-undrain-${node.nodeId}`}>
              <PlayCircle aria-hidden="true" />
              {t("cluster.undrain.confirm", "Undrain")}
            </Button>
          ) : (
            <Button size="sm" variant="outline" onClick={() => onDrain(true)} data-testid={`cluster-drain-${node.nodeId}`}>
              <PauseCircle aria-hidden="true" />
              {t("cluster.drain.confirm", "Drain")}
            </Button>
          )}
        </div>
      )}
    </li>
  );
}
