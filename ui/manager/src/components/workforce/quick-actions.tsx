import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { UsersRound, MessageSquareText, TrendingUp, Cog } from "lucide-react";
import { cn } from "@/lib/utils";

// ─── Types ───────────────────────────────────────────────────────

interface QuickAction {
  icon: React.ElementType;
  label: string;
  description: string;
  to: string;
}

// ─── Component ───────────────────────────────────────────────────

export interface QuickActionsProps {
  className?: string;
}

export function QuickActions({ className }: QuickActionsProps) {
  const { t } = useTranslation();

  const actions: QuickAction[] = [
    {
      icon: UsersRound,
      label: t("quickActions.assembleTaskForce", "Assemble Task Force"),
      description: t(
        "quickActions.assembleTaskForceDesc",
        "Bring experts together to solve complex challenges",
      ),
      to: "/workforce/new",
    },
    {
      icon: MessageSquareText,
      label: t("quickActions.chatWithAgent", "Chat with Agent"),
      description: t(
        "quickActions.chatWithAgentDesc",
        "Have a 1:1 conversation with any digital expert",
      ),
      to: "/workforce/chat",
    },
    {
      icon: TrendingUp,
      label: t("quickActions.viewInsights", "View Insights"),
      description: t(
        "quickActions.viewInsightsDesc",
        "Track your workforce's knowledge coverage",
      ),
      to: "/workforce/analytics",
    },
    {
      icon: Cog,
      label: t("quickActions.manageWorkforce", "Manage Workforce"),
      description: t(
        "quickActions.manageWorkforceDesc",
        "Configure and deploy digital experts",
      ),
      to: "/workforce",
    },
  ];

  return (
    <div className={cn("grid grid-cols-2 sm:grid-cols-4 gap-3", className)}>
      {actions.map((action, index) => (
        <Link
          key={action.label}
          to={action.to}
          className={cn(
            "group relative flex flex-col items-start gap-3 rounded-xl border border-border p-4",
            "bg-card transition-all duration-200",
            "hover:bg-muted/40 hover:shadow-lg hover:-translate-y-1",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
            "br-card-enter br-card-premium",
          )}
          style={
            { "--enter-delay": `${index * 60}ms` } as React.CSSProperties
          }
        >
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-muted/60">
            <action.icon className="h-[18px] w-[18px] text-muted-foreground transition-all duration-200 group-hover:text-foreground group-hover:scale-110" />
          </div>
          <div>
            <p className="text-sm font-medium text-foreground">
              {action.label}
            </p>
            <p className="text-xs text-muted-foreground/70 mt-0.5 line-clamp-2">
              {action.description}
            </p>
          </div>
        </Link>
      ))}
    </div>
  );
}
