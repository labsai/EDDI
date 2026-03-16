import { NavLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  LayoutDashboard,
  Bot,
  Package,
  MessageSquare,
  MessageCircle,
  FileCode,
  PanelLeftClose,
  PanelLeft,
  LogOut,
  ExternalLink,
  BookOpen,
  FileJson,
  Activity,
  Trash2,
  ScrollText,
  KeyRound,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useAuth } from "@/hooks/use-auth";

const navSections = [
  {
    labelKey: "nav.sectionManagement",
    items: [
      { path: "/manage", icon: LayoutDashboard, labelKey: "nav.dashboard" },
      { path: "/manage/bots", icon: Bot, labelKey: "nav.bots" },
      { path: "/manage/packages", icon: Package, labelKey: "nav.packages" },
    ],
  },
  {
    labelKey: "nav.sectionDevelopment",
    items: [
      { path: "/manage/resources", icon: FileCode, labelKey: "nav.resources" },
      { path: "/manage/chat", icon: MessageCircle, labelKey: "nav.chat" },
    ],
  },
  {
    labelKey: "nav.sectionOperations",
    items: [
      {
        path: "/manage/conversations",
        icon: MessageSquare,
        labelKey: "nav.conversations",
      },
      {
        path: "/manage/coordinator",
        icon: Activity,
        labelKey: "nav.coordinator",
      },
      {
        path: "/manage/logs",
        icon: ScrollText,
        labelKey: "nav.logs",
      },
      {
        path: "/manage/orphans",
        icon: Trash2,
        labelKey: "nav.orphans",
      },
      {
        path: "/manage/secrets",
        icon: KeyRound,
        labelKey: "nav.secrets",
      },
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
      <nav className="flex-1 overflow-y-auto p-2">
        {navSections.map((section, idx) => (
          <div key={section.labelKey} className={cn(idx > 0 && "mt-4")}>
            {/* Section label (hidden when collapsed) */}
            {!collapsed && (
              <p className="mb-1 px-3 text-[11px] font-semibold uppercase tracking-wider text-sidebar-foreground/50">
                {t(section.labelKey)}
              </p>
            )}
            {collapsed && idx > 0 && (
              <div className="mx-3 mb-2 border-t border-sidebar-border" />
            )}
            <div className="space-y-0.5">
              {section.items.map((item) => (
                <NavLink
                  key={item.path}
                  to={item.path}
                  end={item.path === "/manage"}
                  className={({ isActive }) =>
                    cn(
                      "flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-all",
                      "hover:bg-sidebar-accent/10 hover:text-sidebar-accent",
                      isActive
                        ? "border-s-2 border-sidebar-accent bg-sidebar-accent/10 text-sidebar-accent"
                        : "border-s-2 border-transparent text-sidebar-foreground",
                      collapsed && "justify-center px-2"
                    )
                  }
                >
                  <item.icon className="h-5 w-5 shrink-0" />
                  {!collapsed && <span>{t(item.labelKey)}</span>}
                </NavLink>
              ))}
            </div>
          </div>
        ))}
      </nav>

      {/* External links */}
      <div className="border-t border-sidebar-border p-2">
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
                "flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-all",
                "border-s-2 border-transparent text-sidebar-foreground",
                "hover:bg-sidebar-accent/10 hover:text-sidebar-accent",
                collapsed && "justify-center px-2"
              )}
            >
              <link.icon className="h-5 w-5 shrink-0" />
              {!collapsed && (
                <span className="flex items-center gap-1.5">
                  {t(link.labelKey, link.fallback)}
                  <ExternalLink className="h-3 w-3 opacity-50" />
                </span>
              )}
            </a>
          ))}
        </div>
      </div>

      {/* User profile section (only when auth is enabled) */}
      {showUser && (
        <div className="border-t border-sidebar-border p-2">
          <div
            className={cn(
              "flex items-center gap-3 rounded-lg px-3 py-2.5",
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
                  className="ms-2 shrink-0 rounded-md p-1.5 text-sidebar-foreground/60 transition-colors hover:bg-sidebar-accent/10 hover:text-sidebar-accent"
                >
                  <LogOut className="h-4 w-4" />
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Collapse toggle */}
      <div className="border-t border-sidebar-border p-2">
        <button
          onClick={onToggle}
          data-testid="sidebar-toggle"
          aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          className="flex w-full items-center justify-center rounded-lg p-2.5 text-sidebar-foreground transition-all hover:bg-sidebar-accent/10 hover:text-sidebar-accent active:scale-[0.98]"
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
