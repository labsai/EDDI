import { useTranslation } from "react-i18next";
import { Users, Activity, Layers, AlertTriangle } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  useKnowledgeHealth,
  type HealthStatus,
} from "@/hooks/use-knowledge-health";
import { Skeleton } from "@/components/ui/skeleton";

// ─── Health status config ────────────────────────────────────────

const STATUS_CONFIG: Record<
  HealthStatus,
  { color: string; barColor: string; icon: string }
> = {
  healthy: {
    color: "text-foreground",
    barColor: "bg-foreground/70",
    icon: "●",
  },
  moderate: {
    color: "text-muted-foreground",
    barColor: "bg-muted-foreground",
    icon: "●",
  },
  "at-risk": {
    color: "text-destructive",
    barColor: "bg-destructive/70",
    icon: "●",
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

  const metrics = [
    {
      value: health.workforceSize,
      label: t("knowledgeHealth.workforceSize", "Workforce Size"),
      icon: Users,
      description: t(
        "knowledgeHealth.workforceSizeDesc",
        "Total deployed digital experts",
      ),
    },
    {
      value: `${health.activeRate}%`,
      label: t("knowledgeHealth.activeRate", "Active Rate"),
      icon: Activity,
      description: t(
        "knowledgeHealth.activeRateDesc",
        "Agents active in the last 30 days",
      ),
    },
    {
      value: health.taskForceCount,
      label: t("knowledgeHealth.taskForces", "Task Forces"),
      icon: Layers,
      description: t(
        "knowledgeHealth.taskForcesDesc",
        "Collaborative agent groups",
      ),
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
    },
  ];

  return (
    <div
      className={cn("rounded-xl border border-border bg-card p-5", className)}
    >
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-foreground flex items-center gap-2">
          📊{" "}
          {t("knowledgeHealth.title", "Knowledge Health")}
        </h3>
        <span
          className={cn("text-xs font-medium flex items-center gap-1", status.color)}
        >
          {status.icon}{" "}
          {health.healthStatus === "healthy"
            ? t("knowledgeHealth.statusHealthy", "Healthy")
            : health.healthStatus === "moderate"
              ? t("knowledgeHealth.statusModerate", "Moderate")
              : t("knowledgeHealth.statusAtRisk", "At Risk")}
        </span>
      </div>

      {/* Status bar */}
      <div className="h-1.5 rounded-full bg-muted mb-5 overflow-hidden">
        <div
          className={cn("h-full rounded-full transition-all duration-500", status.barColor)}
          style={{ width: `${Math.max(health.activeRate, 5)}%` }}
          role="progressbar"
          aria-valuenow={health.activeRate}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={t("knowledgeHealth.activeRate", "Active Rate")}
        />
      </div>

      {/* Metric cards */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        {metrics.map((metric) => (
          <div
            key={metric.label}
            className="text-center"
            title={metric.description}
          >
            <div className="flex items-center justify-center mb-1">
              <metric.icon
                className={cn(
                  "h-4 w-4",
                  "highlight" in metric && metric.highlight
                    ? "text-muted-foreground"
                    : "text-muted-foreground",
                )}
              />
            </div>
            <p
              className={cn(
                "text-2xl font-bold",
                "highlight" in metric && metric.highlight
                  ? "text-muted-foreground"
                  : "text-foreground",
              )}
            >
              {metric.value}
            </p>
            <p className="text-xs text-muted-foreground mt-0.5">
              {metric.label}
            </p>
          </div>
        ))}
      </div>

      {/* Status message */}
      <p className={cn("text-xs mt-4 text-center", status.color)}>
        {health.healthStatus === "healthy"
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
              )}
      </p>
    </div>
  );
}
