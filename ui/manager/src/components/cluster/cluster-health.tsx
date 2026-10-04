import { useTranslation } from "react-i18next";
import { AlertOctagon, AlertTriangle, CheckCircle2, Server, Split, WifiOff } from "lucide-react";
import type { ClusterOverview } from "@/lib/api/cluster";
import { formatDuration, reasonLabel, verdictExplanation, verdictLabel, verdictNextStep } from "@/lib/cluster-labels";
import { cn } from "@/lib/utils";

/** Reason codes that need attention but do not make the cluster unhealthy. */
const ATTENTION_ONLY = new Set(["DEAD_LETTERS_WAITING", "NODE_DRAINING", "LOCAL_DEAD_LETTERS"]);

/**
 * "Is my cluster healthy?" — the verdict, what it means, and the next step.
 * The verdict is spelled out and carries an icon, never colour alone.
 */
export function ClusterVerdictCard({ overview }: { overview: ClusterOverview }) {
  const { t } = useTranslation();
  const { verdict, reasons } = overview;
  const next = verdictNextStep(t, verdict, reasons);
  const tone =
    verdict === "HEALTHY"
      ? "border-emerald-500/30 bg-emerald-500/5"
      : verdict === "SINGLE_NODE"
        ? "border-border bg-card"
        : verdict === "PARTITIONED"
          ? "border-destructive/40 bg-destructive/5"
          : "border-warning/40 bg-warning/5";
  const Icon =
    verdict === "HEALTHY" ? CheckCircle2 : verdict === "SINGLE_NODE" ? Server : verdict === "PARTITIONED" ? Split : AlertTriangle;
  const iconTone =
    verdict === "HEALTHY"
      ? "text-emerald-600 dark:text-emerald-400"
      : verdict === "SINGLE_NODE"
        ? "text-muted-foreground"
        : verdict === "PARTITIONED"
          ? "text-destructive"
          : "text-warning";
  const live = overview.nodes.filter((n) => n.state === "LIVE" || n.state === "STALE").length;

  return (
    <section
      className={cn("rounded-xl border p-5", tone)}
      aria-labelledby="cluster-verdict-heading"
      data-testid="coordinator-connection-card"
    >
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start">
        <Icon className={cn("h-10 w-10 shrink-0", iconTone)} aria-hidden="true" />
        <div className="min-w-0 flex-1 space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <h2 id="cluster-verdict-heading" className="text-xl font-bold text-foreground" data-testid="cluster-verdict" data-verdict={verdict}>
              {verdictLabel(t, verdict)}
            </h2>
            {overview.mode === "cluster" && (
              <span className="text-sm text-muted-foreground" data-testid="cluster-member-count">
                {t("cluster.health.members", "{{live}} of {{total}} nodes live", { live, total: overview.nodes.length })}
              </span>
            )}
          </div>
          <p className="text-sm text-foreground/90">{verdictExplanation(t, verdict, reasons)}</p>
          {next && (
            <p className="text-sm text-foreground" data-testid="cluster-next-step">
              <span className="font-semibold">{t("cluster.health.nextStep", "Next step:")}</span> {next}
            </p>
          )}
          {reasons.length > 0 && (
            <ul className="flex flex-wrap gap-2 pt-1" aria-label={t("cluster.health.reasons", "Why")} data-testid="cluster-reasons">
              {reasons.map((code) => (
                <li
                  key={code}
                  className={cn(
                    "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium",
                    ATTENTION_ONLY.has(code) ? "border-border bg-card text-foreground" : "border-warning/40 bg-warning/10 text-warning",
                  )}
                  data-testid={`cluster-reason-${code}`}
                >
                  {ATTENTION_ONLY.has(code) ? (
                    <AlertOctagon className="h-3 w-3" aria-hidden="true" />
                  ) : (
                    <AlertTriangle className="h-3 w-3" aria-hidden="true" />
                  )}
                  {reasonLabel(t, code)}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
      {(reasons.includes("NATS_UNREACHABLE") || reasons.includes("NATS_RECONNECTING")) && (
        <DegradedBanner policy={overview.degradedTurnsPolicy} since={overview.degradedSince} node={overview.answeredBy} />
      )}
    </section>
  );
}

/**
 * What degraded mode actually means for turns right now — the policy decides
 * whether two nodes may run one conversation unfenced, or clients get 409.
 */
function DegradedBanner({ policy, since, node }: { policy: string; since: number | null; node: string }) {
  const { t } = useTranslation();
  return (
    <div
      role="alert"
      className="mt-4 flex gap-3 rounded-lg border border-destructive/40 bg-destructive/5 p-4 text-sm"
      data-testid="cluster-degraded-banner"
      data-policy={policy}
    >
      <WifiOff className="mt-0.5 h-5 w-5 shrink-0 text-destructive" aria-hidden="true" />
      <div className="space-y-1">
        <p className="font-semibold text-foreground">
          {since
            ? t("cluster.degraded.title", "Node {{node}} has been without NATS for {{duration}}", {
                node,
                duration: formatDuration(Date.now() - since),
              })
            : t("cluster.degraded.titleNow", "Node {{node}} is without NATS", { node })}
        </p>
        {policy === "reject" ? (
          <p className="text-foreground/90" data-testid="cluster-degraded-reject">
            {t(
              "cluster.degraded.reject",
              "Policy reject: this node refuses every turn with 409 and Retry-After, so clients retry on a node that still has NATS. Nothing runs unfenced; a client that only reaches this node cannot talk at all.",
            )}
          </p>
        ) : (
          <p className="text-foreground/90" data-testid="cluster-degraded-local">
            {t(
              "cluster.degraded.local",
              "Policy local: this node keeps answering, ordering turns only among its own requests and without the fence. If another node handles the same conversation meanwhile, both may process it and miss each other's context — nothing is lost, appends merge.",
            )}
          </p>
        )}
        <p className="text-muted-foreground">
          {t(
            "cluster.degraded.what",
            "Also paused here: cache events (queued and replayed on reconnect), HITL recovery, cluster-wide cancel. Signed envelopes fail closed. Everything recovers by itself when NATS returns.",
          )}
        </p>
      </div>
    </div>
  );
}
