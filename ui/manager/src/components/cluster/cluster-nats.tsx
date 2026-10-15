import { useTranslation } from "react-i18next";
import { Database, Radio } from "lucide-react";
import type { NatsView, PeerView } from "@/lib/api/cluster";
import { formatBytes, formatDuration } from "@/lib/cluster-labels";
import { Badge } from "@/components/ui/badge";

function PeerList({ peers }: { peers: PeerView[] }) {
  const { t } = useTranslation();
  if (peers.length === 0) return <span className="text-muted-foreground">—</span>;
  return (
    <ul className="flex flex-wrap gap-1">
      {peers.map((p, i) => (
        <li key={`${p.name}-${i}`}>
          <Badge
            variant={p.offline ? "destructive" : p.current ? "success" : "warning"}
            title={
              p.offline
                ? t("cluster.nats.peerOffline", "offline")
                : p.current
                  ? i === 0
                    ? t("cluster.nats.peerLeader", "leader")
                    : t("cluster.nats.peerCurrent", "current")
                  : t("cluster.nats.peerBehind", "behind by {{lag}}", { lag: p.lag })
            }
          >
            {p.name}
            {i === 0 ? " ★" : p.offline ? " ✕" : p.current ? "" : ` +${p.lag}`}
          </Badge>
        </li>
      ))}
    </ul>
  );
}

/**
 * NATS and JetStream as the answering node sees them: which server it talks
 * to, every stream and KV bucket of this deployment with its replicas and
 * their state, consumer lag, and the generation of the leases bucket (the
 * fencing-token epoch).
 */
