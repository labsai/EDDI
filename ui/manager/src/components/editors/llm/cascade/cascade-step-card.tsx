import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Plus,
  Trash2,
  ArrowUp,
  ArrowDown,
  X,
  ChevronDown,
  ChevronRight,
} from "lucide-react";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { MODEL_TYPES, type CascadeStep } from "../types";
import type { CascadeIssue } from "./cascade-validation";
import { CascadeIssues } from "./cascade-issues";
import { parseNum as num, nextParamKey } from "./cascade-utils";

/**
 * Params owned by the dedicated controls (model input + API key picker), hidden
 * from the advanced grid. Matched EXACT-case: the dedicated controls only read
 * `model` / `apiKey`, so a mis-cased key like `apikey` / `Model` must stay
 * visible in the grid — otherwise the cross-provider warning fires on a key the
 * user cannot see or remove.
 */
const STEP_HIDDEN_PARAM_KEYS = new Set(["model", "apiKey"]);
const SENSITIVE_KEYS = new Set(["apikey", "password", "secret", "token"]);

/**
 * One cascade step: provider + model, confidence/timeout, cross-provider API key,
 * and (collapsed) per-step pricing + extra parameters. Renders its own inline
 * validation issues.
 */
export function CascadeStepCard({
  step,
  index,
  totalSteps,
  taskType,
  issues,
  onChange,
  onMoveUp,
  onMoveDown,
  onRemove,
  readOnly,
}: {
  step: CascadeStep;
  index: number;
  totalSteps: number;
  taskType?: string;
  issues: CascadeIssue[];
  onChange: (patch: Partial<CascadeStep>) => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onRemove: () => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [advancedOpen, setAdvancedOpen] = useState(false);

  const params = step.parameters ?? {};
  const setParam = (key: string, val: string) =>
    onChange({ parameters: { ...params, [key]: val } });
  const removeParam = (key: string) => {
    const next = { ...params };
    delete next[key];
    onChange({ parameters: next });
  };

  const extraParamEntries = Object.entries(params).filter(
    ([k]) => !STEP_HIDDEN_PARAM_KEYS.has(k),
  );
  const isCrossProvider = !!step.type && step.type !== taskType;

  return (
    <div
      className="space-y-2 rounded-lg border border-border bg-card p-3"
      data-testid={`cascade-step-${index}`}
    >
      {/* Header: provider + model + reorder/remove */}
      <div className="flex items-center gap-2">
        <span className="inline-flex h-5 w-5 items-center justify-center rounded-full bg-primary/10 text-[10px] font-bold text-primary">
          {index + 1}
        </span>
        <select
          value={step.type ?? "openai"}
          onChange={(e) => onChange({ type: e.target.value })}
          disabled={readOnly}
          className="h-7 rounded-md border border-input bg-background px-2 text-xs font-semibold text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
        >
          {MODEL_TYPES.map((mt) => (
            <option key={mt} value={mt}>
              {mt}
            </option>
          ))}
        </select>
        <input
          type="text"
          value={params.model ?? ""}
          onChange={(e) => setParam("model", e.target.value)}
          readOnly={readOnly}
          placeholder={t("llmEditor.cascadeModelName", "e.g. claude-sonnet-5")}
          className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
        />
        {!readOnly && (
          <div className="flex items-center gap-0.5">
            <button
              type="button"
              disabled={index === 0}
              onClick={onMoveUp}
              className="rounded p-1 text-muted-foreground transition-colors hover:text-foreground disabled:opacity-30"
              title={t("llmEditor.moveUp", "Move up")}
              aria-label={t("llmEditor.moveUp", "Move up")}
            >
              <ArrowUp className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              disabled={index === totalSteps - 1}
              onClick={onMoveDown}
              className="rounded p-1 text-muted-foreground transition-colors hover:text-foreground disabled:opacity-30"
              title={t("llmEditor.moveDown", "Move down")}
              aria-label={t("llmEditor.moveDown", "Move down")}
            >
              <ArrowDown className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              onClick={onRemove}
              className="rounded p-1 text-muted-foreground transition-colors hover:text-destructive"
              title={t("llmEditor.removeStep", "Remove step")}
              aria-label={t("llmEditor.removeStep", "Remove step")}
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          </div>
        )}
      </div>

      {/* Confidence + timeout */}
      <div className="grid grid-cols-2 gap-2 ps-7">
        <div>
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("llmEditor.cascadeConfidence", "Min. Confidence (0–1)")}
          </label>
          <input
            type="text"
            inputMode="decimal"
            pattern="[0-9]*(\.[0-9]+)?"
            value={step.confidenceThreshold ?? ""}
            onChange={(e) => {
              const v = e.target.value;
              onChange({
                confidenceThreshold:
                  v === "" ? null : isNaN(parseFloat(v)) ? null : parseFloat(v),
              });
            }}
            readOnly={readOnly}
            placeholder={t("llmEditor.cascadeConfidencePlaceholder", "empty = always accept")}
            className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>
        <div>
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("llmEditor.cascadeTimeout", "Timeout (ms)")}
          </label>
          <input
            type="number"
            value={step.timeoutMs ?? ""}
            onChange={(e) => onChange({ timeoutMs: num(e.target.value) })}
            readOnly={readOnly}
            placeholder="30000"
            className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>
      </div>

      {/* API key (needed for cross-provider steps) */}
      <div className="ps-7">
        <label className="mb-0.5 block text-[10px] text-muted-foreground">
          {t("llmEditor.cascadeStepApiKey", "API Key")}
          {isCrossProvider && (
            <span className="ms-1 rounded bg-amber-500/15 px-1 py-0.5 text-[9px] font-semibold uppercase text-amber-600 dark:text-amber-400">
              {t("llmEditor.cascadeCrossProvider", "cross-provider")}
            </span>
          )}
        </label>
        <SecretKeyPicker
          value={params.apiKey ?? ""}
          onChange={(v) => setParam("apiKey", v)}
          readOnly={readOnly}
          placeholder={t("llmEditor.cascadeStepApiKeyPlaceholder", "inherits task key if empty")}
          testId={`cascade-step-apikey-${index}`}
        />
      </div>

      {/* Advanced: pricing + extra parameters */}
      <div className="ps-7">
        <button
          type="button"
          onClick={() => setAdvancedOpen((o) => !o)}
          className="flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground transition-colors hover:text-foreground"
          data-testid={`cascade-step-advanced-toggle-${index}`}
        >
          {advancedOpen ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
          {t("llmEditor.cascadeStepAdvanced", "Advanced (pricing & parameters)")}
        </button>

        {advancedOpen && (
          <div className="mt-2 space-y-2.5">
            {/* Per-step pricing */}
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">
                  {t("llmEditor.cascadeStepInputPrice", "Input $ / 1M")}
                </label>
                <input
                  type="number"
                  step="0.01"
                  value={step.inputPricePer1M ?? ""}
                  onChange={(e) => onChange({ inputPricePer1M: num(e.target.value) })}
                  readOnly={readOnly}
                  placeholder={t("llmEditor.cascadeStepPriceInherit", "cascade default")}
                  className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              </div>
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">
                  {t("llmEditor.cascadeStepOutputPrice", "Output $ / 1M")}
                </label>
                <input
                  type="number"
                  step="0.01"
                  value={step.outputPricePer1M ?? ""}
                  onChange={(e) => onChange({ outputPricePer1M: num(e.target.value) })}
                  readOnly={readOnly}
                  placeholder={t("llmEditor.cascadeStepPriceInherit", "cascade default")}
                  className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              </div>
            </div>

            {/* Extra parameters (baseUrl, temperature, …) */}
            <div>
              <label className="mb-1 block text-[10px] text-muted-foreground">
                {t("llmEditor.cascadeStepParams", "Extra parameters")}
              </label>
              <div className="space-y-1.5">
                {extraParamEntries.map(([k, v]) => {
                  const sensitive = SENSITIVE_KEYS.has(k.toLowerCase());
                  return (
                    <div key={k} className="flex items-center gap-1.5">
                      <input
                        type="text"
                        value={k}
                        readOnly
                        className="h-7 w-24 rounded border border-input bg-muted px-2 text-xs text-foreground"
                      />
                      {sensitive ? (
                        <div className="flex-1">
                          <SecretKeyPicker
                            value={v}
                            onChange={(val) => setParam(k, val)}
                            readOnly={readOnly}
                            placeholder={"${vault:...}"}
                            testId={`cascade-step-${index}-param-${k}`}
                          />
                        </div>
                      ) : (
                        <input
                          type="text"
                          value={v}
                          onChange={(e) => setParam(k, e.target.value)}
                          readOnly={readOnly}
                          className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                      )}
                      {!readOnly && (
                        <button
                          type="button"
                          onClick={() => removeParam(k)}
                          aria-label={t("common.remove", "Remove")}
                          className="rounded p-1 text-muted-foreground transition-colors hover:text-destructive"
                        >
                          <X className="h-3 w-3" />
                        </button>
                      )}
                    </div>
                  );
                })}
                {!readOnly && (
                  <button
                    type="button"
                    onClick={() => setParam(nextParamKey(params), "")}
                    className="inline-flex items-center gap-1 rounded px-2 py-1 text-[10px] text-muted-foreground transition-colors hover:text-foreground"
                  >
                    <Plus className="h-3 w-3" />
                    {t("llmEditor.cascadeStepAddParam", "Add parameter")}
                  </button>
                )}
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Inline validation for this step */}
      {issues.length > 0 && <CascadeIssues issues={issues} className="ps-7" />}
    </div>
  );
}
