import { useCallback } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useLocation } from "react-router-dom";
import { Settings2, Users } from "lucide-react";
import { cn } from "@/lib/utils";

// ─── Constants ──────────────────────────────────────────────────

const LANDING_PREF_KEY = "eddi-landing-preference";

interface ModeOption {
  key: string;
  icon: typeof Settings2;
  path: string;
  prefValue: string;
}

const MODES: ModeOption[] = [
  {
    key: "manager",
    icon: Settings2,
    path: "/manage",
    prefValue: "manager",
  },
  {
    key: "workforce",
    icon: Users,
    path: "/workforce",
    prefValue: "workforce",
  },
];

// ─── Component ──────────────────────────────────────────────────

interface ModeSwitcherProps {
  collapsed?: boolean;
}

export function ModeSwitcher({ collapsed = false }: ModeSwitcherProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
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

  const handleSelect = useCallback(
    (mode: ModeOption) => {
      if (mode.key !== activeKey) {
        try {
          localStorage.setItem(LANDING_PREF_KEY, mode.prefValue);
        } catch { /* storage unavailable */ }
        navigate(mode.path);
      }
    },
    [activeKey, navigate],
  );

  // Keyboard handler for ARIA tabs (arrow keys, Home/End)
  const handleTabKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLButtonElement>) => {
      const activeIdx = MODES.findIndex((m) => m.key === activeKey);
      let targetIdx = -1;
      if (e.key === "ArrowRight" || e.key === "ArrowDown") {
        targetIdx = (activeIdx + 1) % MODES.length;
      } else if (e.key === "ArrowLeft" || e.key === "ArrowUp") {
        targetIdx = (activeIdx - 1 + MODES.length) % MODES.length;
      } else if (e.key === "Home") {
        targetIdx = 0;
      } else if (e.key === "End") {
        targetIdx = MODES.length - 1;
      }
      if (targetIdx >= 0 && targetIdx !== activeIdx) {
        const target = MODES[targetIdx];
        if (!target) return;
        e.preventDefault();
        handleSelect(target);
        // Focus the newly active tab after React re-render
        const container = e.currentTarget.closest("[role='tablist']");
        const tabs = container?.querySelectorAll<HTMLButtonElement>("[role='tab']");
        tabs?.[targetIdx]?.focus();
      }
    },
    [activeKey, handleSelect],
  );

  // ── Collapsed: icon-only toggle ──
  if (collapsed) {
    const inactiveMode = MODES.find((m) => m.key !== activeKey)!;
    const InactiveIcon = inactiveMode.icon;
    return (
      <button
        onClick={() => handleSelect(inactiveMode)}
        className={cn(
          "flex w-full items-center justify-center rounded-lg p-2 transition-all",
          "text-sidebar-foreground/70 hover:bg-sidebar-accent/10 hover:text-sidebar-foreground",
        )}
        aria-label={t("nav.switchMode", "Switch workspace")}
        title={getModeLabel(inactiveMode.key)}
        data-testid="mode-switcher-trigger"
      >
        <InactiveIcon className="h-4 w-4 shrink-0" aria-hidden="true" />
      </button>
    );
  }


  return (
    <div
      className="flex items-center gap-0.5 rounded-lg bg-sidebar-accent/5 p-0.5"
      role="tablist"
      aria-label={t("nav.switchMode", "Switch workspace")}
      data-testid="mode-switcher-trigger"
    >
      {MODES.map((mode) => {
        const isActive = mode.key === activeKey;
        const Icon = mode.icon;
        return (
          <button
            key={mode.key}
            role="tab"
            aria-selected={isActive}
            tabIndex={isActive ? 0 : -1}
            onClick={() => handleSelect(mode)}
            onKeyDown={handleTabKeyDown}
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
          </button>
        );
      })}
    </div>
  );
}
