import { useState } from "react";
import { useTranslation } from "react-i18next";
import { SlidersHorizontal, Plus, X } from "lucide-react";
import { EditorSection } from "../../editor-section";
import type { CascadeHeuristic } from "../types";

/** Compact editor for a list of phrases (chips + add input). */
function PhraseList({
  label,
  phrases,
  onChange,
  readOnly,
  testId,
}: {
  label: string;
  phrases: string[];
  onChange: (next: string[]) => void;
  readOnly?: boolean;
  testId: string;
}) {
  const { t } = useTranslation();
  const [input, setInput] = useState("");

  const add = () => {
    const v = input.trim();
    if (v && !phrases.includes(v)) {
      onChange([...phrases, v]);
      setInput("");
    }
  };

  return (
    <div>
      <label className="mb-1 block text-[10px] text-muted-foreground">{label}</label>
      <div className="mb-1 flex flex-wrap gap-1" data-testid={testId}>
        {phrases.map((p) => (
          <span
            key={p}
            className="inline-flex items-center gap-0.5 rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary"
          >
            {p}
            {!readOnly && (
              <button
                type="button"
                onClick={() => onChange(phrases.filter((x) => x !== p))}
                className="rounded p-0.5 hover:bg-primary/20 transition-colors"
                aria-label={t("common.remove", "Remove")}
              >
                <X className="h-2.5 w-2.5" />
              </button>
            )}
          </span>
        ))}
        {phrases.length === 0 && (
          <span className="text-[10px] italic text-muted-foreground">
            {t("llmEditor.cascadeHeuristicDefaults", "Built-in defaults")}
          </span>
        )}
      </div>
      {!readOnly && (
        <div className="flex gap-1">
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                add();
              }
            }}
            placeholder={t("llmEditor.cascadeHeuristicPhrasePlaceholder", "add a phrase…")}
            className="h-6 flex-1 rounded border border-input bg-background px-1.5 text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
          <button
            type="button"
            onClick={add}
            aria-label={t("common.add", "Add")}
            className="inline-flex h-6 items-center rounded border border-input px-1.5 text-[10px] text-muted-foreground hover:text-foreground transition-colors"
          >
            <Plus className="h-2.5 w-2.5" />
          </button>
        </div>
      )}
    </div>
  );
}

/** One optional 0–1 score input with a placeholder showing the built-in default. */
function ScoreInput({
  label,
  value,
  defaultHint,
  onChange,
  readOnly,
}: {
  label: string;
  value?: number;
  defaultHint: string;
  onChange: (v: number | undefined) => void;
  readOnly?: boolean;
}) {
  return (
    <div>
      <label className="mb-0.5 block text-[10px] text-muted-foreground">{label}</label>
      <input
        type="text"
        inputMode="decimal"
        pattern="[0-9]*(\.[0-9]+)?"
        value={value ?? ""}
        onChange={(e) => {
          const v = e.target.value;
          onChange(v === "" ? undefined : isNaN(parseFloat(v)) ? undefined : parseFloat(v));
        }}
        readOnly={readOnly}
        placeholder={defaultHint}
        className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
      />
    </div>
  );
}

/**
 * Optional overrides for the `heuristic` confidence-evaluation strategy.
 * Every field is optional and falls back to the backend's built-in English
 * defaults when empty. Shown only when the evaluation strategy is "heuristic".
 */
export function CascadeHeuristicEditor({
  value,
  onChange,
  readOnly,
}: {
  value?: CascadeHeuristic;
  onChange: (v: CascadeHeuristic) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const h = value ?? {};
  const patch = (p: Partial<CascadeHeuristic>) => onChange({ ...h, ...p });

  return (
    <div data-testid="cascade-heuristic">
      <EditorSection
        label={t("llmEditor.cascadeHeuristic", "Heuristic tuning")}
        icon={SlidersHorizontal}
        accent="text-cyan-500"
        defaultOpen={false}
      >
        <div className="space-y-3 rounded-lg border border-border bg-card p-3">
          <p className="text-[10px] text-muted-foreground">
            {t(
              "llmEditor.cascadeHeuristicDesc",
              "Optional overrides for hedging/refusal detection. Empty fields use the built-in English defaults. Localize the phrase lists for non-English deployments.",
            )}
          </p>

          <PhraseList
            label={t("llmEditor.cascadeHeuristicPhrasesLow", "Hedging phrases (low confidence)")}
            phrases={h.lowConfidencePhrases ?? []}
            onChange={(next) => patch({ lowConfidencePhrases: next.length ? next : undefined })}
            readOnly={readOnly}
            testId="cascade-heuristic-low-phrases"
          />
          <PhraseList
            label={t("llmEditor.cascadeHeuristicPhrasesRefusal", "Refusal phrases")}
            phrases={h.refusalPhrases ?? []}
            onChange={(next) => patch({ refusalPhrases: next.length ? next : undefined })}
            readOnly={readOnly}
            testId="cascade-heuristic-refusal-phrases"
          />

          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("llmEditor.cascadeHeuristicShortLen", "Short response length")}
              </label>
              <input
                type="number"
                value={h.shortLengthThreshold ?? ""}
                onChange={(e) =>
                  patch({
                    shortLengthThreshold: e.target.value ? parseInt(e.target.value, 10) : undefined,
                  })
                }
                readOnly={readOnly}
                placeholder="20"
                className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
            <ScoreInput
              label={t("llmEditor.cascadeHeuristicShortScore", "Short score")}
              value={h.shortScore}
              defaultHint="0.3"
              onChange={(v) => patch({ shortScore: v })}
              readOnly={readOnly}
            />
            <ScoreInput
              label={t("llmEditor.cascadeHeuristicRefusalScore", "Refusal score")}
              value={h.refusalScore}
              defaultHint="0.2"
              onChange={(v) => patch({ refusalScore: v })}
              readOnly={readOnly}
            />
            <ScoreInput
              label={t("llmEditor.cascadeHeuristicHedgingScore", "Hedging score")}
              value={h.hedgingScore}
              defaultHint="0.4"
              onChange={(v) => patch({ hedgingScore: v })}
              readOnly={readOnly}
            />
            <ScoreInput
              label={t("llmEditor.cascadeHeuristicDefaultScore", "Default score")}
              value={h.defaultScore}
              defaultHint="0.8"
              onChange={(v) => patch({ defaultScore: v })}
              readOnly={readOnly}
            />
          </div>
        </div>
      </EditorSection>
    </div>
  );
}
