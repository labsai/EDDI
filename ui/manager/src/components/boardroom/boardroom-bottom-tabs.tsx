import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Home, MessageSquare } from "lucide-react";
import { cn } from "@/lib/utils";

// ─── Component ───────────────────────────────────────────────────

export function BoardroomBottomTabs() {
  const location = useLocation();
  const navigate = useNavigate();
  const { t } = useTranslation();

  const isHome = location.pathname === "/boardroom";
  const isThreads = location.pathname.includes("/thread/");

  const tabs = [
    {
      key: "home" as const,
      label: t("boardroom.tabHome", "Home"),
      icon: Home,
      active: isHome,
      to: "/boardroom",
    },
    {
      key: "threads" as const,
      label: t("boardroom.tabThreads", "Threads"),
      icon: MessageSquare,
      active: isThreads,
      to: location.pathname.match(/\/boardroom\/[^/]+/)?.[0]
        ? `${location.pathname.match(/\/boardroom\/[^/]+/)![0]}/thread/`
        : "/boardroom",
    },
  ] as const;

  return (
    <nav
      aria-label={t("boardroom.bottomNav", "Bottom navigation")}
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
            onClick={() => navigate(tab.to)}
            className={cn(
              "flex flex-1 flex-col items-center justify-center gap-1 text-xs transition-colors",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset",
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
