import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router-dom";
import { BookOpen, LineChart, Network, RefreshCw, ShieldOff } from "lucide-react";
import { useOnboarding } from "@/hooks/use-onboarding";
import { useAuth } from "@/hooks/use-auth";
import { useClusterActivity, useClusterOverview } from "@/hooks/use-cluster";
import { clusterAccess } from "@/lib/cluster-access";
import { formatDuration } from "@/lib/cluster-labels";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { StreamBadge } from "@/components/ui/stream-badge";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { RefetchErrorNotice } from "@/components/shared/refetch-error-notice";
import { ClusterVerdictCard } from "@/components/cluster/cluster-health";
import { ClusterNodeGrid } from "@/components/cluster/cluster-nodes";
import { ClusterNatsPanel } from "@/components/cluster/cluster-nats";
import { ClusterAuditTrail, ClusterRecoveryActions } from "@/components/cluster/cluster-recovery";
import { ClusterLeasesPanel } from "@/components/cluster/cluster-leases";
import { ClusterDeadLettersPanel, DeadLetterCountsCard } from "@/components/cluster/cluster-dead-letters";
import { ClusterActivityFeed } from "@/components/cluster/cluster-activity";
import { ClusterDiagnosePanel } from "@/components/cluster/cluster-diagnose";

type Tab = "overview" | "leases" | "deadLetters" | "activity" | "diagnose";

const CLUSTERING_DOCS = "https://docs.labs.ai/clustering";
const METRICS_DOCS = "https://docs.labs.ai/metrics#cluster-metrics";

/**
 * The cluster console, for an on-call admin: is the cluster healthy, which
 * node is doing what, what went wrong, and what can safely be done about it.
 * On a single node it says so and explains what cluster mode adds.
 */
