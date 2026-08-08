import { useId } from "react";
import { useTranslation } from "react-i18next";
import { DollarSign } from "lucide-react";
import { EditorSection } from "../editor-section";
import type { TaskSectionProps } from "./task-section-props";
import { parseNum as num } from "./cascade/cascade-utils";

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
      </div>
    </EditorSection>
  );
}
