import { useTranslation } from "react-i18next";
import { useLocation, Link } from "react-router-dom";
import { Moon, Sun, Monitor, Globe, Menu, ChevronRight } from "lucide-react";
import { useTheme } from "./theme-provider";
import { cn } from "@/lib/utils";

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
    bots: t("nav.bots"),
    packages: t("nav.packages"),
    conversations: t("nav.conversations"),
    chat: t("nav.chat"),
    resources: t("nav.resources"),
    wizard: t("wizard.title", "Bot Wizard"),
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

  return (
    <header className="flex h-16 items-center justify-between border-b border-border bg-card px-4">
      {/* Left: Mobile menu + Breadcrumbs */}
      <div className="flex items-center gap-3">
        {/* Mobile menu button */}
        <button
          onClick={onMenuClick}
          data-testid="mobile-menu-toggle"
          className={cn(
            "rounded-lg p-2 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground md:hidden",
            sidebarVisible && "hidden"
          )}
        >
          <Menu className="h-5 w-5" />
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
                <span className="font-medium text-foreground">
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
              className={cn(
                "rounded-md p-1.5 transition-colors",
                theme === option.value
                  ? "bg-background text-foreground shadow-sm"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              <option.icon className="h-4 w-4" />
            </button>
          ))}
        </div>
      </div>
    </header>
  );
}
