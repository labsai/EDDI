/**
 * Client-side validation for the model-cascade config, mirroring the backend
 * `CascadeConfigValidator` (deploy-time checks) so the editor can surface the
 * same errors and warnings *before* the user saves.
 *
 * Errors correspond to conditions the backend rejects at deploy (deployment
 * fails); warnings correspond to conditions the backend tolerates but that
 * degrade behaviour at runtime.
 *
 * Kept pure and framework-free (no i18n) so it is trivially unit-testable. Each
 * issue carries a stable `code` (the UI maps it to a translated message, falling
 * back to the English `message`) plus interpolation `params`.
 */
import type { LlmTask } from "../types";

export const CASCADE_STRATEGIES = ["cascade", "parallel"] as const;
export const CASCADE_EVAL_STRATEGIES = [
  "structured_output",
  "heuristic",
  "judge_model",
  "none",
] as const;

export type CascadeIssueLevel = "error" | "warning";

export interface CascadeIssue {
  level: CascadeIssueLevel;
  /** Stable identifier; the UI maps it to a translated message. */
  code: string;
  /** Default English message (used as the i18n fallback). */
  message: string;
  /** Present for step-scoped issues (0-based). */
  stepIndex?: number;
  /** Interpolation params for the translated message. */
  params?: Record<string, string | number>;
}

/** True if a parameters map carries a non-blank apiKey (case-insensitive key match). */
function hasApiKey(parameters?: Record<string, string>): boolean {
  if (!parameters) return false;
  return Object.entries(parameters).some(
    ([k, v]) =>
      k.trim().toLowerCase() === "apikey" &&
      typeof v === "string" &&
      v.trim() !== "",
  );
}

function norm(s?: string): string {
  return (s ?? "").trim().toLowerCase();
}

/**
 * Validate a task's model cascade. Returns an empty list when the cascade is
 * absent or disabled.
 */
