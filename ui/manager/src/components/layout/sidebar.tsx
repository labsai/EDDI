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
} from "lucide-react";
import { cn } from "@/lib/utils";

const navItems = [
  { path: "/manage", icon: LayoutDashboard, labelKey: "nav.dashboard" },
  { path: "/manage/bots", icon: Bot, labelKey: "nav.bots" },
  { path: "/manage/packages", icon: Package, labelKey: "nav.packages" },
  {
    path: "/manage/conversations",
    icon: MessageSquare,
    labelKey: "nav.conversations",
  },
  { path: "/manage/chat", icon: MessageCircle, labelKey: "nav.chat" },
  { path: "/manage/resources", icon: FileCode, labelKey: "nav.resources" },
] as const;

interface SidebarProps {
  collapsed: boolean;
  onToggle: () => void;
}

export function Sidebar({ collapsed, onToggle }: SidebarProps) {
  const { t } = useTranslation();

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

      {/* Navigation */}
      <nav className="flex-1 space-y-1 p-2">
        {navItems.map((item) => (
          <NavLink
            key={item.path}
            to={item.path}
            end={item.path === "/manage"}
            className={({ isActive }) =>
              cn(
                "flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors",
                "hover:bg-sidebar-accent/10 hover:text-sidebar-accent",
                isActive
                  ? "bg-sidebar-accent text-sidebar-accent-foreground"
                  : "text-sidebar-foreground",
                collapsed && "justify-center px-2"
              )
            }
          >
            <item.icon className="h-5 w-5 shrink-0" />
            {!collapsed && <span>{t(item.labelKey)}</span>}
          </NavLink>
        ))}
      </nav>

      {/* Collapse toggle */}
      <div className="border-t border-sidebar-border p-2">
        <button
          onClick={onToggle}
          data-testid="sidebar-toggle"
          className="flex w-full items-center justify-center rounded-lg p-2.5 text-sidebar-foreground transition-colors hover:bg-sidebar-accent/10 hover:text-sidebar-accent"
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
