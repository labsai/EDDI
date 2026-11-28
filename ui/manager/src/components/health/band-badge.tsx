import { useTranslation } from "react-i18next";
import { AlertTriangle, CheckCircle2, CircleDashed, OctagonAlert, XCircle } from "lucide-react";
import type { Band, HealthStatus } from "@eddi/app-kit/lib/api/health";
import { Badge, cn } from "./deps";
import { bandMeta, statusLabelKey, STATUS_META } from "./format";

const BAND_ICON = { GREEN: CheckCircle2, AMBER: AlertTriangle, RED: XCircle, GREY: CircleDashed } as const;
const STATUS_ICON = {
  HEALTHY: CheckCircle2,
  WATCH: AlertTriangle,
  NEEDS_ATTENTION: OctagonAlert,
  NOT_ENOUGH_DATA: CircleDashed,
} as const;

/** Band as icon + word + colour: never colour alone. */
export function BandBadge({ band, className }: { band: Band | string; className?: string }) {
  const { t } = useTranslation();
  const meta = bandMeta(band);
  const Icon = BAND_ICON[band as Band] ?? CircleDashed;
  return (
    <Badge variant={meta.badge} className={cn("gap-1 px-2 font-medium", className)} data-testid="health-band" data-band={band}>
      <Icon className="h-3 w-3" aria-hidden="true" />
      {t(`health.band.${meta.key}`, meta.fallback)}
    </Badge>
  );
}

/** Status as icon + word + colour. */
export function StatusBadge({ status, className }: { status: HealthStatus; className?: string }) {
  const { t } = useTranslation();
  const meta = STATUS_META[status] ?? STATUS_META.NOT_ENOUGH_DATA;
  const Icon = STATUS_ICON[status] ?? CircleDashed;
  const [key, fallback] = statusLabelKey(status);
  return (
    <Badge variant={meta.badge} className={cn("gap-1.5 py-1", className)} data-testid="health-status-badge" data-status={status}>
      <Icon className="h-3.5 w-3.5" aria-hidden="true" />
      {t(key, fallback)}
    </Badge>
  );
}

/** The icon of an overall status, decorative (`aria-hidden`: the label next to it carries the meaning). An unknown status gets the dashed circle of "not enough data". */
export function StatusIcon({ status, className }: { status: HealthStatus; className?: string }) {
  const Icon = STATUS_ICON[status] ?? CircleDashed;
  return <Icon className={className} aria-hidden="true" />;
}
