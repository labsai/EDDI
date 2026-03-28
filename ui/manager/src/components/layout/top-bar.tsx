import { useState, useRef, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useLocation, Link } from "react-router-dom";
import {
  Moon,
  Sun,
  Monitor,
  Globe,
  Menu,
  ChevronRight,
  LogOut,
} from "lucide-react";
import { useTheme } from "./theme-provider";
import { cn } from "@/lib/utils";
import { useAuth } from "@/hooks/use-auth";

interface TopBarProps {
  onMenuClick: () => void;
  sidebarVisible: boolean;
}

/** Build breadcrumb segments from the current URL path */
function useBreadcrumbs() {
  const location = useLocation();
  const { t } = useTranslation();

  const pathSegments = location.pathname
    .replace(/^\/manage\/?/, "")
    .split("/")
    .filter(Boolean);

  const crumbs: { label: string; to: string }[] = [
    { label: t("nav.dashboard"), to: "/manage" },
  ];

  const labelMap: Record<string, string> = {
    agents: t("nav.agents"),
    workflows: t("nav.packages"),
    conversations: t("nav.conversations"),
    chat: t("nav.chat"),
    resources: t("nav.resources"),
    groups: t("nav.groups", "Groups"),
    coordinator: t("nav.coordinator", "Coordinator"),
    schedules: t("nav.schedules", "Schedules"),
    logs: t("nav.logs", "Logs"),
    orphans: t("nav.orphans", "Orphans"),
    secrets: t("nav.secrets", "Secrets"),
    audit: t("nav.audit", "Audit Trail"),
    quotas: t("nav.quotas", "Quotas"),
    wizard: t("wizard.title", "Agent Wizard"),
    workflowview: t("nav.packages"),
  };

  let currentPath = "/manage";
  for (const segment of pathSegments) {
    currentPath += `/${segment}`;
    // Known routes get labels, IDs/params show as shortened
    const label =
      labelMap[segment] ??
      (segment.match(/^[a-f0-9]{24}$/)
        ? `${segment.substring(0, 8)}…`
        : segment.replace(/view$/, ""));
    crumbs.push({ label, to: currentPath });
  }

  return crumbs;
}