export function ClusterNatsPanel({ nats }: { nats: NatsView }) {
  const { t } = useTranslation();
  const maxLag = (consumers: { pending: number }[]) => consumers.reduce((m, c) => Math.max(m, c.pending), 0);
  return (
    <section className="rounded-xl border border-border bg-card" aria-labelledby="cluster-nats-heading" data-testid="cluster-nats">
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-5 py-4">
        <Radio className="h-5 w-5 text-primary" aria-hidden="true" />
        <h2 id="cluster-nats-heading" className="text-lg font-semibold text-foreground">
          {t("cluster.nats.title", "NATS & JetStream")}
        </h2>
        <Badge variant={nats.status === "CONNECTED" ? "success" : "destructive"} data-testid="cluster-nats-status">
          {nats.status}
        </Badge>
      </div>
      <div className="space-y-5 p-5">
        <dl className="grid grid-cols-2 gap-4 text-sm md:grid-cols-4">
          <div>
            <dt className="text-xs text-muted-foreground">{t("cluster.nats.server", "Connected to")}</dt>
            <dd className="truncate font-mono" title={nats.connectedUrl ?? ""}>
              {nats.serverName ?? "—"} {nats.serverVersion ? `(${nats.serverVersion})` : ""}
            </dd>
          </div>
          <div>
            <dt className="text-xs text-muted-foreground">{t("cluster.nats.cluster", "NATS cluster")}</dt>
            <dd className="font-mono">{nats.clusterName ?? "—"}</dd>
          </div>
          <div>
            <dt className="text-xs text-muted-foreground">{t("cluster.nats.rtt", "Round trip")}</dt>
            <dd className="font-mono">{nats.rttMillis >= 0 ? `${nats.rttMillis} ms` : "—"}</dd>
          </div>
          <div>
            <dt className="text-xs text-muted-foreground">{t("cluster.nats.reconnects", "Reconnects since boot")}</dt>
            <dd className="font-mono">{nats.reconnects}</dd>
          </div>
          <div className="col-span-2 md:col-span-4">
            <dt className="text-xs text-muted-foreground">{t("cluster.nats.servers", "Known servers")}</dt>
            <dd className="flex flex-wrap gap-1 font-mono text-xs" data-testid="cluster-nats-servers">
              {nats.knownServers.length === 0 ? "—" : nats.knownServers.map((s) => <Badge key={s} variant="outline">{s}</Badge>)}
            </dd>
          </div>
        </dl>

        {nats.error && (
          <p className="rounded-lg border border-warning/40 bg-warning/5 p-3 text-sm text-foreground" data-testid="cluster-nats-error">
            {t("cluster.nats.partial", "Part of the JetStream view could not be read: {{error}}", { error: nats.error })}
          </p>
        )}

        {nats.leaseEpoch && (
          <p className="text-sm text-muted-foreground" data-testid="cluster-lease-epoch">
            {t(
              "cluster.nats.epoch",
              "Leases bucket generation: first fencing token {{first}}, latest {{last}}. A recreated bucket starts above every earlier token, so fencing survives losing NATS data.",
              { first: nats.leaseEpoch.firstRevision, last: nats.leaseEpoch.lastRevision },
            )}
          </p>
        )}

        <div>
          <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold text-foreground">
            <Database className="h-4 w-4" aria-hidden="true" />
            {t("cluster.nats.streams", "Streams")}
          </h3>
          <div className="overflow-x-auto rounded-lg border border-border/50">
            <table className="w-full text-sm" data-testid="cluster-streams">
              <thead>
                <tr className="border-b border-border bg-muted/50 text-muted-foreground">
                  <th className="px-3 py-2 text-start font-medium">{t("cluster.nats.colName", "Name")}</th>
                  <th className="px-3 py-2 text-end font-medium">{t("cluster.nats.colMessages", "Messages")}</th>
                  <th className="px-3 py-2 text-end font-medium">{t("cluster.nats.colBytes", "Size")}</th>
                  <th className="px-3 py-2 text-end font-medium">{t("cluster.nats.colLag", "Max consumer lag")}</th>
                  <th className="px-3 py-2 text-start font-medium">{t("cluster.nats.colReplicas", "Replicas (leader ★)")}</th>
                </tr>
              </thead>
              <tbody>
                {nats.streams.map((s) => (
                  <tr key={s.name} className="border-b border-border/30" data-testid={`cluster-stream-${s.name}`}>
                    <td className="px-3 py-2 font-mono text-xs">{s.name}</td>
                    <td className="px-3 py-2 text-end tabular-nums">{s.messages.toLocaleString()}</td>
                    <td className="px-3 py-2 text-end tabular-nums">{formatBytes(s.bytes)}</td>
                    <td className="px-3 py-2 text-end tabular-nums">{s.consumers.length > 0 ? maxLag(s.consumers).toLocaleString() : "—"}</td>
                    <td className="px-3 py-2">
                      <PeerList peers={s.peers} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div>
          <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold text-foreground">
            <Database className="h-4 w-4" aria-hidden="true" />
            {t("cluster.nats.buckets", "KV buckets")}
          </h3>
          <div className="overflow-x-auto rounded-lg border border-border/50">
            <table className="w-full text-sm" data-testid="cluster-buckets">
              <thead>
                <tr className="border-b border-border bg-muted/50 text-muted-foreground">
                  <th className="px-3 py-2 text-start font-medium">{t("cluster.nats.colName", "Name")}</th>
                  <th className="px-3 py-2 text-end font-medium">{t("cluster.nats.colValues", "Values")}</th>
                  <th className="px-3 py-2 text-end font-medium">{t("cluster.nats.colBytes", "Size")}</th>
                  <th className="px-3 py-2 text-end font-medium">{t("cluster.nats.colTtl", "TTL")}</th>
                  <th className="px-3 py-2 text-start font-medium">{t("cluster.nats.colReplicas", "Replicas (leader ★)")}</th>
                </tr>
              </thead>
              <tbody>
                {nats.buckets.map((b) => (
                  <tr key={b.bucket} className="border-b border-border/30" data-testid={`cluster-bucket-${b.name}`}>
                    <td className="px-3 py-2 font-mono text-xs">{b.name}</td>
                    <td className="px-3 py-2 text-end tabular-nums">{b.values.toLocaleString()}</td>
                    <td className="px-3 py-2 text-end tabular-nums">{formatBytes(b.bytes)}</td>
                    <td className="px-3 py-2 text-end tabular-nums">{b.ttlMillis ? formatDuration(b.ttlMillis) : "—"}</td>
                    <td className="px-3 py-2">
                      <PeerList peers={b.peers} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        {nats.account && (
          <p className="text-xs text-muted-foreground">
            {t("cluster.nats.account", "Account usage: {{storage}} stored, {{memory}} in memory, {{streams}} streams, {{consumers}} consumers.", {
              storage: formatBytes(nats.account.storageBytes),
              memory: formatBytes(nats.account.memoryBytes),
              streams: nats.account.streams,
              consumers: nats.account.consumers,
            })}
          </p>
        )}
      </div>
    </section>
  );
}
