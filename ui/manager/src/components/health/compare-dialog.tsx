import { useId, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { ArrowDownRight, ArrowUpRight, Minus } from "lucide-react";
import type { AgentHealth, HealthCompare, Measure } from "@/lib/api/health";
import { useHealthCompare } from "@/hooks/use-agent-health";
import { AccessibleDialog, Skeleton, cn, getErrorMessage } from "./deps";
import { compareVerdict, formatDelta, formatMeasureValue, measureLabel, versionOptions } from "./format";

function indexMeasures(...sides: Array<AgentHealth["sections"]>): Map<string, Measure> {
  const out = new Map<string, Measure>();
  for (const sections of sides) {
    for (const section of Object.values(sections)) {
      for (const m of section?.measures ?? []) if (!out.has(m.id)) out.set(m.id, m);
    }
  }
  return out;
}

const TONE = { better: "text-emerald-700 dark:text-emerald-400", worse: "text-destructive", neutral: "text-muted-foreground" } as const;

export function CompareTable({ data }: { data: HealthCompare }) {
  const { t } = useTranslation();
  const measures = useMemo(() => indexMeasures(data.to.sections, data.from.sections), [data]);
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
            const Icon = v === "neutral" ? Minus : d.delta! > 0 ? ArrowUpRight : ArrowDownRight;
            return (
              <tr key={d.measureId} className="border-t border-border" data-testid="health-compare-row" data-verdict={v}>
                <td className="p-1.5 font-medium">{m ? measureLabel(t, m) : d.measureId}</td>
                <td className="p-1.5 text-end tabular-nums">{formatMeasureValue(d.from, unit)}</td>
                <td className="p-1.5 text-end tabular-nums">{formatMeasureValue(d.to, unit)}</td>
                <td className={cn("p-1.5 text-end tabular-nums", TONE[v])}>
                  <span className="inline-flex items-center gap-1">
                    <Icon className="h-3 w-3 rtl:-scale-x-100" aria-hidden="true" />
                    {formatDelta(d.delta, unit)}
                  </span>
                </td>
                <td className={cn("p-1.5", TONE[v])}>
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

/** Two versions side by side: the newer since its first turn, the older over its last days. */
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
  const [from, setFrom] = useState<number | null>(latestVersion > 1 ? latestVersion - 1 : null);
  const [to, setTo] = useState<number | null>(latestVersion > 0 ? latestVersion : null);
  const compare = useHealthCompare(agentId, open ? from : null, open ? to : null);
  const options = versionOptions(latestVersion);

  const select = (id: string, value: number | null, set: (v: number | null) => void, testId: string) => (
    <select
      id={id}
      className="h-8 rounded-lg border border-input bg-background px-2 text-xs"
      value={value ?? ""}
      onChange={(e) => set(e.target.value === "" ? null : Number(e.target.value))}
      data-testid={testId}
    >
      <option value="">—</option>
      {options.map((v) => (
        <option key={v} value={v}>{`v${v}`}</option>
      ))}
    </select>
  );

  return (
    <AccessibleDialog open={open} onClose={onClose} title={t("health.compare.title", "Compare versions")} maxWidth="max-w-3xl" testId="health-compare-dialog">
      <div className="space-y-3">
        <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
          <label htmlFor={fromId} className="flex items-center gap-1.5">
            {t("health.compare.older", "Older")}
            {select(fromId, from, setFrom, "health-compare-from")}
          </label>
          <label htmlFor={toId} className="flex items-center gap-1.5">
            {t("health.compare.newer", "Newer")}
            {select(toId, to, setTo, "health-compare-to")}
          </label>
        </div>
        {from !== null && to !== null && from === to && (
          <p className="text-sm text-muted-foreground">{t("health.compare.same", "Pick two different versions.")}</p>
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