export function TopBar({ onMenuClick, sidebarVisible }: TopBarProps) {
  const { theme, setTheme } = useTheme();
  const { t, i18n } = useTranslation();
  const breadcrumbs = useBreadcrumbs();
  const { method, user, logout } = useAuth();
  const showUser = method === "keycloak" && user;

  // User dropdown state
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const userMenuRef = useRef<HTMLDivElement>(null);

  // Close dropdown on outside click or Escape key
  useEffect(() => {
    if (!userMenuOpen) return;
    const handleClick = (e: MouseEvent) => {
      if (
        userMenuRef.current &&
        !userMenuRef.current.contains(e.target as Node)
      ) {
        setUserMenuOpen(false);
      }
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setUserMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClick);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handleClick);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [userMenuOpen]);

  const themeOptions = [
    { value: "light" as const, icon: Sun, label: t("theme.light") },
    { value: "dark" as const, icon: Moon, label: t("theme.dark") },
    { value: "system" as const, icon: Monitor, label: t("theme.system") },
  ];

  const languages = [
    { code: "en", label: t("language.en") },
    { code: "de", label: t("language.de") },
    { code: "fr", label: t("language.fr") },
    { code: "es", label: t("language.es") },
    { code: "ar", label: t("language.ar") },
    { code: "zh", label: t("language.zh") },
    { code: "th", label: t("language.th") },
    { code: "ja", label: t("language.ja") },
    { code: "ko", label: t("language.ko") },
    { code: "pt", label: t("language.pt") },
    { code: "hi", label: t("language.hi") },
  ];

  /** User initials for avatar */
  const initials = showUser
    ? [user.firstName, user.lastName]
        .filter(Boolean)
        .map((n) => n[0])
        .join("")
        .toUpperCase() || user.username[0]?.toUpperCase() || "?"
    : "";

  return (
    <header className="flex h-16 items-center justify-between border-b border-border bg-card px-4">
      {/* Left: Mobile menu + Breadcrumbs */}
      <div className="flex items-center gap-3">
        {/* Mobile menu button */}
        <button
          onClick={onMenuClick}
          data-testid="mobile-menu-toggle"
          aria-label={t("nav.openMenu", "Open navigation menu")}
          className={cn(
            "rounded-lg p-2 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground md:hidden",
            sidebarVisible && "hidden"
          )}
        >
          <Menu className="h-5 w-5" aria-hidden="true" />
        </button>

        {/* Breadcrumbs */}
        <nav
          aria-label="Breadcrumb"
          className="hidden items-center gap-1 text-sm md:flex"
        >
          {breadcrumbs.map((crumb, idx) => (
            <span key={crumb.to} className="flex items-center gap-1">
              {idx > 0 && (
                <ChevronRight className="h-3.5 w-3.5 text-muted-foreground/60" />
              )}
              {idx === breadcrumbs.length - 1 ? (
                <span className="font-medium text-foreground" aria-current="page">
                  {crumb.label}
                </span>
              ) : (
                <Link
                  to={crumb.to}
                  className="text-muted-foreground transition-colors hover:text-foreground"
                >
                  {crumb.label}
                </Link>
              )}
            </span>
          ))}
        </nav>
      </div>

      {/* Right: Controls */}
      <div className="flex items-center gap-2">
        {/* Language selector */}
        <div className="relative flex items-center gap-1">
          <Globe className="h-4 w-4 text-muted-foreground" />
          <select
            value={i18n.language}
            onChange={(e) => i18n.changeLanguage(e.target.value)}
            data-testid="language-selector"
            className="appearance-none rounded-md bg-transparent px-2 py-1.5 text-sm text-foreground outline-none transition-colors hover:bg-secondary focus:ring-2 focus:ring-ring"
          >
            {languages.map((lang) => (
              <option key={lang.code} value={lang.code}>
                {lang.label}
              </option>
            ))}
          </select>
        </div>

        {/* Theme toggle */}
        <div className="flex items-center rounded-lg bg-secondary p-0.5">
          {themeOptions.map((option) => (
            <button
              key={option.value}
              onClick={() => setTheme(option.value)}
              data-testid={`theme-${option.value}`}
              title={option.label}
              aria-label={option.label}
              aria-pressed={theme === option.value}
              className={cn(
                "rounded-md p-1.5 transition-colors",
                theme === option.value
                  ? "bg-background text-foreground shadow-sm"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              <option.icon className="h-4 w-4" aria-hidden="true" />
            </button>
          ))}
        </div>

        {/* User dropdown (only when auth enabled) */}
        {showUser && (
          <div className="relative" ref={userMenuRef}>
            <button
              onClick={() => setUserMenuOpen((prev) => !prev)}
              data-testid="user-menu-trigger"
              className="flex h-8 w-8 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground transition-opacity hover:opacity-80"
              title={user.fullName || user.username}
              aria-label={t("auth.userMenu", "User menu")}
              aria-haspopup="true"
              aria-expanded={userMenuOpen}
            >
              {initials}
            </button>

            {userMenuOpen && (
              <div
                className="absolute inset-e-0 top-full z-50 mt-2 w-56 rounded-lg border border-border bg-card p-1 shadow-lg"
                data-testid="user-menu-dropdown"
                role="menu"
                aria-label={t("auth.userMenu", "User menu")}
              >
                {/* User info */}
                <div className="border-b border-border px-3 py-2.5">
                  <p className="text-sm font-medium text-foreground">
                    {user.fullName || user.username}
                  </p>
                  {user.email && (
                    <p className="text-xs text-muted-foreground">
                      {user.email}
                    </p>
                  )}
                </div>

                {/* Menu items */}
                <div className="py-1">
                  <button
                    onClick={() => {
                      setUserMenuOpen(false);
                      logout();
                    }}
                    data-testid="user-menu-logout"
                    role="menuitem"
                    className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm text-foreground transition-colors hover:bg-secondary"
                  >
                    <LogOut className="h-4 w-4" />
                    {t("auth.logout", "Logout")}
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </header>
  );
}
