import { useTranslation } from "react-i18next";
import { Layers, Plus, Trash2, ArrowUp, ArrowDown, Info } from "lucide-react";
import { EditorSection } from "../editor-section";
import { MODEL_TYPES } from "./types";
import type { TaskSectionProps } from "./task-section-props";

/**
 * Model Cascade configuration section.
 * Allows configuring sequential escalation through cheap→expensive model tiers.
 */
export function TaskCascadeSection({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();

  return (
    <EditorSection
      label={t("llmEditor.cascade", "Model Cascade")}
      icon={Layers}
      accent="text-purple-500"
      defaultOpen={!!(task.modelCascade?.enabled)}
    >
      <div className="space-y-3" data-testid="cascade-section">
        {/* Explain what cascade does */}
        <p className="text-[10px] text-muted-foreground leading-relaxed">
          {t("llmEditor.cascadeDesc", "Try a cheap/fast model first. If confidence is too low, automatically escalate to a more powerful (and expensive) model. Saves costs without sacrificing quality.")}
        </p>

        {/* Enable toggle */}
        <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
          <input
            type="checkbox"
            checked={task.modelCascade?.enabled ?? false}
            onChange={(e) =>
              onChange({
                ...task,
                modelCascade: {
                  ...task.modelCascade,
                  enabled: e.target.checked,
                  strategy: task.modelCascade?.strategy ?? "cascade",
                  evaluationStrategy: task.modelCascade?.evaluationStrategy ?? "structured_output",
                  enableInAgentMode: task.modelCascade?.enableInAgentMode ?? true,
                  steps: task.modelCascade?.steps ?? [],
                },
              })
            }
            disabled={readOnly}
            className="h-3.5 w-3.5 rounded border-input accent-primary"
            data-testid="cascade-enable"
          />
          <Layers className="h-3.5 w-3.5 text-primary" />
          {t("llmEditor.cascadeEnable", "Enable Model Cascade")}
        </label>

        {task.modelCascade?.enabled && (
          <div className="space-y-3 rounded-lg border border-primary/20 bg-primary/5 p-3">
            {/* Strategy + Evaluation */}
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("llmEditor.cascadeStrategy", "Strategy")}
                </label>
                <select
                  value={task.modelCascade.strategy ?? "cascade"}
                  onChange={(e) =>
                    onChange({
                      ...task,
                      modelCascade: { ...task.modelCascade!, strategy: e.target.value },
                    })
                  }
                  disabled={readOnly}
                  className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                >
                  <option value="cascade">{t("llmEditor.strategyCascade", "Sequential Escalation")}</option>
                  <option value="parallel">{t("llmEditor.strategyParallel", "Parallel (future)")}</option>
                </select>
                <p className="mt-0.5 text-[10px] text-muted-foreground">
                  {t("llmEditor.cascadeStrategyHint", "Sequential tries cheap first, escalates on low confidence")}
                </p>
              </div>
              <div>
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("llmEditor.cascadeEvalStrategy", "Confidence Evaluation")}
                </label>
                <select
                  value={task.modelCascade.evaluationStrategy ?? "structured_output"}
                  onChange={(e) =>
                    onChange({
                      ...task,
                      modelCascade: { ...task.modelCascade!, evaluationStrategy: e.target.value },
                    })
                  }
                  disabled={readOnly}
                  className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                >
                  <option value="structured_output">{t("llmEditor.evalStructured", "Structured Output (JSON)")}</option>
                  <option value="heuristic">{t("llmEditor.evalHeuristic", "Heuristic (hedging detection)")}</option>
                  <option value="judge_model">{t("llmEditor.evalJudge", "Judge Model (secondary LLM)")}</option>
                  <option value="none">{t("llmEditor.evalNone", "None (always accept)")}</option>
                </select>
                <p className="mt-0.5 text-[10px] text-muted-foreground">
                  {t("llmEditor.cascadeEvalHint", "How to determine if a response is good enough")}
                </p>
              </div>
            </div>

            {/* Enable in agent mode */}
            <label className="inline-flex items-center gap-2 text-xs text-foreground">
              <input
                type="checkbox"
                checked={task.modelCascade.enableInAgentMode ?? true}
                onChange={(e) =>
                  onChange({
                    ...task,
                    modelCascade: { ...task.modelCascade!, enableInAgentMode: e.target.checked },
                  })
                }
                disabled={readOnly}
                className="h-3.5 w-3.5 rounded border-input accent-primary"
              />
              {t("llmEditor.cascadeInAgent", "Also use cascade in Agent Mode (with tools)")}
            </label>

            {/* Steps */}
            <div>
              <label className="mb-1.5 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                <ArrowDown className="h-3 w-3" />
                {t("llmEditor.cascadeSteps", "Cascade Steps (cheap → expensive)")}
              </label>
              <p className="mb-2 text-[10px] text-muted-foreground">
                {t("llmEditor.cascadeStepsDesc", "Order matters: first step tried first. Last step is always accepted (set confidence to empty).")}
              </p>

              <div className="space-y-2">
                {(task.modelCascade.steps ?? []).map((step, si) => (
                  <div
                    key={si}
                    className="rounded-lg border border-border bg-card p-3 space-y-2"
                    data-testid={`cascade-step-${si}`}
                  >
                    <div className="flex items-center gap-2">
                      <span className="inline-flex h-5 w-5 items-center justify-center rounded-full bg-primary/10 text-[10px] font-bold text-primary">
                        {si + 1}
                      </span>
                      <select
                        value={step.type ?? "openai"}
                        onChange={(e) => {
                          const steps = [...(task.modelCascade!.steps ?? [])];
                          steps[si] = { ...step, type: e.target.value };
                          onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                        }}
                        disabled={readOnly}
                        className="h-7 rounded-md border border-input bg-background px-2 text-xs font-semibold text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                      >
                        {MODEL_TYPES.map((mt) => (
                          <option key={mt} value={mt}>{mt}</option>
                        ))}
                      </select>
                      <input
                        type="text"
                        value={step.parameters?.model ?? ""}
                        onChange={(e) => {
                          const steps = [...(task.modelCascade!.steps ?? [])];
                          steps[si] = { ...step, parameters: { ...(step.parameters ?? {}), model: e.target.value } };
                          onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                        }}
                        readOnly={readOnly}
                        placeholder={t("llmEditor.cascadeModelName", "e.g. gpt-5.4-mini")}
                        className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                      />
                      {!readOnly && (
                        <div className="flex items-center gap-0.5">
                          <button
                            type="button"
                            disabled={si === 0}
                            onClick={() => {
                              const steps = [...(task.modelCascade!.steps ?? [])];
                              const temp = steps[si];
                              steps[si] = steps[si - 1]!;
                              steps[si - 1] = temp!;
                              onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                            }}
                            className="rounded p-1 text-muted-foreground hover:text-foreground transition-colors disabled:opacity-30"
                            title={t("llmEditor.moveUp", "Move up")}
                          >
                            <ArrowUp className="h-3.5 w-3.5" />
                          </button>
                          <button
                            type="button"
                            disabled={si === (task.modelCascade!.steps ?? []).length - 1}
                            onClick={() => {
                              const steps = [...(task.modelCascade!.steps ?? [])];
                              const temp = steps[si];
                              steps[si] = steps[si + 1]!;
                              steps[si + 1] = temp!;
                              onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                            }}
                            className="rounded p-1 text-muted-foreground hover:text-foreground transition-colors disabled:opacity-30"
                            title={t("llmEditor.moveDown", "Move down")}
                          >
                            <ArrowDown className="h-3.5 w-3.5" />
                          </button>
                          <button
                            type="button"
                            onClick={() => {
                              const steps = (task.modelCascade!.steps ?? []).filter((_, j) => j !== si);
                              onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                            }}
                            className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </button>
                        </div>
                      )}
                    </div>
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
                            const steps = [...(task.modelCascade!.steps ?? [])];
                            const val = e.target.value;
                            steps[si] = {
                              ...step,
                              confidenceThreshold: val === "" ? null : (isNaN(parseFloat(val)) ? 0 : parseFloat(val)),
                            };
                            onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
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
                          value={step.timeoutMs ?? 30000}
                          onChange={(e) => {
                            const steps = [...(task.modelCascade!.steps ?? [])];
                            steps[si] = { ...step, timeoutMs: parseInt(e.target.value, 10) || 30000 };
                            onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                          }}
                          readOnly={readOnly}
                          className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                      </div>
                    </div>
                    {/* API key resolution hint */}
                    <div className="flex items-start gap-1.5 ps-7 mt-1">
                      <Info className="h-3 w-3 text-muted-foreground/60 shrink-0 mt-0.5" />
                      <p className="text-[10px] text-muted-foreground/70 leading-relaxed">
                        {t("llmEditor.cascadeApiKeyHint", "API key is inherited from the parent task and resolved from the Secrets Vault. Ensure a matching key is configured for this provider.")}
                      </p>
                    </div>
                  </div>
                ))}
              </div>

              {!readOnly && (
                <button
                  type="button"
                  onClick={() => {
                    const steps = [...(task.modelCascade?.steps ?? []), { type: "openai", parameters: { model: "" }, confidenceThreshold: 0.7, timeoutMs: 30000 }];
                    onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                  }}
                  className="mt-2 inline-flex items-center gap-1.5 rounded-lg border border-dashed border-primary/40 px-3 py-1.5 text-xs font-medium text-primary/70 transition-colors hover:border-primary hover:text-primary"
                  data-testid="add-cascade-step"
                >
                  <Plus className="h-3.5 w-3.5" />
                  {t("llmEditor.addCascadeStep", "Add Cascade Step")}
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </EditorSection>
  );
}
