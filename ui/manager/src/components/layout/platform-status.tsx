import { useState, useRef, useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { usePlatformStatus } from "@/hooks/use-platform-status";
import { cn } from "@/lib/utils";

/**
 * Always-visible platform status pill for the top bar.
 * Shows EDDI backend connectivity with a click-to-expand popover
 * for instance details (mobile-friendly — no hover-only info).
 */
export function PlatformStatus() {
  const { t } = useTranslation();
  const { status, instanceId, latencyMs, lastCheckedAt } = usePlatformStatus();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  // Close popover on outside click or Escape
  useEffect(() => {
    if (!open) return;
    const handleClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", handleClick);
    document.addEventListener("keydown", handleKey);
    return () => {
      document.removeEventListener("mousedown", handleClick);
      document.removeEventListener("keydown", handleKey);
    };
  }, [open]);

  // Live-ticking "last checked" text (updates every second while popover is open)
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!open) return;
    const id = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, [open]);

  const formatAgo = useCallback(() => {
    if (!lastCheckedAt) return "";
    const sec = Math.round((Date.now() - lastCheckedAt.getTime()) / 1000);
    if (sec < 5) return t("platform.justNow", "just now");
    return t("platform.secondsAgo", "{{count}}s ago", { count: sec });
  }, [lastCheckedAt, t]);

  const isOnline = status === "online";
  const isChecking = status === "checking";

  return (
    <div ref={ref} className="relative" data-testid="platform-status">
      {/* Pill button */}
      <button
        onClick={() => setOpen((p) => !p)}
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-label={
          isChecking
            ? t("platform.checking", "Checking connection…")
            : isOnline
              ? t("platform.online", "Online")
              : t("platform.offline", "Offline")
        }
        className={cn(
          "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium transition-all duration-500",
          isChecking && "border-border bg-muted/50 text-muted-foreground",
          isOnline && "border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
          !isOnline && !isChecking && "border-red-500/30 bg-red-500/10 text-red-600 dark:text-red-400",
        )}
      >
        {/* Animated dot */}
        <span
          className={cn(
            "inline-block h-2 w-2 rounded-full transition-colors duration-500",
            isChecking && "bg-muted-foreground/40 animate-pulse",
            isOnline && "bg-emerald-500 animate-pulse",
            !isOnline && !isChecking && "bg-red-500 animate-pulse",
          )}
          aria-hidden="true"
        />
        {/* Label */}
        <span>
          {isChecking
            ? "…"
            : isOnline
              ? t("platform.online", "Online")
              : t("platform.offline", "Offline")}
        </span>
        {/* Latency — desktop only, inline when online */}
        {isOnline && latencyMs !== null && (
          <span className="hidden text-[10px] opacity-60 sm:inline">
            {latencyMs}ms
          </span>
        )}
      </button>

      {/* Popover */}
      {open && (
        <div
          className="absolute inset-s-1/2 top-full z-50 mt-2 w-56 rounded-xl border border-border bg-card p-3 shadow-xl shadow-black/10 transform-[translateX(-50%)] rtl:transform-[translateX(50%)]"
          role="dialog"
          aria-label={t("platform.status", "Platform Status")}
          data-testid="platform-status-popover"
        >
          {/* Title */}
          <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("platform.status", "Platform Status")}
          </p>

          {/* Connection state */}
          <div className="flex items-center gap-2 mb-3">
            <span
              className={cn(
                "h-2.5 w-2.5 rounded-full",
                isOnline ? "bg-emerald-500 animate-pulse" : isChecking ? "bg-muted-foreground/40 animate-pulse" : "bg-red-500 animate-pulse",
              )}
            />
            <span className={cn(
              "text-sm font-medium",
              isOnline ? "text-emerald-600 dark:text-emerald-400" : isChecking ? "text-muted-foreground" : "text-red-600 dark:text-red-400",
            )}>
              {isChecking
                ? t("platform.checking", "Checking connection…")
                : isOnline
                  ? t("platform.connected", "Connected")
                  : t("platform.disconnected", "Disconnected")}
            </span>
          </div>

          {/* Detail rows */}
          <div className="space-y-1.5 text-xs">
            {instanceId && (
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">{t("platform.instance", "Instance")}</span>
                <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-[11px] text-foreground">
                  {instanceId.length > 12 ? `${instanceId.substring(0, 12)}…` : instanceId}
                </code>
              </div>
            )}
            {latencyMs !== null && (
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">{t("platform.latency", "Latency")}</span>
                <span className={cn(
                  "font-medium tabular-nums",
                  latencyMs < 100 ? "text-emerald-600 dark:text-emerald-400" : latencyMs < 500 ? "text-amber-600 dark:text-amber-400" : "text-red-600 dark:text-red-400",
                )}>
                  {latencyMs}ms
                </span>
              </div>
            )}
            {lastCheckedAt && (
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">{t("platform.lastChecked", "Last checked")}</span>
                <span className="text-foreground/70">{formatAgo()}</span>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
