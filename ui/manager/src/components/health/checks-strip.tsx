import { useTranslation } from "react-i18next";
import { CheckCircle2, CircleDashed, Clock, XCircle, type LucideIcon } from "lucide-react";
import type { HealthCheck } from "./api";
import { cn } from "./deps";
import { formatAge } from "./format";

interface CheckMeta {
  Icon: LucideIcon;
  classes: string;
  key: string;
  fallback: string;
}

const STATE: Record<string, CheckMeta> = {
  PASS: { Icon: CheckCircle2, classes: "border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400", key: "pass", fallback: "Passed" },
  FAIL: { Icon: XCircle, classes: "border-destructive/40 bg-destructive/10 text-destructive", key: "fail", fallback: "Failed" },
};
const UNKNOWN: CheckMeta = { Icon: CircleDashed, classes: "border-border bg-muted text-muted-foreground", key: "unknown", fallback: "Not run" };

/** Checks such as the golden suite: pass/fail as a badge, with its age and a stale flag. */
export function ChecksStrip({ checks }: { checks: HealthCheck[] }) {
  const { t } = useTranslation();
  if (checks.length === 0) return null;
  return (
    <ul className="flex flex-wrap gap-2" data-testid="health-checks">
      {checks.map((c) => {
        const meta = STATE[c.state] ?? UNKNOWN;
        return (
          <li
            key={c.id}
            className={cn("inline-flex flex-wrap items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs", meta.classes)}
            data-testid="health-check"
            data-state={c.state}
          >
            <meta.Icon className="h-3.5 w-3.5" aria-hidden="true" />
            <span className="font-medium">{c.label}</span>
            <span>{t(`health.check.${meta.key}`, meta.fallback)}</span>
            {c.version !== undefined && c.version !== null && <span>{`v${c.version}`}</span>}
            {c.at && <span>{formatAge(c.at)}</span>}
            {c.stale && (
              <span className="inline-flex items-center gap-0.5 font-medium" data-testid="health-check-stale">
                <Clock className="h-3 w-3" aria-hidden="true" />
                {t("health.check.stale", "stale")}
              </span>
            )}
          </li>
        );
      })}
    </ul>
  );
}
