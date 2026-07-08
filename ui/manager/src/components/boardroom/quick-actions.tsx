import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Users, MessageCircle, BarChart3, Bot } from "lucide-react";
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
      icon: Users,
      label: t("quickActions.assembleTaskForce", "Assemble Task Force"),
      description: t(
        "quickActions.assembleTaskForceDesc",
        "Bring experts together to solve complex challenges",
      ),
      to: "/boardroom/new",
    },
    {
      icon: MessageCircle,
      label: t("quickActions.chatWithAgent", "Chat with Agent"),
      description: t(
        "quickActions.chatWithAgentDesc",
        "Have a 1:1 conversation with any digital expert",
      ),
      to: "/manage/agents",
    },
    {
      icon: BarChart3,
      label: t("quickActions.viewInsights", "View Insights"),
      description: t(
        "quickActions.viewInsightsDesc",
        "Track your workforce's knowledge coverage",
      ),
      to: "/boardroom/history",
    },
    {
      icon: Bot,
      label: t("quickActions.manageWorkforce", "Manage Workforce"),
      description: t(
        "quickActions.manageWorkforceDesc",
        "Configure and deploy digital experts",
      ),
      to: "/manage/agents",
    },
  ];

  return (
    <div className={cn("grid grid-cols-2 sm:grid-cols-4 gap-3", className)}>
      {actions.map((action) => (
        <Link
          key={action.label}
          to={action.to}
          className={cn(
            "group flex flex-col items-start gap-2 rounded-xl border border-border p-4",
            "bg-card hover:bg-muted/50 transition-all duration-150",
            "hover:shadow-md hover:-translate-y-0.5",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          )}
        >
          <action.icon
            className="h-5 w-5 text-muted-foreground transition-transform group-hover:scale-110 group-hover:text-foreground"
          />
          <div>
            <p className="text-sm font-medium text-foreground">
              {action.label}
            </p>
            <p className="text-xs text-muted-foreground mt-0.5 line-clamp-2">
              {action.description}
            </p>
          </div>
        </Link>
      ))}
    </div>
  );
}
