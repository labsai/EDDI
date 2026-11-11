import { useTranslation } from "react-i18next";
import type { HealthStatus, Reason } from "./api";
import { cn } from "./deps";
import { BAND_META, bandMeta, statusLabelKey } from "./format";
import { StatusIcon } from "./band-badge";

const STATUS_BAND = {
  HEALTHY: "GREEN",
  WATCH: "AMBER",
  NEEDS_ATTENTION: "RED",
  NOT_ENOUGH_DATA: "GREY",
} as const;

/** The status as a ring with an icon, the word beside it, and the top reasons beneath. */
export function StatusRing({ status, reasons }: { status: HealthStatus; reasons: Reason[] }) {
  const { t } = useTranslation();
  const [key, fallback] = statusLabelKey(status);
  const meta = BAND_META[STATUS_BAND[status] ?? "GREY"];
  return (
    <div className="flex flex-wrap items-start gap-4" data-testid="health-status">
      <div
        className={cn("flex h-14 w-14 shrink-0 items-center justify-center rounded-full border-4", meta.border, meta.text)}
        data-testid="health-status-ring"
      >
        <StatusIcon status={status} className="h-6 w-6" />
      </div>
      <div className="min-w-0 flex-1">
        <p className={cn("text-lg font-semibold", meta.text)} data-testid="health-status-label">
          {t(key, fallback)}
        </p>
        {reasons.length > 0 ? (
          <ul className="mt-1 space-y-1 text-sm" data-testid="health-reasons">
            {reasons.slice(0, 3).map((r) => (
              <li key={`${r.measureId}-${r.text}`} className="flex items-start gap-2">
                <span aria-hidden="true" className={cn("mt-1.5 h-2 w-2 shrink-0 rounded-full bg-current", bandMeta(r.severity).text)} />
                <span className="text-foreground">{r.text}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="mt-1 text-sm text-muted-foreground">{t("health.noReasons", "No measure needs attention.")}</p>
        )}
      </div>
    </div>
  );
}
