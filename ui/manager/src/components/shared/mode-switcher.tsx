import { useTranslation } from "react-i18next";
import { Link, useLocation } from "react-router-dom";
import { Settings2, Briefcase } from "lucide-react";
import { cn } from "@/lib/utils";

// ─── Constants ──────────────────────────────────────────────────

interface ModeOption {
  key: string;
  icon: typeof Settings2;
  path: string;
}

// Switching mode does NOT touch the saved landing preference. That preference is
// the visitor's explicit answer on the welcome chooser ("take me there next
// time"); a hop to Workforce for one task used to overwrite it silently, so the
// next visit opened somewhere they never chose.
const MODES: ModeOption[] = [
  { key: "manager", icon: Settings2, path: "/manage" },
  { key: "workforce", icon: Briefcase, path: "/workforce" },
];

// ─── Component ──────────────────────────────────────────────────

interface ModeSwitcherProps {
  collapsed?: boolean;
}

export function ModeSwitcher({ collapsed = false }: ModeSwitcherProps) {
  const { t } = useTranslation();
  const location = useLocation();

  // Helper for mode labels
  function getModeLabel(key: string): string {
    return key === "manager"
      ? t("nav.modeManager", "Manager")
      : t("nav.modeWorkforce", "Workforce");
  }

  // Determine active mode from URL
  const activeKey = location.pathname.startsWith("/workforce")
    ? "workforce"
    : "manager";

  // ── Collapsed: icon-only link to the other mode ──
  if (collapsed) {
    const inactiveMode = MODES.find((m) => m.key !== activeKey)!;
    const InactiveIcon = inactiveMode.icon;
    return (
      <Link
        to={inactiveMode.path}
        className={cn(
          "flex w-full items-center justify-center rounded-lg p-2 transition-all",
          "text-sidebar-foreground/70 hover:bg-sidebar-accent/10 hover:text-sidebar-foreground",
        )}
        aria-label={`${t("nav.switchMode", "Switch workspace")}: ${getModeLabel(inactiveMode.key)}`}
        title={getModeLabel(inactiveMode.key)}
        data-testid="mode-switcher-trigger"
      >
        <InactiveIcon className="h-4 w-4 shrink-0" aria-hidden="true" />
      </Link>
    );
  }

  // Plain links in a nav, not an ARIA tablist: a tablist promises that arrow keys
  // move between tabs and that doing so is cheap, but each of these swaps the whole
  // application. Tab/Enter on a link is the honest model.
  return (
    <nav
      className="flex items-center gap-0.5 rounded-lg bg-sidebar-accent/5 p-0.5"
      aria-label={t("nav.switchMode", "Switch workspace")}
      data-testid="mode-switcher-trigger"
    >
      {MODES.map((mode) => {
        const isActive = mode.key === activeKey;
        const Icon = mode.icon;
        return (
          <Link
            key={mode.key}
            to={mode.path}
            aria-current={isActive ? "page" : undefined}
            // Already here: do nothing rather than jump to the app's root.
            onClick={isActive ? (e) => e.preventDefault() : undefined}
            className={cn(
              "flex flex-1 items-center justify-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium transition-all duration-200",
              isActive
                ? "bg-sidebar-accent/15 text-sidebar-accent shadow-sm"
                : "text-sidebar-foreground/50 hover:text-sidebar-foreground/80",
            )}
            data-testid={`mode-option-${mode.key}`}
          >
            <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            <span>{getModeLabel(mode.key)}</span>
          </Link>
        );
      })}
    </nav>
  );
}
