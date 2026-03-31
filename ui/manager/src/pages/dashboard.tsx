import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import {
  LayoutDashboard,
  Bot,
  Workflow,
  MessageSquare,
  FileCode,
  Plus,
  Wand2,
  MessageCircle,
  ArrowRight,
  FileText,
  Link2Off,
  ShieldCheck,
  Activity,
  Server,
  Cloud,
  CheckCircle2,
  AlertTriangle,
} from "lucide-react";
import {
  useDashboardStats,
  useRecentAgents,
  useRecentConversations,
  useCoordinatorStatusLight,
} from "@/hooks/use-dashboard";
import { groupAgentsByName } from "@/hooks/use-agents";
import { usePlatformStatus } from "@/hooks/use-platform-status";
import { useVaultHealth } from "@/hooks/use-secrets";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { cn, formatRelativeTime } from "@/lib/utils";
import { useOnboarding } from "@/hooks/use-onboarding";
import { parseConversationUri } from "@/lib/api/conversations";

// ─── State badge color helper ────────────────────────────────────────────────

const STATE_COLORS: Record<string, string> = {
  READY: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  IN_PROGRESS: "bg-blue-500/10 text-blue-600 dark:text-blue-400",
  ERROR: "bg-red-500/10 text-red-600 dark:text-red-400",
  ENDED: "bg-gray-500/10 text-gray-500",
};

// ─── Dashboard Page ──────────────────────────────────────────────────────────

