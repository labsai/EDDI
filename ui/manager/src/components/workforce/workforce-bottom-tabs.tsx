import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Home, MessageSquare, BarChart3 } from "lucide-react";
import { cn } from "@/lib/utils";

/** Second path segments under /workforce that are app pages, not board ids. */
const WORKFORCE_SUBPAGES = new Set(["new", "analytics", "chat"]);

// ─── Component ───────────────────────────────────────────────────

export function WorkforceBottomTabs() {
  const location = useLocation();
  const navigate = useNavigate();
  const { t } = useTranslation();

  const isHome = location.pathname === "/workforce";
  const isThreads = location.pathname.includes("/thread/");

  /**
   * The board id in /workforce/:boardId, or null when the second segment is one
   * of the app's own sub-pages rather than a board.
   */
  const boardId = (() => {
    const segment = location.pathname.split("/")[2];
    if (!segment) return null;
    return WORKFORCE_SUBPAGES.has(segment) ? null : segment;
  })();

  const tabs = [
    {
      key: "home" as const,
      label: t("Workforce.tabHome", "Home"),
      icon: Home,
      active: isHome,
      to: "/workforce",
      disabled: false,
    },
    {
      // Threads live at /workforce/:boardId/thread/:memberId. There is no
      // memberId in scope here, so this tab targets the board — which lists the
      // members whose threads you can open. Previously it built
      // `/workforce/<something>/thread/` with no memberId, which matched no
      // route and bounced the user out to /welcome via the catch-all.
      key: "threads" as const,
      label: t("Workforce.tabThreads", "Threads"),
      icon: MessageSquare,
      active: isThreads,
      to: boardId ? `/workforce/${boardId}` : "/workforce",
      disabled: !boardId,
    },
    {
      key: "insights" as const,
      label: t("Workforce.tabInsights", "Insights"),
      icon: BarChart3,
      active: location.pathname === "/workforce/analytics",
      to: "/workforce/analytics",
      disabled: false,
    },
  ] as const;

  return (
    <nav
      aria-label={t("Workforce.bottomNav", "Bottom navigation")}
      className={cn(
        "fixed inset-x-0 bottom-0 z-40 flex h-16 border-t",
        "border-border bg-card/90 backdrop-blur-md",
        "pb-[env(safe-area-inset-bottom)]",
      )}
    >
      {tabs.map((tab) => {
        const Icon = tab.icon;
        return (
          <button
            key={tab.key}
            type="button"
            {...(tab.active ? { "aria-current": "page" as const } : {})}
            disabled={tab.disabled}
            onClick={() => navigate(tab.to)}
            className={cn(
              "flex flex-1 flex-col items-center justify-center gap-1 text-xs transition-colors",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset",
              "disabled:cursor-not-allowed disabled:opacity-40",
              tab.active
                ? "font-medium text-primary"
                : "text-muted-foreground",
            )}
          >
            <Icon className="h-5 w-5" />
            <span>{tab.label}</span>
            {tab.active && (
              <span className="h-1 w-1 rounded-full bg-primary" />
            )}
          </button>
        );
      })}
    </nav>
  );
}
