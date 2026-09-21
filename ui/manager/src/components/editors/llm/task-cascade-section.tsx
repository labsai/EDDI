import { useRef } from "react";
import { useTranslation } from "react-i18next";
import { Layers, Plus, ArrowDown } from "lucide-react";
import { EditorSection } from "../editor-section";
import type { TaskSectionProps } from "./task-section-props";
import type { ModelCascadeConfig, CascadeStep } from "./types";
import {
  validateCascade,
  cascadeLevelIssues,
  cascadeIssuesForStep,
} from "./cascade/cascade-validation";
import { CascadeIssues } from "./cascade/cascade-issues";
import { CascadeStepCard } from "./cascade/cascade-step-card";
import { CascadeJudgeModelEditor } from "./cascade/cascade-judge-model";
import { CascadeHeuristicEditor } from "./cascade/cascade-heuristic";
import { CascadeCeilings } from "./cascade/cascade-ceilings";

/**
 * Model Cascade configuration section — sequential escalation through
 * cheap → expensive model tiers with confidence-based routing. Composes the
 * focused editors under `./cascade/` and surfaces the same validation the
 * backend applies at deploy, inline and before saving.
 */
export function TaskCascadeSection({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();
  const cascade = task.modelCascade;
  const enabled = cascade?.enabled ?? false;

  const issues = validateCascade(task);
  const levelIssues = cascadeLevelIssues(issues);

  const updateCascade = (patch: Partial<ModelCascadeConfig>) =>
    onChange({ ...task, modelCascade: { ...task.modelCascade, ...patch } });

  const steps = cascade?.steps ?? [];

  // Stable per-step keys so a card's local UI state (advanced open, secret
  // picker popup) follows the logical step across reorder/remove rather than its
  // array position. Purely client-side — never written to the saved config. The
  // handlers keep it in lockstep; the length guard resyncs on external changes.
  const stepKeys = useRef<number[]>([]);
  const keySeq = useRef(0);
  const taskIdRef = useRef(task.id);
  // Regenerate keys when the editor switches to a different task, or when the
  // step count changes — so a card's local UI state never binds to the wrong
  // step. During normal editing of one task the handlers keep the array aligned.
  if (taskIdRef.current !== task.id || stepKeys.current.length !== steps.length) {
    taskIdRef.current = task.id;
    stepKeys.current = steps.map(() => keySeq.current++);
  }

  const updateStep = (i: number, patch: Partial<CascadeStep>) => {
    const next = [...steps];
    next[i] = { ...next[i], ...patch };
    updateCascade({ steps: next });
  };
  const moveStep = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= steps.length) return;
    const next = [...steps];
    const tmp = next[i]!;
    next[i] = next[j]!;
    next[j] = tmp;
    const kt = stepKeys.current[i]!;
    stepKeys.current[i] = stepKeys.current[j]!;
    stepKeys.current[j] = kt;
    updateCascade({ steps: next });
  };
  const removeStep = (i: number) => {
    stepKeys.current.splice(i, 1);
    updateCascade({ steps: steps.filter((_, j) => j !== i) });
  };
  const addStep = () => {
    stepKeys.current.push(keySeq.current++);
    updateCascade({
      steps: [
        ...steps,
        { type: task.type ?? "openai", parameters: { model: "" }, confidenceThreshold: 0.7, timeoutMs: 30000 },
      ],
    });
  };

  const evalStrategy = cascade?.evaluationStrategy ?? "structured_output";

  const onEvalStrategyChange = (value: string) => {
    // When switching to judge_model, seed a judge with the task's provider so
    // the picker reflects reality (the user still supplies model + key).
    if (value === "judge_model" && !task.modelCascade?.judgeModel?.type) {
      updateCascade({
        evaluationStrategy: value,
        judgeModel: { type: task.type ?? "openai", parameters: {} },
      });
    } else {
      updateCascade({ evaluationStrategy: value });
    }
  };

  return (
    <EditorSection
      label={t("llmEditor.cascade", "Model Cascade")}
      icon={Layers}
      accent="text-purple-500"
      defaultOpen={enabled}
    >
      <div className="space-y-3" data-testid="cascade-section">
        <p className="text-[10px] leading-relaxed text-muted-foreground">
          {t(
            "llmEditor.cascadeDesc",
            "Try a cheap/fast model first. If confidence is too low, automatically escalate to a more powerful (and expensive) model. Saves costs without sacrificing quality.",
          )}
        </p>

        {/* Enable toggle */}
        <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) =>
              updateCascade({
                enabled: e.target.checked,
                strategy: cascade?.strategy ?? "cascade",
                evaluationStrategy: cascade?.evaluationStrategy ?? "structured_output",
                enableInAgentMode: cascade?.enableInAgentMode ?? true,
                steps: cascade?.steps ?? [],
              })
            }
            disabled={readOnly}
            className="h-3.5 w-3.5 rounded border-input accent-primary"
            data-testid="cascade-enable"
          />
          <Layers className="h-3.5 w-3.5 text-primary" />
          {t("llmEditor.cascadeEnable", "Enable Model Cascade")}
        </label>

        {enabled && cascade && (
          <div className="space-y-3 rounded-lg border border-primary/20 bg-primary/5 p-3">
            {/* Strategy + Evaluation */}
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("llmEditor.cascadeStrategy", "Strategy")}
                </label>
                <select
                  value={cascade.strategy ?? "cascade"}
                  onChange={(e) => updateCascade({ strategy: e.target.value })}
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
                  value={evalStrategy}
                  onChange={(e) => onEvalStrategyChange(e.target.value)}
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

            {/* Evaluation-strategy-specific config */}
            {evalStrategy === "judge_model" && (
              <CascadeJudgeModelEditor
                value={cascade.judgeModel}
                onChange={(v) => updateCascade({ judgeModel: v })}
                readOnly={readOnly}
              />
            )}
            {evalStrategy === "heuristic" && (
              <CascadeHeuristicEditor
                value={cascade.heuristic}
                onChange={(v) => updateCascade({ heuristic: v })}
                readOnly={readOnly}
              />
            )}
            {evalStrategy === "none" && (
              <p className="text-[10px] text-muted-foreground">
                {t("llmEditor.cascadeEvalNoneHint", "Confidence gating is disabled — the first step's response is always accepted.")}
              </p>
            )}

            {/* Toggles */}
            <div className="space-y-2">
              <label className="inline-flex items-center gap-2 text-xs text-foreground">
                <input
                  type="checkbox"
                  checked={cascade.enableInAgentMode ?? true}
                  onChange={(e) => updateCascade({ enableInAgentMode: e.target.checked })}
                  disabled={readOnly}
                  className="h-3.5 w-3.5 rounded border-input accent-primary"
                />
                {t("llmEditor.cascadeInAgent", "Also use cascade in Agent Mode (with tools)")}
              </label>
              <label className="inline-flex items-center gap-2 text-xs text-foreground">
                <input
                  type="checkbox"
                  checked={cascade.returnBestAcrossSteps ?? false}
                  onChange={(e) => updateCascade({ returnBestAcrossSteps: e.target.checked })}
                  disabled={readOnly}
                  className="h-3.5 w-3.5 rounded border-input accent-primary"
                  data-testid="cascade-return-best"
                />
                {t("llmEditor.cascadeReturnBest", "Return best-scoring response across steps")}
              </label>
            </div>

            {/* Ceilings & pricing */}
            <CascadeCeilings cascade={cascade} onChange={updateCascade} readOnly={readOnly} />

            {/* Steps */}
            <div>
              <label className="mb-1.5 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                <ArrowDown className="h-3 w-3" />
                {t("llmEditor.cascadeSteps", "Cascade Steps (cheap → expensive)")}
              </label>
              <p className="mb-2 text-[10px] text-muted-foreground">
                {t(
                  "llmEditor.cascadeStepsDesc",
                  "Order matters: first step tried first. The last step is always accepted (leave its confidence empty).",
                )}
              </p>

              <div className="space-y-2">
                {steps.map((step, si) => (
                  <CascadeStepCard
                    key={stepKeys.current[si] ?? si}
                    step={step}
                    index={si}
                    totalSteps={steps.length}
                    taskType={task.type}
                    issues={cascadeIssuesForStep(issues, si)}
                    onChange={(patch) => updateStep(si, patch)}
                    onMoveUp={() => moveStep(si, -1)}
                    onMoveDown={() => moveStep(si, 1)}
                    onRemove={() => removeStep(si)}
                    readOnly={readOnly}
                  />
                ))}
              </div>

              {!readOnly && (
                <button
                  type="button"
                  onClick={addStep}
                  className="mt-2 inline-flex items-center gap-1.5 rounded-lg border border-dashed border-primary/40 px-3 py-1.5 text-xs font-medium text-primary/70 transition-colors hover:border-primary hover:text-primary"
                  data-testid="add-cascade-step"
                >
                  <Plus className="h-3.5 w-3.5" />
                  {t("llmEditor.addCascadeStep", "Add Cascade Step")}
                </button>
              )}
            </div>

            {/* Cascade-level validation issues */}
            {levelIssues.length > 0 && <CascadeIssues issues={levelIssues} />}
          </div>
        )}
      </div>
    </EditorSection>
  );
}
