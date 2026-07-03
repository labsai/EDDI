import { describe, it, expect } from "vitest";
import {
  validateCascade,
  cascadeHasErrors,
  type CascadeIssue,
} from "../cascade-validation";
import type { LlmTask, ModelCascadeConfig } from "../../types";

/** Build a task with an enabled cascade and a task-level apiKey (so cross-provider checks fire). */
function mk(cascade: Partial<ModelCascadeConfig>, taskType = "openai"): LlmTask {
  return {
    type: taskType,
    parameters: { apiKey: "${vault:openai-key}" },
    modelCascade: { enabled: true, ...cascade },
  } as LlmTask;
}

const codes = (issues: CascadeIssue[]) => issues.map((i) => i.code);

describe("validateCascade", () => {
  it("returns no issues when cascade is absent or disabled", () => {
    expect(validateCascade({ type: "openai" } as LlmTask)).toEqual([]);
    expect(validateCascade(mk({ enabled: false, steps: [] }))).toEqual([]);
  });

  it("returns no issues for a well-formed two-step cascade", () => {
    const issues = validateCascade(
      mk({
        steps: [
          { type: "openai", parameters: { model: "gpt-4o-mini" }, confidenceThreshold: 0.7, timeoutMs: 10000 },
          { type: "openai", parameters: { model: "gpt-4o" }, confidenceThreshold: null, timeoutMs: 30000 },
        ],
      }),
    );
    expect(issues).toEqual([]);
  });

  it("warns when enabled with no steps", () => {
    expect(codes(validateCascade(mk({ steps: [] })))).toContain("NO_STEPS");
    expect(codes(validateCascade(mk({})))).toContain("NO_STEPS");
  });

  // ── Hard errors ────────────────────────────────────────────────────────────
  it("errors on non-positive maxTotalDurationMs", () => {
    const issues = validateCascade(mk({ maxTotalDurationMs: 0, steps: [{ confidenceThreshold: null }] }));
    const issue = issues.find((i) => i.code === "MAX_DURATION_NONPOSITIVE");
    expect(issue?.level).toBe("error");
    expect(codes(validateCascade(mk({ maxTotalDurationMs: 5000, steps: [{ confidenceThreshold: null }] })))).not.toContain(
      "MAX_DURATION_NONPOSITIVE",
    );
  });

  it("errors on negative maxCostPerRun but allows zero", () => {
    expect(codes(validateCascade(mk({ maxCostPerRun: -1, steps: [{ confidenceThreshold: null }] })))).toContain(
      "MAX_COST_NEGATIVE",
    );
    expect(codes(validateCascade(mk({ maxCostPerRun: 0, steps: [{ confidenceThreshold: null }] })))).not.toContain(
      "MAX_COST_NEGATIVE",
    );
  });

  it("errors on negative cascade-level pricing", () => {
    const issues = validateCascade(mk({ inputPricePer1M: -0.1, outputPricePer1M: -1, steps: [{ confidenceThreshold: null }] }));
    expect(codes(issues)).toContain("CASCADE_INPUT_PRICE_NEGATIVE");
    expect(codes(issues)).toContain("CASCADE_OUTPUT_PRICE_NEGATIVE");
    expect(cascadeHasErrors(issues)).toBe(true);
  });

  it("errors on negative per-step pricing and tags the step index", () => {
    const issues = validateCascade(
      mk({
        steps: [
          { confidenceThreshold: 0.7, inputPricePer1M: -1 },
          { confidenceThreshold: null, outputPricePer1M: -2 },
        ],
      }),
    );
    const inErr = issues.find((i) => i.code === "STEP_INPUT_PRICE_NEGATIVE");
    const outErr = issues.find((i) => i.code === "STEP_OUTPUT_PRICE_NEGATIVE");
    expect(inErr?.stepIndex).toBe(0);
    expect(outErr?.stepIndex).toBe(1);
    expect(inErr?.level).toBe("error");
  });

  // ── Warnings ─────────────────────────────────────────────────────────────
  it("warns when judge_model has no judge configured, but not when it does", () => {
    expect(codes(validateCascade(mk({ evaluationStrategy: "judge_model", steps: [{ confidenceThreshold: null }] })))).toContain(
      "JUDGE_MODEL_MISSING",
    );
    expect(
      codes(
        validateCascade(
          mk({
            evaluationStrategy: "judge_model",
            judgeModel: { type: "openai", parameters: { model: "gpt-4o-mini", apiKey: "${vault:openai-key}" } },
            steps: [{ confidenceThreshold: null }],
          }),
        ),
      ),
    ).not.toContain("JUDGE_MODEL_MISSING");
  });

  it("warns when a cross-provider step omits its own apiKey", () => {
    const issues = validateCascade(
      mk({
        steps: [
          { type: "anthropic", parameters: { model: "claude-sonnet-4" }, confidenceThreshold: 0.7 },
          { type: "openai", parameters: { model: "gpt-4o" }, confidenceThreshold: null },
        ],
      }),
    );
    const issue = issues.find((i) => i.code === "STEP_CROSS_PROVIDER_NO_APIKEY");
    expect(issue?.stepIndex).toBe(0);
    expect(issue?.level).toBe("warning");
  });

  it("does not warn cross-provider when the step supplies its own apiKey", () => {
    const issues = validateCascade(
      mk({
        steps: [
          { type: "anthropic", parameters: { model: "claude-sonnet-4", apiKey: "${vault:anthropic-key}" }, confidenceThreshold: 0.7 },
          { type: "openai", confidenceThreshold: null },
        ],
      }),
    );
    expect(codes(issues)).not.toContain("STEP_CROSS_PROVIDER_NO_APIKEY");
  });

  it("does not warn cross-provider for same-provider steps", () => {
    const issues = validateCascade(
      mk({
        steps: [
          { type: "openai", confidenceThreshold: 0.7 },
          { type: "openai", confidenceThreshold: null },
        ],
      }),
    );
    expect(codes(issues)).not.toContain("STEP_CROSS_PROVIDER_NO_APIKEY");
  });

  it("stops at NO_STEPS and skips later hard-error checks (mirrors the backend early return)", () => {
    const c = codes(validateCascade(mk({ maxCostPerRun: -1, inputPricePer1M: -5, steps: [] })));
    expect(c).toEqual(["NO_STEPS"]);
    expect(c).not.toContain("MAX_COST_NEGATIVE");
  });

  it("treats a present-but-empty apiKey as 'has key' (containsKey semantics)", () => {
    const issues = validateCascade(
      mk({
        steps: [
          { type: "anthropic", parameters: { model: "claude", apiKey: "" }, confidenceThreshold: 0.7 },
          { type: "openai", confidenceThreshold: null },
        ],
      }),
    );
    expect(codes(issues)).not.toContain("STEP_CROSS_PROVIDER_NO_APIKEY");
  });

  it("warns when a cross-provider apiKey uses non-canonical casing (exact-case match)", () => {
    const issues = validateCascade(
      mk({
        steps: [
          { type: "anthropic", parameters: { model: "claude", apikey: "x" }, confidenceThreshold: 0.7 },
          { type: "openai", confidenceThreshold: null },
        ],
      }),
    );
    expect(codes(issues)).toContain("STEP_CROSS_PROVIDER_NO_APIKEY");
  });

  it("warns on a non-last step with a null threshold (dead-step trap) but allows it on the last step", () => {
    const issues = validateCascade(
      mk({
        steps: [
          { type: "openai", confidenceThreshold: null }, // dead step
          { type: "openai", confidenceThreshold: null }, // last — fine
        ],
      }),
    );
    const dead = issues.filter((i) => i.code === "STEP_DEAD_NULL_THRESHOLD");
    expect(dead).toHaveLength(1);
    expect(dead[0]!.stepIndex).toBe(0);
  });

  it("warns on confidence threshold outside [0,1]", () => {
    const issues = validateCascade(
      mk({ steps: [{ confidenceThreshold: 1.5 }, { confidenceThreshold: null }] }),
    );
    expect(issues.find((i) => i.code === "STEP_CONFIDENCE_OUT_OF_RANGE")?.stepIndex).toBe(0);
  });

  it("warns on non-positive step timeout", () => {
    const issues = validateCascade(
      mk({ steps: [{ confidenceThreshold: 0.7, timeoutMs: 0 }, { confidenceThreshold: null }] }),
    );
    expect(codes(issues)).toContain("STEP_TIMEOUT_NONPOSITIVE");
  });

  it("warns on parallel and unknown strategies", () => {
    expect(codes(validateCascade(mk({ strategy: "parallel", steps: [{ confidenceThreshold: null }] })))).toContain(
      "PARALLEL_NOT_IMPLEMENTED",
    );
    expect(codes(validateCascade(mk({ strategy: "banana", steps: [{ confidenceThreshold: null }] })))).toContain(
      "UNKNOWN_STRATEGY",
    );
  });

  it("warns on an unknown evaluation strategy", () => {
    expect(codes(validateCascade(mk({ evaluationStrategy: "vibes", steps: [{ confidenceThreshold: null }] })))).toContain(
      "UNKNOWN_EVAL_STRATEGY",
    );
  });

  it("cascadeHasErrors is true only when an error-level issue is present", () => {
    const warnOnly = validateCascade(mk({ strategy: "parallel", steps: [{ confidenceThreshold: null }] }));
    expect(cascadeHasErrors(warnOnly)).toBe(false);
    const withErr = validateCascade(mk({ maxCostPerRun: -1, steps: [{ confidenceThreshold: null }] }));
    expect(cascadeHasErrors(withErr)).toBe(true);
  });
});