export function validateCascade(task: LlmTask): CascadeIssue[] {
  const cascade = task.modelCascade;
  if (!cascade?.enabled) return [];

  const issues: CascadeIssue[] = [];
  const steps = cascade.steps ?? [];

  // ── Cascade-level ──────────────────────────────────────────────────────────
  if (steps.length === 0) {
    issues.push({
      level: "warning",
      code: "NO_STEPS",
      message: "Cascade is enabled but has no steps — it will fail when it runs.",
    });
  }

  const strategy = norm(cascade.strategy);
  if (
    strategy &&
    !CASCADE_STRATEGIES.includes(strategy as (typeof CASCADE_STRATEGIES)[number])
  ) {
    issues.push({
      level: "warning",
      code: "UNKNOWN_STRATEGY",
      message: `Unknown strategy "${cascade.strategy}" — the cascade will run sequentially.`,
      params: { strategy: cascade.strategy ?? "" },
    });
  } else if (strategy === "parallel") {
    issues.push({
      level: "warning",
      code: "PARALLEL_NOT_IMPLEMENTED",
      message: "Parallel strategy is not implemented yet — steps run sequentially.",
    });
  }

  const evalStrategy = norm(cascade.evaluationStrategy);
  if (
    evalStrategy &&
    !CASCADE_EVAL_STRATEGIES.includes(
      evalStrategy as (typeof CASCADE_EVAL_STRATEGIES)[number],
    )
  ) {
    issues.push({
      level: "warning",
      code: "UNKNOWN_EVAL_STRATEGY",
      message: `Unknown confidence evaluation "${cascade.evaluationStrategy}" — defaulting to Structured Output.`,
      params: { strategy: cascade.evaluationStrategy ?? "" },
    });
  }

  // Effective strategy — the backend default is structured_output.
  const effectiveEval = evalStrategy || "structured_output";

  if (effectiveEval === "judge_model" && !norm(cascade.judgeModel?.type)) {
    issues.push({
      level: "warning",
      code: "JUDGE_MODEL_MISSING",
      message:
        "Judge Model evaluation has no judge configured — confidence falls back to heuristic.",
    });
  }

  // Judge model cross-provider credentials.
  const judgeType = norm(cascade.judgeModel?.type);
  if (
    judgeType &&
    judgeType !== norm(task.type) &&
    hasApiKey(task.parameters) &&
    !hasApiKey(cascade.judgeModel?.parameters)
  ) {
    issues.push({
      level: "warning",
      code: "JUDGE_CROSS_PROVIDER_NO_APIKEY",
      message:
        "The judge model uses a different provider but has no API key of its own — it would inherit the task key.",
    });
  }

  if (cascade.maxTotalDurationMs != null && cascade.maxTotalDurationMs <= 0) {
    issues.push({
      level: "error",
      code: "MAX_DURATION_NONPOSITIVE",
      message: "Max total duration must be greater than 0.",
    });
  }
  if (cascade.maxCostPerRun != null && cascade.maxCostPerRun < 0) {
    issues.push({
      level: "error",
      code: "MAX_COST_NEGATIVE",
      message: "Max cost per run cannot be negative.",
    });
  }
  if (cascade.inputPricePer1M != null && cascade.inputPricePer1M < 0) {
    issues.push({
      level: "error",
      code: "CASCADE_INPUT_PRICE_NEGATIVE",
      message: "Cascade input price cannot be negative.",
    });
  }
  if (cascade.outputPricePer1M != null && cascade.outputPricePer1M < 0) {
    issues.push({
      level: "error",
      code: "CASCADE_OUTPUT_PRICE_NEGATIVE",
      message: "Cascade output price cannot be negative.",
    });
  }

  // convertToObject collides with the structured_output confidence wrapper.
  const convertToObject = norm(task.parameters?.convertToObject) === "true";
  if (convertToObject && effectiveEval === "structured_output") {
    issues.push({
      level: "warning",
      code: "CONVERT_TO_OBJECT_STRUCTURED",
      message:
        "convertToObject is incompatible with Structured Output confidence — the cascade will use heuristic instead.",
    });
  }

  // ── Per-step ───────────────────────────────────────────────────────────────
  steps.forEach((step, i) => {
    const isLast = i === steps.length - 1;
    const params = { step: i + 1 };

    if (step.inputPricePer1M != null && step.inputPricePer1M < 0) {
      issues.push({
        level: "error",
        code: "STEP_INPUT_PRICE_NEGATIVE",
        message: `Step ${i + 1} input price cannot be negative.`,
        stepIndex: i,
        params,
      });
    }
    if (step.outputPricePer1M != null && step.outputPricePer1M < 0) {
      issues.push({
        level: "error",
        code: "STEP_OUTPUT_PRICE_NEGATIVE",
        message: `Step ${i + 1} output price cannot be negative.`,
        stepIndex: i,
        params,
      });
    }
    if (
      step.confidenceThreshold != null &&
      (step.confidenceThreshold < 0 || step.confidenceThreshold > 1)
    ) {
      issues.push({
        level: "warning",
        code: "STEP_CONFIDENCE_OUT_OF_RANGE",
        message: `Step ${i + 1} confidence must be between 0 and 1.`,
        stepIndex: i,
        params,
      });
    }
    if (!isLast && step.confidenceThreshold == null) {
      issues.push({
        level: "warning",
        code: "STEP_DEAD_NULL_THRESHOLD",
        message: `Step ${i + 1} has no confidence threshold — later steps become unreachable.`,
        stepIndex: i,
        params,
      });
    }
    if (step.timeoutMs != null && step.timeoutMs <= 0) {
      issues.push({
        level: "warning",
        code: "STEP_TIMEOUT_NONPOSITIVE",
        message: `Step ${i + 1} timeout must be greater than 0.`,
        stepIndex: i,
        params,
      });
    }

    // Cross-provider credentials: step provider differs from the task and the
    // step supplies no key of its own (it would silently inherit the task key).
    const stepType = norm(step.type);
    if (
      stepType &&
      stepType !== norm(task.type) &&
      hasApiKey(task.parameters) &&
      !hasApiKey(step.parameters)
    ) {
      issues.push({
        level: "warning",
        code: "STEP_CROSS_PROVIDER_NO_APIKEY",
        message: `Step ${i + 1} uses a different provider but has no API key of its own — it would inherit the task key.`,
        stepIndex: i,
        params,
      });
    }
  });

  return issues;
}

/** True when any issue is error-level (the backend would reject the deploy). */
export function cascadeHasErrors(issues: CascadeIssue[]): boolean {
  return issues.some((i) => i.level === "error");
}

/** Issues scoped to a specific step. */
export function cascadeIssuesForStep(
  issues: CascadeIssue[],
  stepIndex: number,
): CascadeIssue[] {
  return issues.filter((i) => i.stepIndex === stepIndex);
}

/** Cascade-level (non-step) issues. */
export function cascadeLevelIssues(issues: CascadeIssue[]): CascadeIssue[] {
  return issues.filter((i) => i.stepIndex === undefined);
}
