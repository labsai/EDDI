import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

interface StreamBadgeProps {
  /** Whether the SSE stream is currently connected */
  connected: boolean;
  /** Optional extra classes */
  className?: string;
}

/**
 * Compact inline badge showing SSE stream connection status.
 * Use this anywhere a real-time EventSource stream is active.
 *
 * Connected:    [● Live]
 * Disconnected: [○ Reconnecting…]
 */
export function StreamBadge({ connected, className }: StreamBadgeProps) {
  const { t } = useTranslation();

  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-medium transition-colors duration-300",
        connected
          ? "text-emerald-600 dark:text-emerald-400"
          : "text-muted-foreground",
        className,
      )}
      data-testid="stream-badge"
    >
      <span
        className={cn(
          "inline-block h-1.5 w-1.5 rounded-full",
          connected
            ? "bg-emerald-500 animate-pulse"
            : "bg-muted-foreground/50 animate-pulse",
        )}
        aria-hidden="true"
      />
      <span role="status" aria-live="polite">
        {connected
          ? t("stream.live", "Live")
          : t("stream.reconnecting", "Reconnecting…")}
      </span>
    </span>
  );
}