export function DashboardPage() {
  const { t } = useTranslation();
  const { data: stats, isLoading: statsLoading } = useDashboardStats();
  const { data: recentAgentsRaw, isLoading: agentsLoading } = useRecentAgents();
  const { data: recentConversations, isLoading: convsLoading } = useRecentConversations(5);
  const { data: coordinatorStatus } = useCoordinatorStatusLight();
  const platformStatus = usePlatformStatus();
  const { data: vaultHealth } = useVaultHealth();

  const recentAgents = recentAgentsRaw ? groupAgentsByName(recentAgentsRaw).slice(0, 4) : [];

  // Auto-trigger dashboard onboarding chapter on first visit
  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => {
    // Small delay so DOM elements are rendered for spotlight targeting
    const timer = setTimeout(() => maybeAutoStart("dashboard"), 500);
    return () => clearTimeout(timer);
  }, [maybeAutoStart]);

  const statCards = [
    {
      label: t("pages.dashboard.activeAgents"),
      value: stats?.agentCount ?? 0,
      icon: Bot,
      gradient: "from-amber-500/10 to-primary/5",
      iconColor: "text-primary bg-primary/10",
      to: "/manage/agents",
    },
    {
      label: t("pages.dashboard.totalWorkflows"),
      value: stats?.workflowCount ?? 0,
      icon: Workflow,
      gradient: "from-emerald-500/10 to-emerald-500/5",
      iconColor: "text-emerald-600 dark:text-emerald-400 bg-emerald-500/10",
      to: "/manage/workflows",
    },
    {
      label: t("pages.dashboard.totalConversations"),
      value: stats?.conversationCount ?? 0,
      icon: MessageSquare,
      gradient: "from-blue-500/10 to-blue-500/5",
      iconColor: "text-blue-600 dark:text-blue-400 bg-blue-500/10",
      to: "/manage/conversations",
    },
    {
      label: t("pages.dashboard.totalResources"),
      value: stats?.resourceCount ?? 0,
      icon: FileCode,
      gradient: "from-violet-500/10 to-violet-500/5",
      iconColor: "text-violet-600 dark:text-violet-400 bg-violet-500/10",
      to: "/manage/resources",
    },
  ];

  // Remove resourceCount card (always 0, no backend endpoint)
  const visibleStatCards = statCards.filter((s) => s.to !== "/manage/resources" || s.value > 0);

  return (
    <div className="space-y-8">
      {/* Page header */}
      <div>
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <LayoutDashboard className="h-8 w-8 text-primary" />
          {t("pages.dashboard.title")}
        </h1>
        <p className="mt-1 text-muted-foreground">
          {t("pages.dashboard.subtitle")}
        </p>
      </div>

      {/* ─── Platform Health Strip ─── */}
      <div className="flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card/50 px-4 py-2.5" data-testid="platform-health-strip">
        {/* Platform connectivity */}
        <div className="flex items-center gap-2 text-xs">
          <span className={`inline-flex h-2 w-2 rounded-full ${
            platformStatus.status === "online" ? "bg-emerald-500 animate-pulse"
            : platformStatus.status === "offline" ? "bg-red-500"
            : "bg-muted-foreground animate-pulse"
          }`} />
          <span className="font-medium text-foreground">
            {t("dashboard.platform", "Platform")}
          </span>
          <span className="text-muted-foreground">
            {platformStatus.status === "online"
              ? t("dashboard.online", "Online")
              : platformStatus.status === "offline"
                ? t("dashboard.offline", "Offline")
                : t("dashboard.checking", "Checking…")}
          </span>
        </div>

        <div className="h-4 w-px bg-border" />

        {/* Coordinator */}
        <div className="flex items-center gap-2 text-xs">
          {coordinatorStatus ? (
            coordinatorStatus.coordinatorType === "nats" ? (
              <Cloud className="h-3.5 w-3.5 text-blue-500" />
            ) : (
              <Server className="h-3.5 w-3.5 text-purple-500" />
            )
          ) : (
            <Activity className="h-3.5 w-3.5 text-muted-foreground" />
          )}
          <span className="text-muted-foreground">
            {coordinatorStatus
              ? coordinatorStatus.connected
                ? t("dashboard.coordConnected", "Coordinator connected")
                : t("dashboard.coordDisconnected", "Coordinator disconnected")
              : t("dashboard.coordUnknown", "Coordinator —")}
          </span>
        </div>

        <div className="h-4 w-px bg-border" />

        {/* Vault */}
        <div className="flex items-center gap-2 text-xs">
          {vaultHealth?.available ? (
            <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />
          ) : (
            <AlertTriangle className="h-3.5 w-3.5 text-amber-500" />
          )}
          <span className="text-muted-foreground">
            {vaultHealth?.available
              ? t("dashboard.vaultUp", "Vault ready")
              : t("dashboard.vaultDown", "Vault unavailable")}
          </span>
        </div>
      </div>

      {/* Stats cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" data-tour="dashboard-stats">
        {statsLoading
          ? Array.from({ length: 4 }).map((_, i) => (
              <Card key={i}>
                <CardContent className="flex items-center gap-4">
                  <Skeleton className="h-12 w-12 rounded-lg" />
                  <div className="space-y-2">
                    <Skeleton className="h-3 w-20" />
                    <Skeleton className="h-7 w-12" />
                  </div>
                </CardContent>
              </Card>
            ))
          : visibleStatCards.map((stat) => (
              <Link key={stat.label} to={stat.to}>
                <Card className="group relative overflow-hidden transition-all hover:shadow-lg hover:-translate-y-0.5 active:translate-y-0">
                  {/* Gradient background */}
                  <div className={cn("absolute inset-0 bg-linear-to-br opacity-0 transition-opacity group-hover:opacity-100", stat.gradient)} />
                  <CardContent className="relative flex items-center gap-4">
                    <div className={cn("rounded-lg p-3", stat.iconColor)}>
                      <stat.icon className="h-6 w-6" />
                    </div>
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                        {stat.label}
                      </p>
                      <p className="text-2xl font-bold text-foreground tabular-nums">
                        {stat.value > 0 ? stat.value.toLocaleString() : (
                          <span className="flex items-center gap-1 text-muted-foreground/50">
                            0 <Plus className="h-3 w-3" />
                          </span>
                        )}
                      </p>
                    </div>
                  </CardContent>
                </Card>
              </Link>
            ))}
      </div>

      {/* Quick Actions */}
      <div>
        <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {t("pages.dashboard.quickActions")}
        </h2>
        <div className="flex flex-wrap gap-2" data-tour="dashboard-actions">
          <Button variant="outline" asChild>
            <Link to="/manage/agents/wizard">
              <Wand2 className="h-4 w-4" />
              {t("wizard.title")}
            </Link>
          </Button>
          <Button variant="outline" asChild>
            <Link to="/manage/agents">
              <Plus className="h-4 w-4" />
              {t("agents.createAgent")}
            </Link>
          </Button>
          <Button variant="outline" asChild>
            <Link to="/manage/chat">
              <MessageCircle className="h-4 w-4" />
              {t("nav.chat")}
            </Link>
          </Button>
          <Button variant="outline" asChild>
            <Link to="/manage/logs">
              <FileText className="h-4 w-4" />
              {t("dashboard.viewLogs", "View Logs")}
            </Link>
          </Button>
          <Button variant="outline" asChild>
            <Link to="/manage/orphans">
              <Link2Off className="h-4 w-4" />
              {t("dashboard.orphanScan", "Orphan Scan")}
            </Link>
          </Button>
          <Button variant="outline" asChild>
            <Link to="/manage/audit">
              <ShieldCheck className="h-4 w-4" />
              {t("dashboard.auditTrail", "Audit Trail")}
            </Link>
          </Button>
        </div>
      </div>

      {/* Recent conversations */}
      <div>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("dashboard.recentConversations", "Recent Conversations")}
          </h2>
          <Link
            to="/manage/conversations"
            className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline"
          >
            {t("nav.conversations")}
            <ArrowRight className="h-3 w-3" />
          </Link>
        </div>

        {convsLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 3 }).map((_, i) => (
              <Card key={i}>
                <CardContent className="flex items-center gap-3 py-3">
                  <Skeleton className="h-8 w-8 rounded-lg" />
                  <div className="flex-1 space-y-1.5">
                    <Skeleton className="h-3 w-40" />
                    <Skeleton className="h-3 w-24" />
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
        ) : !recentConversations || recentConversations.length === 0 ? (
          <Card>
            <CardContent className="flex items-center gap-3 py-6 text-muted-foreground">
              <MessageSquare className="h-8 w-8 text-muted-foreground/30" />
              <p className="text-sm">{t("dashboard.noConversations", "No conversations yet")}</p>
            </CardContent>
          </Card>
        ) : (
          <div className="space-y-2" data-testid="recent-conversations">
            {recentConversations.map((conv) => {
              const convId = parseConversationUri(conv.resource);
              return (
                <Link key={convId} to={`/manage/conversations/${convId}`}>
                  <Card className="transition-all hover:shadow-sm hover:border-primary/20">
                    <CardContent className="flex items-center gap-3 py-3">
                      <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-500/10">
                        <MessageSquare className="h-4 w-4 text-blue-500" />
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium text-foreground">
                          {conv.name || conv.agentId || convId.slice(0, 12)}
                        </p>
                        <p className="text-xs text-muted-foreground">
                          {conv.agentId} • v{conv.agentVersion}
                        </p>
                      </div>
                      <span className={cn(
                        "rounded-full px-2 py-0.5 text-[10px] font-medium",
                        STATE_COLORS[conv.conversationState] ?? STATE_COLORS.ENDED,
                      )}>
                        {conv.conversationState}
                      </span>
                      <span className="text-xs text-muted-foreground tabular-nums">
                        {conv.lastModifiedOn ? formatRelativeTime(conv.lastModifiedOn) : "—"}
                      </span>
                    </CardContent>
                  </Card>
                </Link>
              );
            })}
          </div>
        )}
      </div>

      {/* Recent agents */}
      <div>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("pages.dashboard.recentAgents")}
          </h2>
          <Link
            to="/manage/agents"
            className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline"
          >
            {t("nav.agents")}
            <ArrowRight className="h-3 w-3" />
          </Link>
        </div>

        {agentsLoading ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {Array.from({ length: 4 }).map((_, i) => (
              <Card key={i}>
                <CardContent className="space-y-3">
                  <Skeleton className="h-5 w-3/4" />
                  <Skeleton className="h-4 w-1/2" />
                  <Skeleton className="h-4 w-full" />
                </CardContent>
              </Card>
            ))}
          </div>
        ) : recentAgents.length === 0 ? (
          <Card>
            <CardContent className="flex flex-col items-center py-8">
              <Bot className="h-10 w-10 text-muted-foreground/50" />
              <p className="mt-3 text-sm text-muted-foreground">
                {t("pages.dashboard.noRecentAgents")}
              </p>
              <Button variant="outline" size="sm" className="mt-3" asChild>
                <Link to="/manage/agents/wizard">
                  <Plus className="h-4 w-4" />
                  {t("agents.createAgent")}
                </Link>
              </Button>
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {recentAgents.map((agent) => (
              <Link key={agent.id} to={`/manage/agentview/${agent.id}`}>
                <Card className="group relative overflow-hidden transition-all hover:shadow-md hover:border-primary/30 hover:-translate-y-0.5 active:translate-y-0">
                  <div className="absolute inset-0 bg-linear-to-br from-primary/5 to-transparent opacity-0 transition-opacity group-hover:opacity-100" />
                  <CardHeader className="relative">
                    <CardTitle className="truncate text-base">{agent.name || t("agents.unnamed", "Unnamed Agent")}</CardTitle>
                  </CardHeader>
                  <CardContent className="relative pt-0">
                    <p className="text-xs text-muted-foreground truncate">
                      {agent.description || "—"}
                    </p>
                    <p className="mt-2 text-xs text-muted-foreground/70">
                      {agent.lastModifiedOn ? formatRelativeTime(agent.lastModifiedOn) : "—"}
                    </p>
                  </CardContent>
                </Card>
              </Link>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