export function CoordinatorPage() {
  const { t } = useTranslation();
  const { roles, method } = useAuth();
  const access = clusterAccess(roles, method);
  const [params, setParams] = useSearchParams();
  const tab = (params.get("tab") as Tab | null) ?? "overview";
  const [dlConversation, setDlConversation] = useState("");

  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => {
    const timer = setTimeout(() => maybeAutoStart("coordinator"), 500);
    return () => clearTimeout(timer);
  }, [maybeAutoStart]);

  const overview = useClusterOverview(access.canView);
  const activity = useClusterActivity(access.canView);
  const data = overview.data;
  const clustered = data?.mode === "cluster";

  const setTab = useCallback(
    (next: Tab) => {
      setParams(
        (p) => {
          const n = new URLSearchParams(p);
          if (next === "overview") n.delete("tab");
          else n.set("tab", next);
          return n;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  if (!access.canView) {
    return (
      <div className="space-y-6">
        <PageHeader />
        <EmptyState
          icon={ShieldOff}
          title={t("cluster.access.denied", "This screen needs the eddi-admin or eddi-viewer role")}
          description={t("cluster.access.deniedText", "Ask an administrator for access. Nothing on this page is shown without one of those roles.")}
        />
      </div>
    );
  }

  const tabs: { id: Tab; label: string; badge?: number }[] = [
    { id: "overview", label: t("cluster.tab.overview", "Overview") },
    ...(clustered ? [{ id: "leases" as Tab, label: t("cluster.tab.leases", "Leases") }] : []),
    {
      id: "deadLetters",
      label: t("cluster.tab.deadLetters", "Dead letters"),
      badge: data ? data.deadLetters.shared + data.deadLetters.local : undefined,
    },
    { id: "activity", label: t("cluster.tab.activity", "Activity") },
    { id: "diagnose", label: t("cluster.tab.diagnose", "Stuck conversation") },
  ];
  const activeTab = tabs.some((x) => x.id === tab) ? tab : "overview";
  const nodeIds = (data?.nodes ?? []).map((n) => n.nodeId);

  return (
    <div className="space-y-6">
      <PageHeader
        right={
          <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <StreamBadge connected={activity.live} />
            {data && (
              <span data-testid="cluster-answered-by">
                {t("cluster.answeredBy", "Answered by {{node}}", { node: data.answeredBy })}
              </span>
            )}
            {!access.canAct && (
              <Badge variant="secondary" data-testid="cluster-read-only">
                {t("cluster.access.readOnly", "Read-only")}
              </Badge>
            )}
            <Button size="sm" variant="ghost" onClick={() => overview.refetch()} aria-label={t("common.refresh", "Refresh")} data-testid="cluster-refresh">
              <RefreshCw aria-hidden="true" />
            </Button>
          </div>
        }
      />

      {overview.isError && data && (
        <RefetchErrorNotice
          onRetry={() => overview.refetch()}
          message={t("cluster.staleNotice", "The latest refresh failed — this is the view from {{age}} ago.", {
            age: formatDuration(Date.now() - data.generatedAt),
          })}
        />
      )}

      <Tabs tabs={tabs} active={activeTab} onSelect={setTab} />

      <div role="tabpanel" id={`cluster-panel-${activeTab}`} aria-labelledby={`cluster-tab-${activeTab}`} tabIndex={0} className="focus:outline-none">
        {overview.isLoading ? (
          <div className="space-y-4" data-testid="cluster-loading">
            <Skeleton className="h-32 w-full rounded-xl" />
            <div className="cq-card-grid">
              {Array.from({ length: 3 }).map((_, i) => (
                <Skeleton key={i} className="h-40 rounded-xl" />
              ))}
            </div>
          </div>
        ) : overview.isError && !data ? (
          <ErrorState message={t("common.error", "Something went wrong")} onRetry={() => overview.refetch()} retryLabel={t("common.retry", "Retry")} />
        ) : !data ? null : activeTab === "overview" ? (
          <div className="space-y-6">
            <ClusterVerdictCard overview={data} />
            {!clustered && <SingleNodeExplainer />}
            <ClusterNodeGrid nodes={data.nodes} canAct={access.canAct && clustered} leaseTtlMs={Number(data.settings["leaseTtlMs"] ?? 20000)} />
            <div className="grid gap-6 lg:grid-cols-2">
              <DeadLetterCountsCard onOpen={access.canAct ? () => setTab("deadLetters") : undefined} />
              <ClusterActivityFeed activity={activity} compact />
            </div>
            {data.nats && <ClusterNatsPanel nats={data.nats} />}
            {access.canAct && (
              <div className="grid gap-6 lg:grid-cols-2">
                <ClusterRecoveryActions localDeadLetters={data.deadLetters.local} clustered={clustered} />
                <ClusterAuditTrail />
              </div>
            )}
            <MetricsLinks />
          </div>
        ) : activeTab === "leases" ? (
          <ClusterLeasesPanel canAct={access.canAct} />
        ) : activeTab === "deadLetters" ? (
          access.canAct ? (
            <ClusterDeadLettersPanel key={dlConversation} nodes={nodeIds} initialConversationId={dlConversation} />
          ) : (
            <div className="space-y-3">
              <DeadLetterCountsCard />
              <p className="text-sm text-muted-foreground" data-testid="cluster-dl-admin-only">
                {t("cluster.access.deadLettersAdmin", "The entries themselves carry what users typed, so only eddi-admin can open, replay or discard them.")}
              </p>
            </div>
          )
        ) : activeTab === "activity" ? (
          <ClusterActivityFeed activity={activity} />
        ) : (
          <ClusterDiagnosePanel
            initialId={params.get("conversation") ?? ""}
            canAct={access.canAct}
            onIdChange={(id) =>
              setParams(
                (p) => {
                  const n = new URLSearchParams(p);
                  if (id) n.set("conversation", id);
                  else n.delete("conversation");
                  return n;
                },
                { replace: true },
              )
            }
            onShowDeadLetters={(id) => {
              setDlConversation(id);
              setTab("deadLetters");
            }}
          />
        )}
      </div>
    </div>
  );
}

function PageHeader({ right }: { right?: React.ReactNode }) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
      <div>
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <Network className="h-8 w-8 text-primary" aria-hidden="true" />
          {t("cluster.title", "Cluster")}
        </h1>
        <p className="mt-1 text-muted-foreground">
          {t("cluster.subtitle", "Health, nodes, leases and failed turns across every EDDI replica — and what you can safely do about them")}
        </p>
      </div>
      {right}
    </div>
  );
}

/** Tabs with roving focus: arrow keys move, Home/End jump. */
function Tabs({ tabs, active, onSelect }: { tabs: { id: Tab; label: string; badge?: number }[]; active: Tab; onSelect: (tab: Tab) => void }) {
  const { t } = useTranslation();
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});
  const onKey = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const rtl = document.documentElement.dir === "rtl";
    let next = -1;
    if (e.key === (rtl ? "ArrowLeft" : "ArrowRight")) next = (index + 1) % tabs.length;
    else if (e.key === (rtl ? "ArrowRight" : "ArrowLeft")) next = (index - 1 + tabs.length) % tabs.length;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = tabs.length - 1;
    if (next < 0) return;
    e.preventDefault();
    const target = tabs[next]!;
    onSelect(target.id);
    refs.current[target.id]?.focus();
  };
  return (
    <div role="tablist" aria-label={t("cluster.tabs", "Cluster console sections")} className="flex flex-wrap gap-1 border-b border-border" data-testid="cluster-tabs">
      {tabs.map((tab, i) => (
        <button
          key={tab.id}
          ref={(el) => {
            refs.current[tab.id] = el;
          }}
          role="tab"
          id={`cluster-tab-${tab.id}`}
          aria-selected={active === tab.id}
          aria-controls={`cluster-panel-${tab.id}`}
          tabIndex={active === tab.id ? 0 : -1}
          onClick={() => onSelect(tab.id)}
          onKeyDown={(e) => onKey(e, i)}
          className={`-mb-px inline-flex items-center gap-2 border-b-2 px-4 py-2 text-sm font-medium transition-colors ${
            active === tab.id ? "border-primary text-foreground" : "border-transparent text-muted-foreground hover:text-foreground"
          }`}
          data-testid={`cluster-tab-${tab.id}`}
        >
          {tab.label}
          {tab.badge !== undefined && tab.badge > 0 && <Badge variant="warning">{tab.badge}</Badge>}
        </button>
      ))}
    </div>
  );
}

