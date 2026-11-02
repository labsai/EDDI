import { useId } from "react";
import { useTranslation } from "react-i18next";
import { DollarSign } from "lucide-react";
import { EditorSection } from "../editor-section";
import type { TaskSectionProps } from "./task-section-props";
import { parseNum as num } from "./cascade/cascade-utils";
import { useResolvedModelPrice } from "@/hooks/use-model-prices";

/**
 * Token pricing for this task's ORDINARY (non-cascade) model calls (N1).
 * Deliberately its own section, not nested inside Model Cascade: these prices
 * matter MOST when the cascade is off (the common case), and that section
 * starts collapsed exactly then — burying the one place a non-cascade user
 * would set pricing inside a section named after a feature they aren't using.
 * A cascade run is priced by its own steps instead (Model Cascade → Ceilings
 * & Pricing) — cascade steps may target different models than this task's own.
 */
export function TaskPricingSection({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();
  // useId, not a fixed string: the LLM editor can render several task sections
  // at once, and duplicate ids would point every label at the first input.
  const inputPriceId = useId();
  const outputPriceId = useId();

  return (
    <EditorSection label={t("llmEditor.taskPricing", "Plain-Call Pricing")} icon={DollarSign} accent="text-emerald-500">
      <div className="space-y-2" data-testid="task-pricing-section">
        <p className="text-[10px] leading-relaxed text-muted-foreground">
          {t(
            "llmEditor.taskPricingDesc",
            "Prices this task's ordinary (non-cascade) model calls for cost tracking and group cost ceilings. Unpriced (blank) contributes $0.",
          )}
        </p>
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label htmlFor={inputPriceId} className="mb-0.5 block text-[10px] text-muted-foreground">
              {t("llmEditor.cascadeInputPrice", "Input $ / 1M tokens")}
            </label>
            <input
              id={inputPriceId}
              type="number"
              min={0}
              step="0.01"
              value={task.inputPricePer1M ?? ""}
              onChange={(e) => onChange({ ...task, inputPricePer1M: num(e.target.value) })}
              readOnly={readOnly}
              placeholder="0.00"
              className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              data-testid="task-input-price"
            />
          </div>
          <div>
            <label htmlFor={outputPriceId} className="mb-0.5 block text-[10px] text-muted-foreground">
              {t("llmEditor.cascadeOutputPrice", "Output $ / 1M tokens")}
            </label>
            <input
              id={outputPriceId}
              type="number"
              min={0}
              step="0.01"
              value={task.outputPricePer1M ?? ""}
              onChange={(e) => onChange({ ...task, outputPricePer1M: num(e.target.value) })}
              readOnly={readOnly}
              placeholder="0.00"
              className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              data-testid="task-output-price"
            />
          </div>
        </div>
        <CatalogPriceHint task={task} />
        <AdvancedPricing task={task} onChange={onChange} readOnly={readOnly} />
      </div>
    </EditorSection>
  );
}

/** The model name under whichever key the task's provider reads it. */
function modelOf(task: TaskSectionProps["task"]): string | undefined {
  const p = task.parameters ?? {};
  return p.modelName || p.model || p.modelId || p.deploymentName || undefined;
}

/**
 * Which deployment catalog price applies when the task sets none of its own —
 * so "blank" no longer silently means $0 when the catalog prices the model.
 */
function CatalogPriceHint({ task }: Pick<TaskSectionProps, "task">) {
  const { t } = useTranslation();
  const explicit = task.inputPricePer1M != null || task.outputPricePer1M != null;
  const { data, isSuccess } = useResolvedModelPrice(explicit ? undefined : task.type, explicit ? undefined : modelOf(task));
  if (explicit || !isSuccess || !data) return null;
  return (
    <p className="text-[10px] leading-relaxed text-muted-foreground" data-testid="task-pricing-catalog-hint">
      {data.matched && data.entry
        ? t("modelPrices.hintCatalog", "Catalog price applies: ${{input}} in / ${{output}} out per 1M ({{provider}}/{{model}}).", {
            input: data.entry.inputPricePer1M ?? "—",
            output: data.entry.outputPricePer1M ?? "—",
            provider: data.entry.provider,
            model: data.entry.model,
          })
        : t("modelPrices.hintUnpriced", "No catalog price for this model: its calls count $0 against every budget.")}
    </p>
  );
}

type PricingField = "cacheReadPricePer1M" | "cacheWritePricePer1M";
type TierField = "inputPricePer1M" | "outputPricePer1M";

/** The optional `pricing` object: cache and long-context prices, collapsed by default. */
function AdvancedPricing({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();
  const baseId = useId();
  const pricing = task.pricing ?? {};

  const write = (next: typeof pricing) => {
    const tier = next.longContext && Object.values(next.longContext).some((v) => v != null) ? next.longContext : undefined;
    const cleaned = { ...next, longContext: tier };
    const empty = cleaned.cacheReadPricePer1M == null && cleaned.cacheWritePricePer1M == null && !tier;
    onChange({ ...task, pricing: empty ? undefined : cleaned });
  };
  const field = (f: PricingField, label: string) => (
    <div>
      <label htmlFor={`${baseId}-${f}`} className="mb-0.5 block text-[10px] text-muted-foreground">
        {label}
      </label>
      <input
        id={`${baseId}-${f}`}
        type="number"
        min={0}
        step="0.001"
        value={pricing[f] ?? ""}
        onChange={(e) => write({ ...pricing, [f]: num(e.target.value) })}
        readOnly={readOnly}
        className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
        data-testid={`task-pricing-${f}`}
      />
    </div>
  );
  const tierField = (f: TierField, label: string) => (
    <div>
      <label htmlFor={`${baseId}-lc-${f}`} className="mb-0.5 block text-[10px] text-muted-foreground">
        {label}
      </label>
      <input
        id={`${baseId}-lc-${f}`}
        type="number"
        min={0}
        step="0.001"
        value={pricing.longContext?.[f] ?? ""}
        onChange={(e) => write({ ...pricing, longContext: { ...(pricing.longContext ?? {}), [f]: num(e.target.value) } })}
        readOnly={readOnly}
        className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
        data-testid={`task-pricing-lc-${f}`}
      />
    </div>
  );

  return (
    <details className="rounded-md border border-border/60 p-2" data-testid="task-pricing-advanced" open={task.pricing != null}>
      <summary className="cursor-pointer text-[10px] font-medium text-muted-foreground">
        {t("modelPrices.advancedPricing", "Cache and long-context prices")}
      </summary>
      <div className="mt-2 grid grid-cols-2 gap-2">
        {field("cacheReadPricePer1M", t("modelPrices.cacheReadPrice", "Cache read $ / 1M"))}
        {field("cacheWritePricePer1M", t("modelPrices.cacheWritePrice", "Cache write $ / 1M"))}
        {tierField("inputPricePer1M", t("modelPrices.lcInputPrice", "Long-context input $ / 1M"))}
        {tierField("outputPricePer1M", t("modelPrices.lcOutputPrice", "Long-context output $ / 1M"))}
      </div>
      <p className="mt-1 text-[10px] leading-relaxed text-muted-foreground">
        {t(
          "modelPrices.advancedHint",
          "Blank fields come from the Model Prices catalog. A blank cache price charges cached tokens at the input price; the long-context threshold is the catalog's.",
        )}
      </p>
    </details>
  );
}
