import { useTranslation } from "react-i18next";
import { Users, Activity, Layers, AlertTriangle, CheckCircle2, AlertCircle } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  useKnowledgeHealth,
  type HealthStatus,
} from "@/hooks/use-knowledge-health";
import { Skeleton } from "@/components/ui/skeleton";

// ─── Health status config ────────────────────────────────────────

const STATUS_CONFIG: Record<
  HealthStatus,
  {
    badgeClass: string;
    barColor: string;
    bannerClass: string;
    icon: typeof CheckCircle2;
  }
> = {
  healthy: {
    badgeClass: "bg-emerald-500/10 text-emerald-500 border-emerald-500/20",
    barColor: "bg-emerald-500",
    bannerClass: "bg-emerald-500/10 border-emerald-500/20 text-emerald-600 dark:text-emerald-400",
    icon: CheckCircle2,
  },
  moderate: {
    badgeClass: "bg-amber-500/10 text-amber-500 border-amber-500/20",
    barColor: "bg-amber-500",
    bannerClass: "bg-amber-500/10 border-amber-500/20 text-amber-600 dark:text-amber-400",
    icon: AlertTriangle,
  },
  "at-risk": {
    badgeClass: "bg-rose-500/10 text-rose-500 border-rose-500/20",
    barColor: "bg-rose-500",
    bannerClass: "bg-rose-500/10 border-rose-500/20 text-rose-600 dark:text-rose-400",
    icon: AlertCircle,
  },
};

// ─── Component ───────────────────────────────────────────────────

export interface KnowledgeHealthCardProps {
  className?: string;
}

export function KnowledgeHealthCard({ className }: KnowledgeHealthCardProps) {
  const { t } = useTranslation();
  const health = useKnowledgeHealth();

  if (health.isLoading) {
    return (
      <div
        className={cn(
          "rounded-xl border border-border bg-card p-5",
          className,
        )}
      >
        <Skeleton className="h-5 w-40 mb-4" />
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="space-y-2">
              <Skeleton className="h-8 w-12" />
              <Skeleton className="h-3 w-20" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  const status = STATUS_CONFIG[health.healthStatus];
  const StatusAlertIcon = status.icon;

  const metrics = [
    {
      value: health.workforceSize,
      label: t("knowledgeHealth.workforceSize", "Workforce Size"),
      icon: Users,
      description: t(
        "knowledgeHealth.workforceSizeDesc",
        "Total deployed digital experts",
      ),
      hasProgress: false,
    },
    {
      value: `${health.activeRate}%`,
      label: t("knowledgeHealth.activeRate", "Active Rate"),
      icon: Activity,
      description: t(
        "knowledgeHealth.activeRateDesc",
        "Agents active in the last 30 days",
      ),
      hasProgress: true,
    },
    {
      value: health.taskForceCount,
      label: t("knowledgeHealth.taskForces", "Task Forces"),
      icon: Layers,
      description: t(
        "knowledgeHealth.taskForcesDesc",
        "Collaborative agent groups",
      ),
      hasProgress: false,
    },
    {
      value: health.dormantCount,
      label: t("knowledgeHealth.dormantAgents", "Dormant"),
      icon: AlertTriangle,
      description: t(
        "knowledgeHealth.dormantAgentsDesc",
        "Agents not used in 30+ days",
      ),
      highlight: health.dormantCount > 0,
      hasProgress: false,
    },
  ];

  const statusLabel =
    health.healthStatus === "healthy"
      ? t("knowledgeHealth.statusHealthy", "Healthy")
      : health.healthStatus === "moderate"
        ? t("knowledgeHealth.statusModerate", "Moderate")
        : t("knowledgeHealth.statusAtRisk", "At Risk");

  const statusMessage =
    health.healthStatus === "healthy"
      ? t(
          "knowledgeHealth.msgHealthy",
          "{{rate}}% of your digital workforce was active in the last 30 days",
          { rate: health.activeRate },
        )
      : health.healthStatus === "moderate"
        ? t(
            "knowledgeHealth.msgModerate",
            "Some digital experts haven't been consulted recently",
          )
        : t(
            "knowledgeHealth.msgAtRisk",
            "Most of your workforce is dormant — consider engaging them",
          );

  return (
    <div
      className={cn(
        "rounded-xl border border-border bg-card p-5 br-card-premium br-section-enter",
        className,
      )}
      style={{ '--enter-delay': '60ms' } as React.CSSProperties}
    >
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-foreground flex items-center gap-2">
          <Activity className="h-4 w-4 text-primary" />
          {t("knowledgeHealth.title", "Knowledge Health")}
        </h3>
        <span
          className={cn(
            "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium",
            status.badgeClass,
          )}
        >
          <span className="h-1.5 w-1.5 rounded-full bg-current" />
          {statusLabel}
        </span>
      </div>

      {/* Metric cards */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-4">
        {metrics.map((metric) => {
          const Icon = metric.icon;
          return (
            <div
              key={metric.label}
              className="rounded-lg border border-border/60 bg-muted/20 p-3.5 flex flex-col justify-between transition-colors hover:bg-muted/40"
              title={metric.description}
            >
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs font-medium text-muted-foreground">
                  {metric.label}
                </span>
                <Icon
                  className={cn(
                    "h-4 w-4",
                    "highlight" in metric && metric.highlight
                      ? "text-rose-500"
                      : "text-muted-foreground/70",
                  )}
                />
              </div>
              <div>
                <p
                  className={cn(
                    "text-2xl font-bold tracking-tight",
                    "highlight" in metric && metric.highlight
                      ? "text-rose-500"
                      : "text-foreground",
                  )}
                >
                  {metric.value}
                </p>
                {metric.hasProgress && (
                  <div className="mt-2 h-1.5 w-full rounded-full bg-muted overflow-hidden">
                    <div
                      className={cn(
                        "h-full rounded-full transition-all duration-500",
                        status.barColor,
                      )}
                      style={{ width: `${Math.max(health.activeRate, 5)}%` }}
                      role="progressbar"
                      aria-valuenow={health.activeRate}
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-label={t("knowledgeHealth.activeRate", "Active Rate")}
                    />
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Status banner */}
      <div
        className={cn(
          "flex items-center gap-2.5 rounded-lg border px-3.5 py-2.5 text-xs font-medium transition-colors",
          status.bannerClass,
        )}
      >
        <StatusAlertIcon className="h-4 w-4 shrink-0" />
        <span>{statusMessage}</span>
      </div>
    </div>
  );
}