function SingleNodeExplainer() {
  const { t } = useTranslation();
  return (
    <section className="rounded-xl border border-primary/20 bg-primary/5 p-4 text-sm" data-testid="cluster-single-node">
      <h2 className="font-semibold text-foreground">{t("cluster.single.title", "What cluster mode adds")}</h2>
      <ul className="mt-2 list-disc space-y-1 ps-5 text-foreground/90">
        <li>{t("cluster.single.scale", "Any number of EDDI replicas behind a plain round-robin load balancer, sharing one database and a NATS JetStream cluster.")}</li>
        <li>{t("cluster.single.leases", "Conversation leases with fencing tokens: turns of one conversation never overlap anywhere, and a node that lost its lease cannot overwrite newer turns.")}</li>
        <li>{t("cluster.single.failover", "Failover: a crashed node's conversations continue on another node within the lease TTL; dead letters, caches, deployments and cancel work cluster-wide.")}</li>
      </ul>
      <a href={CLUSTERING_DOCS} target="_blank" rel="noreferrer" className="mt-3 inline-flex items-center gap-1 text-primary hover:underline" data-testid="cluster-docs-link">
        <BookOpen className="h-4 w-4" aria-hidden="true" />
        {t("cluster.single.docs", "Read the clustering guide")}
      </a>
    </section>
  );
}

function MetricsLinks() {
  const { t } = useTranslation();
  return (
    <p className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted-foreground">
      <LineChart className="h-4 w-4" aria-hidden="true" />
      <span>{t("cluster.metrics.text", "Trends over time are on the EDDI Cluster Grafana dashboard (eddi_cluster_* metrics).")}</span>
      <a href={METRICS_DOCS} target="_blank" rel="noreferrer" className="text-primary hover:underline">
        {t("cluster.metrics.reference", "Cluster metrics reference")}
      </a>
      <a href="/q/metrics" target="_blank" rel="noreferrer" className="text-primary hover:underline">
        {t("cluster.metrics.raw", "Raw metrics of this node")}
      </a>
      <a href={CLUSTERING_DOCS} target="_blank" rel="noreferrer" className="text-primary hover:underline">
        {t("cluster.metrics.runbook", "Clustering runbook")}
      </a>
    </p>
  );
}
