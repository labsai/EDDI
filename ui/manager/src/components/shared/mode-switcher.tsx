import { useState, useRef, useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useLocation } from "react-router-dom";
import { Settings2, Users, Check, ChevronDown } from "lucide-react";
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
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  // Helper for mode labels using literal t() calls per coding guidelines
  function getModeLabel(key: string): string {
    return key === "manager"
      ? t("nav.modeManager", "Manager")
      : t("nav.modeWorkforce", "Workforce");
  }

  // Determine active mode from URL
  const activeKey = location.pathname.startsWith("/workforce")
    ? "workforce"
    : "manager";
  const activeMode = MODES.find((m) => m.key === activeKey)!;

  // Close on outside click
  useEffect(() => {
    if (!open) return;
    const handleClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener("mousedown", handleClick);
    document.addEventListener("keydown", handleKey);
    return () => {
      document.removeEventListener("mousedown", handleClick);
      document.removeEventListener("keydown", handleKey);
    };
  }, [open]);

  // Focus first item on open
  useEffect(() => {
    if (open) {
      requestAnimationFrame(() => {
        const first = ref.current?.querySelector<HTMLElement>('[role="menuitem"]');
        first?.focus();
      });
    }
  }, [open]);

  // Keyboard navigation within the dropdown
  const handleMenuKeyDown = useCallback((e: React.KeyboardEvent<HTMLDivElement>) => {
    const items = ref.current?.querySelectorAll<HTMLElement>('[role="menuitem"]');
    if (!items || items.length === 0) return;
    const arr = Array.from(items);
    const idx = arr.indexOf(document.activeElement as HTMLElement);

    let next: number | null = null;
    switch (e.key) {
      case "ArrowDown":
        next = (idx + 1) % arr.length;
        break;
      case "ArrowUp":
        next = (idx - 1 + arr.length) % arr.length;
        break;
      case "Home":
        next = 0;
        break;
      case "End":
        next = arr.length - 1;
        break;
      default:
        return;
    }
    e.preventDefault();
    arr[next]?.focus();
  }, []);

  const handleSelect = useCallback(
    (mode: ModeOption) => {
      setOpen(false);
      if (mode.key !== activeKey) {
        // Update landing preference
        try {
          localStorage.setItem(LANDING_PREF_KEY, mode.prefValue);
        } catch { /* storage unavailable */ }
        navigate(mode.path);
      }
    },
    [activeKey, navigate],
  );

  const ActiveIcon = activeMode.icon;

  return (
    <div ref={ref} className="relative">
      {/* ── Trigger ── */}
      <button
        ref={triggerRef}
        onClick={() => setOpen((p) => !p)}
        className={cn(
          "flex w-full items-center rounded-lg transition-all",
          "text-sidebar-foreground/70 hover:bg-sidebar-accent/10 hover:text-sidebar-foreground",
          collapsed
            ? "justify-center p-2"
            : "gap-2.5 px-3 py-2",
        )}
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={t("nav.switchMode", "Switch workspace")}
        data-testid="mode-switcher-trigger"
      >
        <ActiveIcon className="h-4 w-4 shrink-0" aria-hidden="true" />
        {!collapsed && (
          <>
            <span className="flex-1 truncate text-start text-sm font-medium">
              {getModeLabel(activeKey)}
            </span>
            <ChevronDown
              className={cn(
                "h-3.5 w-3.5 shrink-0 transition-transform duration-200",
                open && "rotate-180",
              )}
              aria-hidden="true"
            />
          </>
        )}
      </button>

      {/* ── Dropdown ── */}
      {open && (
        <div
          className={cn(
            "absolute z-50 w-52 rounded-xl border border-sidebar-border bg-sidebar p-1 shadow-xl shadow-black/20",
            collapsed
              ? "start-14 top-0"
              : "start-2 top-full mt-1",
          )}
          role="menu"
          aria-label={t("nav.switchMode", "Switch workspace")}
          onKeyDown={handleMenuKeyDown}
          data-testid="mode-switcher-menu"
        >
          {MODES.map((mode) => {
            const isActive = mode.key === activeKey;
            const Icon = mode.icon;
            return (
              <button
                key={mode.key}
                onClick={() => handleSelect(mode)}
                className={cn(
                  "flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-start text-sm transition-colors",
                  isActive
                    ? "bg-sidebar-accent/10 text-sidebar-accent font-medium"
                    : "text-sidebar-foreground hover:bg-sidebar-accent/10 focus:bg-sidebar-accent/10",
                )}
                role="menuitem"
                tabIndex={-1}
                data-testid={`mode-option-${mode.key}`}
              >
                <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
                <span className="flex-1 truncate">
                  {getModeLabel(mode.key)}
                </span>
                {isActive && (
                  <Check className="h-3.5 w-3.5 shrink-0 text-sidebar-accent" aria-hidden="true" />
                )}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
