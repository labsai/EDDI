import { useTranslation } from "react-i18next";
import { Moon, Sun, Monitor, Globe, Menu } from "lucide-react";
import { useTheme } from "./theme-provider";
import { cn } from "@/lib/utils";

interface TopBarProps {
  onMenuClick: () => void;
  sidebarVisible: boolean;
}

export function TopBar({ onMenuClick, sidebarVisible }: TopBarProps) {
  const { theme, setTheme } = useTheme();
  const { t, i18n } = useTranslation();

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

      {/* Spacer */}
      <div className="flex-1" />

      {/* Controls */}
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
