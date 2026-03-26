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
} from "lucide-react";
import { useDashboardStats, useRecentAgents } from "@/hooks/use-dashboard";
import { groupAgentsByName } from "@/hooks/use-agents";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { cn, formatRelativeTime } from "@/lib/utils";

export function DashboardPage() {
  const { t } = useTranslation();
  const { data: stats, isLoading: statsLoading } = useDashboardStats();
  const { data: recentAgentsRaw, isLoading: agentsLoading } = useRecentAgents();

  const recentAgents = recentAgentsRaw ? groupAgentsByName(recentAgentsRaw).slice(0, 4) : [];

  const statCards = [
    {
      label: t("pages.dashboard.activeAgents"),
      value: stats?.agentCount ?? 0,
      icon: Bot,
      color: "text-primary bg-primary/10",
      to: "/manage/agents",
    },
    {
      label: t("pages.dashboard.totalWorkflows"),
      value: stats?.workflowCount ?? 0,
      icon: Workflow,
      color: "text-emerald-600 dark:text-emerald-400 bg-emerald-500/10",
      to: "/manage/workflows",
    },
    {
      label: t("pages.dashboard.totalConversations"),
      value: stats?.conversationCount ?? 0,
      icon: MessageSquare,
      color: "text-blue-600 dark:text-blue-400 bg-blue-500/10",
      to: "/manage/conversations",
    },
    {
      label: t("pages.dashboard.totalResources"),
      value: stats?.resourceCount ?? 0,
      icon: FileCode,
      color: "text-violet-600 dark:text-violet-400 bg-violet-500/10",
      to: "/manage/resources",
    },
  ];

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

      {/* Stats cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
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
          : statCards.map((stat) => (
              <Link key={stat.label} to={stat.to}>
                <Card className="transition-shadow hover:shadow-md">
                  <CardContent className="flex items-center gap-4">
                    <div className={cn("rounded-lg p-3", stat.color)}>
                      <stat.icon className="h-6 w-6" />
                    </div>
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                        {stat.label}
                      </p>
                      <p className="text-2xl font-bold text-foreground">
                        {stat.value.toLocaleString()}
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
        <div className="flex flex-wrap gap-2">
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
        </div>
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
                <Card className="transition-all hover:shadow-md hover:border-primary/30">
                  <CardHeader>
                    <CardTitle className="truncate text-base">{agent.name || "Unnamed Agent"}</CardTitle>
                  </CardHeader>
                  <CardContent className="pt-0">
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
