import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Home, MessageSquare, BarChart3 } from "lucide-react";
import { cn } from "@/lib/utils";
import { WORKFORCE_SUBPAGES } from "./workforce-subpages";

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

  /** `?version=N` from the current URL, or "" — see the Threads tab below. */
  const versionQuery = (() => {
    const version = new URLSearchParams(location.search).get("version");
    return version ? `?version=${encodeURIComponent(version)}` : "";
  })();

  /** True when the board root itself is the current page (its own destination). */
  const isBoardRoot = boardId !== null && location.pathname === `/workforce/${boardId}`;

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
      // Carry ?version= through. Every other board link in the app builds
      // `/workforce/:id?version=N`, and the group endpoints require the version —
      // dropping it silently pins the board to version 1.
      to: boardId
        ? `/workforce/${boardId}${versionQuery}`
        : "/workforce",
      // Disabled when there is no board AND when the board root is already the
      // current page. Otherwise the tab looks like a destination but navigating
      // resolves to the location already shown: nothing renders differently, no
      // tab lights up, and each tap pushes another history entry so Back needs
      // several presses. That is the same dead-control complaint that motivated
      // fixing the "Manage Workforce" tile, so it must not be reintroduced here.
      disabled: !boardId || isBoardRoot,
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
