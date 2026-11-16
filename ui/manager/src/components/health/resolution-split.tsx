import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Info } from "lucide-react";
import type { Measure } from "./api";
import { cn } from "./deps";
import { formatMeasureValue, resolutionSplit, type ResolutionKey } from "./format";

const SEGMENT: Record<ResolutionKey, { label: [string, string]; bar: string; hint: [string, string] }> = {
  confirmed: {
    label: ["health.resolution.confirmed", "Confirmed"],
    bar: "bg-emerald-600 dark:bg-emerald-400",
    hint: ["health.resolution.confirmedHint", "An outcome event or a mapped action said the problem was solved."],
  },
  // Weak evidence — the model's own report — so it is drawn lighter and dashed.
  selfReported: {
    label: ["health.resolution.selfReported", "Self-reported"],
    bar: "bg-emerald-600/35 dark:bg-emerald-400/35 border border-dashed border-emerald-600 dark:border-emerald-400",
    hint: ["health.resolution.selfReportedHint", "The agent's own model reported the problem as solved. Weak evidence."],
  },
  agentClosed: {
    label: ["health.resolution.agentClosed", "Agent closed"],
    bar: "bg-emerald-600/60 dark:bg-emerald-400/60",
    hint: ["health.resolution.agentClosedHint", "The agent ended the conversation after a successful turn."],
  },
  silence: {
    label: ["health.resolution.silence", "Silence"],
    bar: "bg-muted-foreground/50",
    hint: ["health.resolution.silenceHint", "The user simply stopped writing after a successful turn."],
  },
};

/**
 * The resolution rate is never one number: how much of it is confirmed, how
 * much the model reported itself, how much is the agent closing the chat, and
 * how much is users going quiet. Each part is its share of the resolved
 * sessions (whatever the backend's unit), with a text label, so the split reads
 * without colour; the legend is a grid, so labels never run into each other.
 */
export function ResolutionSplit({ measure, onDrill }: { measure: Measure; onDrill?: (key: ResolutionKey) => void }) {
  const { t } = useTranslation();
  const [showHints, setShowHints] = useState(false);
  const hintsId = useId();
  const split = resolutionSplit(measure);
  if (!split) {
    return (
      <p className="text-xs text-muted-foreground" data-testid="resolution-split-missing">
        {t("health.resolution.noSplit", "The split into confirmed, agent-closed and silence is not available yet.")}
      </p>
    );
  }
  return (
    <div className="space-y-2" data-testid="resolution-split">
      <p className="text-xs text-muted-foreground">{t("health.resolution.shareNote", "Where the resolved sessions come from")}</p>
      <div className="flex h-2 overflow-hidden rounded-full bg-muted" role="presentation">
        {split.parts.map((p) => (
          <div key={p.key} className={cn("h-full", SEGMENT[p.key].bar)} style={{ width: `${p.share * 100}%` }} />
        ))}
      </div>
      <ul className="grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
        {split.parts.map((p) => {
          const [labelKey, labelFallback] = SEGMENT[p.key].label;
          const content = (
            <>
              <span aria-hidden="true" className={cn("mt-0.5 inline-block h-2.5 w-2.5 shrink-0 rounded-full", SEGMENT[p.key].bar)} />
              <span className="min-w-0">
                <span className="block font-medium text-foreground">{t(labelKey, labelFallback)}</span>
                <span className="block tabular-nums text-muted-foreground" data-testid={`resolution-${p.key}`}>
                  {formatMeasureValue(p.share, "ratio")}
                </span>
              </span>
            </>
          );
          return (
            <li key={p.key}>
              {onDrill ? (
                <button type="button" className="flex w-full items-start gap-1.5 rounded px-1 text-start hover:bg-secondary/60" onClick={() => onDrill(p.key)}>
                  {content}
                </button>
              ) : (
                <span className="flex items-start gap-1.5 px-1">{content}</span>
              )}
            </li>
          );
        })}
      </ul>
      <button
        type="button"
        className="text-xs text-brand-700 hover:underline dark:text-brand-300"
        aria-expanded={showHints}
        aria-controls={hintsId}
        onClick={() => setShowHints((v) => !v)}
      >
        {t("health.resolution.whatMeans", "What do these mean?")}
      </button>
      {showHints && (
        <dl id={hintsId} className="space-y-1 text-xs text-muted-foreground" data-testid="resolution-hints">
          {split.parts.map((p) => (
            <div key={p.key}>
              <dt className="inline font-medium text-foreground">{t(SEGMENT[p.key].label[0], SEGMENT[p.key].label[1])}: </dt>
              <dd className="inline">{t(SEGMENT[p.key].hint[0], SEGMENT[p.key].hint[1])}</dd>
            </div>
          ))}
        </dl>
      )}
      {split.mostlySilence && (
        <p className="flex items-start gap-1.5 text-xs text-warning" role="note" data-testid="resolution-silence-warning">
          <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          {t("health.resolution.mostlySilence", {
            share: Math.round(split.silenceShare * 100),
            defaultValue: "Mostly assumed from silence: {{share}} % of resolutions are users going quiet, not confirmed outcomes.",
          })}
        </p>
      )}
    </div>
  );
}
