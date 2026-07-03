import { useTranslation } from "react-i18next";
import { Gauge } from "lucide-react";
import type { ModelCascadeConfig } from "../types";

/** Parse a number input into a value or `undefined` when blank/invalid. */
function num(v: string): number | undefined {
  if (v === "") return undefined;
  const n = parseFloat(v);
  return isNaN(n) ? undefined : n;
}

/**
 * Cost & time ceilings plus cascade-level default token pricing. All optional;
 * empty means "unlimited" (ceilings) or "no pricing" (prices).
 */
export function CascadeCeilings({
  cascade,
  onChange,
  readOnly,
}: {
  cascade: ModelCascadeConfig;
  onChange: (patch: Partial<ModelCascadeConfig>) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();

  return (
    <div className="space-y-2.5 rounded-lg border border-border bg-card p-3" data-testid="cascade-ceilings">
      <label className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
        <Gauge className="h-3 w-3" />
        {t("llmEditor.cascadeCeilings", "Ceilings & Pricing")}
      </label>

      <div className="grid grid-cols-2 gap-2">
        <div>
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("llmEditor.cascadeMaxDuration", "Max total duration (ms)")}
          </label>
          <input
            type="number"
            value={cascade.maxTotalDurationMs ?? ""}
            onChange={(e) => onChange({ maxTotalDurationMs: num(e.target.value) })}
            readOnly={readOnly}
            placeholder={t("llmEditor.cascadeUnlimited", "unlimited")}
            className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            data-testid="cascade-max-duration"
          />
        </div>
        <div>
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("llmEditor.cascadeMaxCost", "Max cost per run ($)")}
          </label>
          <input
            type="number"
            step="0.01"
            value={cascade.maxCostPerRun ?? ""}
            onChange={(e) => onChange({ maxCostPerRun: num(e.target.value) })}
            readOnly={readOnly}
            placeholder={t("llmEditor.cascadeUnlimited", "unlimited")}
            className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            data-testid="cascade-max-cost"
          />
        </div>
        <div>
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("llmEditor.cascadeInputPrice", "Input $ / 1M tokens")}
          </label>
          <input
            type="number"
            step="0.01"
            value={cascade.inputPricePer1M ?? ""}
            onChange={(e) => onChange({ inputPricePer1M: num(e.target.value) })}
            readOnly={readOnly}
            placeholder="0.00"
            className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            data-testid="cascade-input-price"
          />
        </div>
        <div>
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("llmEditor.cascadeOutputPrice", "Output $ / 1M tokens")}
          </label>
          <input
            type="number"
            step="0.01"
            value={cascade.outputPricePer1M ?? ""}
            onChange={(e) => onChange({ outputPricePer1M: num(e.target.value) })}
            readOnly={readOnly}
            placeholder="0.00"
            className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            data-testid="cascade-output-price"
          />
        </div>
      </div>
      <p className="text-[10px] text-muted-foreground">
        {t(
          "llmEditor.cascadePricingHint",
          "Default token pricing feeds cost tracking and the cost ceiling. Individual steps can override it.",
        )}
      </p>
    </div>
  );
}
