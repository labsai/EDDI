import { AlertTriangle, RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

interface RefetchErrorNoticeProps {
  onRetry: () => void;
  className?: string;
  /** Overrides the default "could not refresh" wording. */
  message?: string;
}

/**
 * Inline, non-destructive failure notice for a list or panel that already has
 * data on screen.
 *
 * `ErrorState` replaces its container, which is right when there is nothing to
 * show but wrong on a failed *background* refetch: these pages poll (schedules
 * every 10s, quotas every 10s) and refetch on window focus, so one blip would
 * otherwise blank out rows the operator was reading — and on a flaky link the
 * panel flickers in and out. Keep the last good data and say it is stale.
 */
export function RefetchErrorNotice({
  onRetry,
  className,
  message,
}: RefetchErrorNoticeProps) {
  const { t } = useTranslation();
  return (
    <div
      role="status"
      data-testid="refetch-error-notice"
      className={cn(
        "flex items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/5 px-3 py-2 text-xs",
        className,
      )}
    >
      <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-amber-500" />
      <span className="flex-1 text-muted-foreground">
        {message ??
          t(
            "common.refreshFailed",
            "Could not refresh — showing the last data loaded.",
          )}
      </span>
      <button
        type="button"
        onClick={onRetry}
        className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 font-medium text-amber-500 transition-colors hover:bg-amber-500/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <RefreshCw className="h-3 w-3" />
        {t("common.retry", "Retry")}
      </button>
    </div>
  );
}
