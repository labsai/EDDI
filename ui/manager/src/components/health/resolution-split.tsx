import { useTranslation } from "react-i18next";
import { Info } from "lucide-react";
import type { Measure } from "@/lib/api/health";
import { cn } from "./deps";
import { formatMeasureValue, resolutionSplit, type ResolutionKey } from "./format";

const SEGMENT: Record<ResolutionKey, { label: [string, string]; bar: string; hint: [string, string] }> = {
  confirmed: {
    label: ["health.resolution.confirmed", "Confirmed"],
    bar: "bg-emerald-600 dark:bg-emerald-400",
    hint: ["health.resolution.confirmedHint", "An outcome event or a mapped action said the problem was solved."],
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
 * much is the agent closing the chat, and how much is users going quiet.
 * Segments carry a text label and a hatch-free solid/muted weight, so the
 * split reads without colour.
 */
export function ResolutionSplit({ measure, onDrill }: { measure: Measure; onDrill?: (key: ResolutionKey) => void }) {
  const { t } = useTranslation();
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
      <div className="flex h-2 overflow-hidden rounded-full bg-muted" role="presentation">
        {split.parts.map((p) => (
          <div key={p.key} className={cn("h-full", SEGMENT[p.key].bar)} style={{ width: `${p.share * 100}%` }} />
        ))}
      </div>
      <ul className="grid gap-1 text-xs sm:grid-cols-3">
        {split.parts.map((p) => {
          const [labelKey, labelFallback] = SEGMENT[p.key].label;
          const [hintKey, hintFallback] = SEGMENT[p.key].hint;
          const content = (
            <>
              <span aria-hidden="true" className={cn("inline-block h-2 w-2 rounded-full", SEGMENT[p.key].bar)} />
              <span className="font-medium text-foreground">{t(labelKey, labelFallback)}</span>
              <span className="tabular-nums text-muted-foreground" data-testid={`resolution-${p.key}`}>
                {formatMeasureValue(p.value, "ratio")}
              </span>
            </>
          );
          return (
            <li key={p.key} title={t(hintKey, hintFallback)}>
              {onDrill ? (
                <button type="button" className="flex items-center gap-1.5 rounded px-1 text-start hover:bg-secondary/60" onClick={() => onDrill(p.key)}>
                  {content}
                </button>
              ) : (
                <span className="flex items-center gap-1.5 px-1">{content}</span>
              )}
            </li>
          );
        })}
      </ul>
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
