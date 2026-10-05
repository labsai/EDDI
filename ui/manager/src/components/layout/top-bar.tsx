import { useTranslation } from "react-i18next";
import { useLocation, Link } from "react-router-dom";
import {
  Moon,
  Sun,
  Monitor,
  Menu,
  ChevronRight,
  Link2,
  LogOut,
  UserRound,
  Languages,
} from "lucide-react";
import { useTheme } from "./theme-provider";
import { cn } from "@/lib/utils";
import { useAuth } from "@/hooks/use-auth";
import { useLanguageSwitcher } from "@/hooks/use-language-switcher";
import { useRouteEntityName } from "@/hooks/use-route-entity-name";
import { buildCrumbs } from "@/lib/route-registry";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { userDisplayName, userInitials, userSecondaryEmail } from "@/lib/user-display";
import { PlatformStatus } from "./platform-status";
import { OperatorDrawer } from "@/components/operator/operator-drawer";
import { NotificationBell } from "@/components/workspaces/notification-bell";

interface TopBarProps {
  onMenuClick: () => void;
  sidebarVisible: boolean;
}

/** Build breadcrumb segments from the current URL path */
function useBreadcrumbs() {
  const location = useLocation();
  const { t } = useTranslation();
  const entityName = useRouteEntityName();
  return buildCrumbs(t, location.pathname, entityName);
}

export function TopBar({ onMenuClick, sidebarVisible }: TopBarProps) {
  const { theme, setTheme } = useTheme();
  const { t } = useTranslation();
  const breadcrumbs = useBreadcrumbs();
  const { language, languages, changeLanguage } = useLanguageSwitcher();
  const { method, user, logout } = useAuth();
  const showUser = method === "keycloak" && user;

  const themeOptions = [
    { value: "light" as const, icon: Sun, label: t("theme.light") },
    { value: "dark" as const, icon: Moon, label: t("theme.dark") },
    { value: "system" as const, icon: Monitor, label: t("theme.system") },
  ];

  /** Avatar initials and label — both empty when the token carries no profile claims */
  const initials = showUser ? userInitials(user) : "";
  const displayName = showUser ? userDisplayName(user) : "";
  const secondaryEmail = showUser ? userSecondaryEmail(user) : "";

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
          aria-label={t("nav.breadcrumb", "Breadcrumb")}
          className="flex min-w-0 items-center gap-1 text-sm"
        >
          {/* Keyed by position, not by `to`: listRouteForSegment maps a *view
              segment onto the list route, so two crumbs can now resolve to the
              same path (e.g. /manage/agents/agentview) and collide as keys. */}
          {breadcrumbs.map((crumb, idx) => (
            // Phones have no room for the whole trail: only the current page
            // (the last crumb) stays, so context is never lost entirely.
            <span
              key={`${idx}-${crumb.to}`}
              className={cn(
                "items-center gap-1",
                idx === breadcrumbs.length - 1 ? "flex min-w-0" : "hidden md:flex",
              )}
            >
              {idx > 0 && (
                <ChevronRight className="hidden h-3.5 w-3.5 text-muted-foreground/60 md:block" aria-hidden="true" />
              )}
              {idx === breadcrumbs.length - 1 ? (
                <span className="truncate font-medium text-foreground" aria-current="page">
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

      {/* Center: Platform Status. On phones only the dot stays (the full pill
          pushed the bar into horizontal overflow); its label still names the
          state for assistive tech. */}
      <div className="hidden md:block">
        <PlatformStatus />
      </div>
      <div className="md:hidden">
        <PlatformStatus compact />
      </div>

      {/* Right: Operator launcher, then the personalization controls. The
          operator sits outside the tour target — the tour step is about theme
          and language, not about the operator. */}
      <div className="flex min-w-0 items-center gap-2">
        <NotificationBell />
        <OperatorDrawer />

        <div className="flex items-center gap-2" data-tour="topbar-personalize">
          {/* Language selector — hidden on phones, where the same control sits
              in the navigation drawer; keeping it here inflated the bar past the
              viewport at 375px. */}
          <div className="relative hidden items-center gap-1 sm:flex">
            <Languages className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
            <select
              value={language}
              onChange={(e) => void changeLanguage(e.target.value)}
              data-testid="language-selector"
              aria-label={t("language.label", "Language")}
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

        {/* User dropdown (only when auth enabled). Radix gives it the menu
            keyboard model it claims: arrow keys, Home/End, typeahead, Escape,
            and focus returning to the trigger. */}
        {showUser && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                data-testid="user-menu-trigger"
                className="flex h-8 w-8 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground transition-opacity hover:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-card"
                title={displayName || t("auth.signedIn", "Signed in")}
                aria-label={t("auth.userMenu", "User menu")}
              >
                {initials ? (
                  <span data-testid="user-menu-initials">{initials}</span>
                ) : (
                  <UserRound
                    className="h-4 w-4"
                    aria-hidden="true"
                    data-testid="user-menu-avatar-icon"
                  />
                )}
              </button>
            </DropdownMenuTrigger>

            <DropdownMenuContent
              align="end"
              className="w-56 bg-card"
              data-testid="user-menu-dropdown"
              aria-label={t("auth.userMenu", "User menu")}
            >
              {/* User info */}
              <div className="border-b border-border px-3 py-2.5">
                {/* truncate hides the end of a long name or address, so the
                    full text stays available on hover. */}
                <p
                  className="truncate text-sm font-medium text-foreground"
                  title={displayName || undefined}
                >
                  {displayName || t("auth.signedIn", "Signed in")}
                </p>
                {secondaryEmail && (
                  <p className="truncate text-xs text-muted-foreground" title={secondaryEmail}>
                    {secondaryEmail}
                  </p>
                )}
              </div>

              {/* The per-user half of connections lives here because there is
                  nowhere else it could: the Manager has no profile area, and the
                  sidebar's Connections entry is the admin config list, which
                  most people cannot open. */}
              <div className="py-1">
                <DropdownMenuItem asChild className="px-3 py-2 text-foreground">
                  <Link to="/manage/linked-accounts" data-testid="user-menu-linked-accounts">
                    <Link2 className="h-4 w-4" aria-hidden="true" />
                    {t("pages.linkedAccounts.title", "Linked accounts")}
                  </Link>
                </DropdownMenuItem>
                <DropdownMenuItem
                  onSelect={() => logout()}
                  data-testid="user-menu-logout"
                  className="px-3 py-2 text-foreground"
                >
                  <LogOut className="h-4 w-4" aria-hidden="true" />
                  {t("auth.logout", "Logout")}
                </DropdownMenuItem>
              </div>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
        </div>
      </div>
    </header>
  );
}
