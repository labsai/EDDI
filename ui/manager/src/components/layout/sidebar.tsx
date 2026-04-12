import { NavLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  LayoutDashboard,
  Bot,
  Workflow,
  MessagesSquare,
  MessageCircle,
  FileCode,
  PanelLeftClose,
  PanelLeft,
  LogOut,
  ExternalLink,
  BookOpen,
  FileJson,
  Activity,
  CalendarClock,
  Link2Off,
  ScrollText,
  KeyRound,
  ShieldCheck,
  SlidersHorizontal,
  Boxes,
  HelpCircle,
  Check,
  RotateCcw,
  Layers,
  ShieldAlert,
  Zap,
  RefreshCw,
  ChevronRight,
  Users,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useAuth } from "@/hooks/use-auth";
import { useState, useRef, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { useOnboarding, ALL_CHAPTERS, type TourChapterId } from "@/hooks/use-onboarding";
import { TOUR_CHAPTERS } from "@/components/onboarding/tour-chapters";

const navSections = [
  {
    labelKey: "nav.sectionCore",
    items: [
      { path: "/manage", icon: LayoutDashboard, labelKey: "nav.dashboard" },
      { path: "/manage/agents", icon: Bot, labelKey: "nav.agents" },
      { path: "/manage/workflows", icon: Workflow, labelKey: "nav.packages" },
      { path: "/manage/groups", icon: Boxes, labelKey: "nav.groups" },
      { path: "/manage/capabilities", icon: Layers, labelKey: "nav.capabilities" },
    ],
  },
  {
    labelKey: "nav.sectionBuild",
    items: [
      { path: "/manage/resources", icon: FileCode, labelKey: "nav.resources" },
      { path: "/manage/chat", icon: MessageCircle, labelKey: "nav.chat" },
      { path: "/manage/triggers", icon: Zap, labelKey: "nav.triggers" },
    ],
  },
  {
    labelKey: "nav.sectionMonitor",
    items: [
      { path: "/manage/logs", icon: ScrollText, labelKey: "nav.logs" },
      { path: "/manage/conversations", icon: MessagesSquare, labelKey: "nav.conversations" },
      { path: "/manage/coordinator", icon: Activity, labelKey: "nav.coordinator" },
      { path: "/manage/audit", icon: ShieldCheck, labelKey: "nav.audit" },
    ],
  },
  {
    labelKey: "nav.sectionAdmin",
    items: [
      { path: "/manage/secrets", icon: KeyRound, labelKey: "nav.secrets" },
      { path: "/manage/quotas", icon: SlidersHorizontal, labelKey: "nav.quotas" },
      { path: "/manage/schedules", icon: CalendarClock, labelKey: "nav.schedules" },
      { path: "/manage/userdata", icon: Users, labelKey: "nav.userData" },
      { path: "/manage/orphans", icon: Link2Off, labelKey: "nav.orphans" },
      { path: "/manage/sync", icon: RefreshCw, labelKey: "nav.sync" },
      { path: "/manage/gdpr", icon: ShieldAlert, labelKey: "nav.gdpr" },
    ],
  },
] as const;

const externalLinks = [
  {
    href: "/q/swagger-ui",
    icon: FileJson,
    labelKey: "nav.openapi",
    fallback: "OpenAPI",
  },
  {
    href: "https://docs.labs.ai",
    icon: BookOpen,
    labelKey: "nav.docs",
    fallback: "Documentation",
  },
] as const;

interface SidebarProps {
  collapsed: boolean;
  onToggle: () => void;
}

export function Sidebar({ collapsed, onToggle }: SidebarProps) {
  const { t } = useTranslation();
  const { method, user, logout } = useAuth();
  const showUser = method === "keycloak" && user;

  /** User initials for avatar */
  const initials = showUser
    ? [user.firstName, user.lastName]
        .filter(Boolean)
        .map((n) => n[0])
        .join("")
        .toUpperCase() || user.username[0]?.toUpperCase() || "?"
    : "";

  // ── Collapsible section state (persisted in localStorage) ──
  const STORAGE_KEY = "eddi-sidebar-sections";
  const [collapsedSections, setCollapsedSections] = useState<Set<number>>(() => {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      return stored ? new Set(JSON.parse(stored) as number[]) : new Set();
    } catch {
      return new Set();
    }
  });

  const toggleSection = useCallback((idx: number) => {
    setCollapsedSections((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) next.delete(idx);
      else next.add(idx);
      try { localStorage.setItem(STORAGE_KEY, JSON.stringify([...next])); } catch { /* noop */ }
      return next;
    });
  }, []);

  return (
    <aside
      data-testid="sidebar"
      className={cn(
        "flex h-full flex-col border-e border-sidebar-border bg-sidebar transition-all duration-300",
        collapsed ? "w-16" : "w-64"
      )}
    >
      {/* Logo */}
      <div className="flex h-16 items-center justify-center border-b border-sidebar-border px-4">
        {collapsed ? (
          <svg
            width="28"
            height="28"
            viewBox="0 0 28 28"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
            className="shrink-0"
            aria-label="EDDI"
          >
            <rect width="28" height="28" rx="6" className="fill-sidebar-accent" />
            <text
              x="5"
              y="20"
              fontFamily="'Noto Sans', sans-serif"
              fontWeight="700"
              fontSize="16"
              className="fill-sidebar"
            >
              E.
            </text>
          </svg>
        ) : (
          <img
            src="/logo_eddi.png"
            alt="EDDI"
            className="h-7 w-auto"
          />
        )}
      </div>

      {/* Navigation with section groupings */}
      <nav className="flex-1 overflow-y-auto p-1.5" aria-label={t("nav.mainNavigation", "Main navigation")}>
        {navSections.map((section, idx) => (
          <div key={section.labelKey} className={cn(idx > 0 && "mt-2.5")}>
            {/* Section label — clickable toggle (hidden when sidebar is collapsed) */}
            {!collapsed && (
              <button
                type="button"
                onClick={() => toggleSection(idx)}
                className="mb-1 flex w-full items-center gap-1 px-3 text-[11px] font-semibold uppercase tracking-wider text-sidebar-foreground/50 hover:text-sidebar-foreground/80 transition-colors"
                aria-expanded={!collapsedSections.has(idx)}
              >
                <ChevronRight
                  className={cn(
                    "h-3 w-3 shrink-0 transition-transform duration-200",
                    !collapsedSections.has(idx) && "rotate-90"
                  )}
                />
                {t(section.labelKey)}
              </button>
            )}
            {collapsed && idx > 0 && (
              <div className="mx-3 mb-2 border-t border-sidebar-border" />
            )}
            {/* Section items — hidden when section is collapsed (only in expanded sidebar) */}
            {(!collapsed ? !collapsedSections.has(idx) : true) && (
              <div className="space-y-0.5">
                {section.items.map((item) => (
                  <NavLink
                    key={item.path}
                    to={item.path}
                    end={item.path === "/manage"}
                    className={({ isActive }) =>
                      cn(
                        "flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-all",
                        "hover:bg-sidebar-accent/10 hover:text-sidebar-accent",
                        isActive
                          ? "border-s-2 border-sidebar-accent bg-sidebar-accent/10 text-sidebar-accent"
                          : "border-s-2 border-transparent text-sidebar-foreground",
                        collapsed && "justify-center px-2"
                      )
                    }
                    aria-label={collapsed ? t(item.labelKey) : undefined}
                    title={collapsed ? t(item.labelKey) : undefined}
                  >
                    <item.icon className="h-5 w-5 shrink-0" aria-hidden="true" />
                    {!collapsed && <span>{t(item.labelKey)}</span>}
                  </NavLink>
                ))}
              </div>
            )}
          </div>
        ))}
      </nav>

      {/* External links */}
      <div className="border-t border-sidebar-border p-1.5">
        {!collapsed && (
          <p className="mb-1 px-3 text-[11px] font-semibold uppercase tracking-wider text-sidebar-foreground/50">
            {t("nav.sectionExternal", "External")}
          </p>
        )}
        <div className="space-y-0.5">
          {externalLinks.map((link) => (
            <a
              key={link.href}
              href={link.href}
              target="_blank"
              rel="noopener noreferrer"
              className={cn(
                "flex items-center gap-3 rounded-lg px-3 py-1.5 text-sm font-medium transition-all",
                "border-s-2 border-transparent text-sidebar-foreground",
                "hover:bg-sidebar-accent/10 hover:text-sidebar-accent",
                collapsed && "justify-center px-2"
              )}
              aria-label={collapsed ? `${t(link.labelKey, link.fallback)} (${t("common.opensNewTab", "opens in new tab")})` : undefined}
              title={collapsed ? t(link.labelKey, link.fallback) : undefined}
            >
              <link.icon className="h-5 w-5 shrink-0" aria-hidden="true" />
              {!collapsed && (
                <span className="flex items-center gap-1.5">
                  {t(link.labelKey, link.fallback)}
                  <ExternalLink className="h-3 w-3 opacity-50" aria-hidden="true" />
                  <span className="sr-only">({t("common.opensNewTab", "opens in new tab")})</span>
                </span>
              )}
            </a>
          ))}
        </div>
      </div>

      {/* User profile section (only when auth is enabled) */}
      {showUser && (
        <div className="border-t border-sidebar-border p-1.5">
          <div
            className={cn(
              "flex items-center gap-3 rounded-lg px-3 py-2",
              collapsed && "justify-center px-2"
            )}
            data-testid="sidebar-user"
          >
            {/* Avatar */}
            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-sidebar-accent text-xs font-bold text-sidebar">
              {initials}
            </div>
            {!collapsed && (
              <div className="flex min-w-0 flex-1 items-center justify-between">
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium text-sidebar-foreground">
                    {user.fullName || user.username}
                  </p>
                  {user.email && (
                    <p className="truncate text-xs text-sidebar-foreground/60">
                      {user.email}
                    </p>
                  )}
                </div>
                <button
                  onClick={logout}
                  data-testid="sidebar-logout"
                  title={t("auth.logout", "Logout")}
                  aria-label={t("auth.logout", "Logout")}
                  className="ms-2 shrink-0 rounded-md p-1.5 text-sidebar-foreground/60 transition-colors hover:bg-sidebar-accent/10 hover:text-sidebar-accent"
                >
                  <LogOut className="h-4 w-4" aria-hidden="true" />
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Help & Tour menu */}
      <HelpMenu collapsed={collapsed} />

      {/* Version + Collapse toggle */}
      <div className="border-t border-sidebar-border p-1.5">
        {!collapsed && (
          <p className="mb-1 px-3 text-center text-[10px] text-sidebar-foreground/30">
            EDDI Manager {__APP_VERSION__}
          </p>
        )}
        <button
          onClick={onToggle}
          data-testid="sidebar-toggle"
          aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          className="flex w-full items-center justify-center rounded-lg p-2 text-sidebar-foreground transition-all hover:bg-sidebar-accent/10 hover:text-sidebar-accent active:scale-[0.98]"
        >
          {collapsed ? (
            <PanelLeft className="h-5 w-5" />
          ) : (
            <PanelLeftClose className="h-5 w-5" />
          )}
        </button>
      </div>
    </aside>
  );
}

/* ─── Help & Tour dropdown menu ──────────────────── */

const CHAPTER_ROUTES: Record<TourChapterId, string> = {
  dashboard: "/manage",
  agents: "/manage/agents",
  workflows: "/manage/workflows",
  chat: "/manage/chat",
  resources: "/manage/resources",
  conversations: "/manage/conversations",
  groups: "/manage/groups",
  logs: "/manage/logs",
  secrets: "/manage/secrets",
  audit: "/manage/audit",
  schedules: "/manage/schedules",
  quotas: "/manage/quotas",
  coordinator: "/manage/coordinator",
  orphans: "/manage/orphans",
};

function HelpMenu({ collapsed }: { collapsed: boolean }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  const completedChapters = useOnboarding((s) => s.completedChapters);
  const restartChapter = useOnboarding((s) => s.restartChapter);
  const resetAll = useOnboarding((s) => s.resetAll);

  // Close on outside click
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [open]);

  const handleChapterClick = (id: TourChapterId) => {
    setOpen(false);
    navigate(CHAPTER_ROUTES[id]);
    // Small delay so page renders targets before tour starts
    setTimeout(() => restartChapter(id), 300);
  };

  return (
    <div ref={ref} className="relative border-t border-sidebar-border p-1.5">
      <button
        onClick={() => setOpen((p) => !p)}
        className={cn(
          "flex w-full items-center rounded-lg px-3 py-2 text-sidebar-foreground transition-all hover:bg-sidebar-accent/10 hover:text-sidebar-accent",
          collapsed && "justify-center px-2"
        )}
        title={t("onboarding.help.title", "Help & Tour")}
        aria-label={t("onboarding.help.title", "Help & Tour")}
        data-testid="sidebar-help"
      >
        <HelpCircle className="h-5 w-5 shrink-0" />
        {!collapsed && (
          <span className="ms-3 text-sm font-medium">
            {t("onboarding.help.title", "Help & Tour")}
          </span>
        )}
      </button>

      {/* Dropdown */}
      {open && (
        <div
          className={cn(
            "absolute z-50 mb-2 w-56 rounded-xl border border-sidebar-border bg-sidebar p-1.5 shadow-xl shadow-black/20",
            collapsed ? "inset-s-14 bottom-0" : "inset-s-2 bottom-full"
          )}
          data-testid="help-menu-dropdown"
        >
          <p className="px-3 py-1.5 text-[10px] font-bold uppercase tracking-widest text-sidebar-foreground/40">
            {t("onboarding.help.platformTour", "Platform Tour")}
          </p>
          {ALL_CHAPTERS.map((id) => {
            const chapter = TOUR_CHAPTERS[id];
            const done = completedChapters.has(id);
            return (
              <button
                key={id}
                onClick={() => handleChapterClick(id)}
                className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-start text-sm text-sidebar-foreground transition-colors hover:bg-sidebar-accent/10"
                data-testid={`help-chapter-${id}`}
              >
                <span className="flex-1 truncate">{t(chapter.titleKey)}</span>
                {done ? (
                  <Check className="h-3.5 w-3.5 text-emerald-500 shrink-0" />
                ) : (
                  <span className="h-3.5 w-3.5 shrink-0 rounded-full border border-sidebar-foreground/30" />
                )}
              </button>
            );
          })}
          <div className="mt-1 border-t border-sidebar-border pt-1">
            <button
              onClick={() => {
                setOpen(false);
                resetAll();
              }}
              className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-xs text-sidebar-foreground/50 transition-colors hover:text-sidebar-foreground hover:bg-sidebar-accent/10"
              data-testid="help-reset-all"
            >
              <RotateCcw className="h-3.5 w-3.5" />
              {t("onboarding.help.resetAll", "Reset All Tours")}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
