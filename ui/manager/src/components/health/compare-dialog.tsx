import { useId, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { ArrowDownRight, ArrowUpRight, Minus } from "lucide-react";
import type { AgentHealth, HealthCompare, Measure } from "./api";
import { useHealthCompare } from "./use-agent-health";
import { AccessibleDialog, Skeleton, cn, getErrorMessage } from "./deps";
import { TONE_CLASS, compareVerdict, formatDays, formatDelta, formatMeasureValue, measureLabel, selectClass, versionOptions } from "./format";

/** The older version's window, in days before the newer one took over. */
const COMPARE_DAYS = [7, 14, 30] as const;
const DEFAULT_DAYS = 14;

/** Indexes the measures of the given section sets by id. The first side given wins when an id appears on several, so passing the newer version first shows its label, unit and thresholds. */
function indexMeasures(...sides: Array<AgentHealth["sections"]>): Map<string, Measure> {
  const out = new Map<string, Measure>();
  for (const sections of sides) {
    for (const section of Object.values(sections)) {
      for (const m of section?.measures ?? []) if (!out.has(m.id)) out.set(m.id, m);
    }
  }
  return out;
}

/** The per-measure comparison of two versions: both values, the delta and a verdict. Verdicts are neutral unless the backend marks the change significant and directional (see `compareVerdict`). */
export function CompareTable({ data }: { data: HealthCompare }) {
  const { t } = useTranslation();
  const measures = useMemo(() => indexMeasures(data.to.sections, data.from.sections), [data]);
  const pp = t("health.unit.pp", "pp");
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs" data-testid="health-compare-table">
        <thead className="text-muted-foreground">
          <tr>
            <th className="p-1.5 text-start font-medium">{t("health.compare.measure", "Measure")}</th>
            <th className="p-1.5 text-end font-medium">{`v${data.from.version}`}</th>
            <th className="p-1.5 text-end font-medium">{`v${data.to.version}`}</th>
            <th className="p-1.5 text-end font-medium">{t("health.compare.change", "Change")}</th>
            <th className="p-1.5 text-start font-medium">{t("health.compare.verdict", "Verdict")}</th>
          </tr>
        </thead>
        <tbody>
          {data.deltas.map((d) => {
            const m = measures.get(d.measureId);
            const unit = m?.unit ?? "count";
            const v = compareVerdict(d);
            const Icon = v === "neutral" || !d.delta ? Minus : d.delta > 0 ? ArrowUpRight : ArrowDownRight;
            return (
              <tr key={d.measureId} className="border-t border-border" data-testid="health-compare-row" data-verdict={v}>
                <td className="p-1.5 font-medium">{m ? measureLabel(t, m) : d.measureId}</td>
                <td className="p-1.5 text-end tabular-nums">{formatMeasureValue(d.from, unit)}</td>
                <td className="p-1.5 text-end tabular-nums">{formatMeasureValue(d.to, unit)}</td>
                <td className={cn("p-1.5 text-end tabular-nums", TONE_CLASS[v])}>
                  <span className="inline-flex items-center gap-1">
                    <Icon className="h-3 w-3 rtl:-scale-x-100" aria-hidden="true" />
                    {formatDelta(d.delta, unit, pp)}
                  </span>
                </td>
                <td className={cn("p-1.5", TONE_CLASS[v])}>
                  {v === "better" && t("health.compare.better", "Better")}
                  {v === "worse" && t("health.compare.worse", "Worse")}
                  {v === "neutral" &&
                    (d.significant ? t("health.compare.noDirection", "Changed") : t("health.compare.noChange", "No clear change"))}
                  {v !== "neutral" && (
                    <span className="ms-1 text-muted-foreground">{t("health.compare.significant", "(significant)")}</span>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Two versions side by side: the newer since its first turn, the older over its
 * last days. The pickers hold only what the person chose (`null` is "the
 * default"), and the defaults are derived from `latestVersion` at render — so a
 * latest version that arrives late does not leave the dialog on v1.
 */
export function CompareDialog({
  open,
  onClose,
  agentId,
  latestVersion,
}: {
  open: boolean;
  onClose: () => void;
  agentId: string;
  latestVersion: number;
}) {
  const { t } = useTranslation();
  const fromId = useId();
  const toId = useId();
  const daysId = useId();
  const [chosenFrom, setChosenFrom] = useState<number | null>(null);
  const [chosenTo, setChosenTo] = useState<number | null>(null);
  const [days, setDays] = useState<number>(DEFAULT_DAYS);

  const to = chosenTo ?? (latestVersion >= 2 ? latestVersion : null);
  // The older side must really be older: a stale choice at or above the newer one falls back to the default.
  const defaultFrom = to !== null && to > 1 ? to - 1 : null;
  const from = chosenFrom !== null && to !== null && chosenFrom < to ? chosenFrom : defaultFrom;
  const compare = useHealthCompare(agentId, open ? from : null, open ? to : null, days);
  const options = versionOptions(latestVersion);

  return (
    <AccessibleDialog open={open} onClose={onClose} title={t("health.compare.title", "Compare versions")} maxWidth="max-w-3xl" testId="health-compare-dialog">
      <div className="space-y-3">
        <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
          <label htmlFor={fromId} className="flex items-center gap-1.5">
            {t("health.compare.older", "Older")}
            <select
              id={fromId}
              className={selectClass}
              value={from ?? ""}
              onChange={(e) => setChosenFrom(e.target.value === "" ? null : Number(e.target.value))}
              data-testid="health-compare-from"
            >
              {from === null && <option value="">—</option>}
              {options.filter((v) => to === null || v < to).map((v) => (
                <option key={v} value={v}>{`v${v}`}</option>
              ))}
            </select>
          </label>
          <label htmlFor={toId} className="flex items-center gap-1.5">
            {t("health.compare.newer", "Newer")}
            <select
              id={toId}
              className={selectClass}
              value={to ?? ""}
              onChange={(e) => setChosenTo(e.target.value === "" ? null : Number(e.target.value))}
              data-testid="health-compare-to"
            >
              {to === null && <option value="">—</option>}
              {options.filter((v) => v > 1).map((v) => (
                <option key={v} value={v}>{`v${v}`}</option>
              ))}
            </select>
          </label>
          <label htmlFor={daysId} className="flex items-center gap-1.5">
            {t("health.compare.olderWindow", "Older version's last")}
            <select id={daysId} className={selectClass} value={days} onChange={(e) => setDays(Number(e.target.value))} data-testid="health-compare-days">
              {COMPARE_DAYS.map((d) => (
                <option key={d} value={d}>{formatDays(d)}</option>
              ))}
            </select>
          </label>
        </div>
        {from === null && to === null && (
          <p className="text-sm text-muted-foreground" data-testid="health-compare-needs-two">
            {t("health.compare.needTwo", "This agent has only one version, so there is nothing to compare yet.")}
          </p>
        )}
        {compare.isLoading && <Skeleton className="h-24 w-full" />}
        {compare.isError && <p className="text-sm text-destructive" role="alert">{getErrorMessage(compare.error)}</p>}
        {compare.data && (
          <>
            <p className="text-xs text-muted-foreground">
              {[compare.data.from, compare.data.to]
                .map((side) => `v${side.version}: ${side.window.from} – ${side.window.to}`)
                .join(" · ")}
            </p>
            <CompareTable data={compare.data} />
          </>
        )}
      </div>
    </AccessibleDialog>
  );
}
