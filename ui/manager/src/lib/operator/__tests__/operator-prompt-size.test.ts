import { describe, expect, it } from "vitest";
import { SETUP_MAX_SYSTEM_PROMPT_LENGTH } from "@/lib/api/agent-setup";
import { PRODUCT_NAME_MAX } from "@/lib/brand-validation";
import { buildOperatorSystemPrompt, defaultOperatorPromptBody } from "../system-prompt";
import type { OperatorScope } from "../tool-scopes";

/**
 * The Operator is provisioned (and upgraded) through `setup-api`, whose
 * `systemPrompt` is capped by the backend. 6.7.0 shipped a prompt of ~110,000
 * characters against a cap of 100,000: every activation and every upgrade got
 * "System prompt is too long (HTTP 400)", and no test noticed because every
 * Operator test runs against a mocked backend.
 *
 * This test sends nothing; it measures the exact string `provisionOperator`
 * sends, for every scope and the longest brand name the validator allows, and
 * holds it to 80% of the cap. The headroom is deliberate: the prompt grows with
 * every release, and running into the guard on a feature branch is cheap where
 * running into the cap after a release is not. When this fails, shorten the
 * prompt, or raise the cap in BOTH places (`AgentSetupService.MAX_SYSTEM_PROMPT_LENGTH`
 * and `SETUP_MAX_SYSTEM_PROMPT_LENGTH`) — never just this budget.
 */
const SCOPES: readonly OperatorScope[] = ["read_only", "read_write"];
const BUDGET = Math.floor(SETUP_MAX_SYSTEM_PROMPT_LENGTH * 0.8);
const LONGEST_BRAND = "B".repeat(PRODUCT_NAME_MAX);

describe("Operator prompt size vs the setup-api cap", () => {
  it.each(SCOPES)("the %s prompt stays within the budget (80 percent of the backend cap)", (scope) => {
    const prompt = buildOperatorSystemPrompt(defaultOperatorPromptBody(scope), scope, LONGEST_BRAND);

    expect(
      prompt.length,
      `The ${scope} Operator prompt is ${prompt.length} characters; the budget is ${BUDGET} ` +
        `(80% of the setup-api cap of ${SETUP_MAX_SYSTEM_PROMPT_LENGTH}). See this file's header.`,
    ).toBeLessThanOrEqual(BUDGET);
  });

  it("measures a real prompt, not an empty one", () => {
    // Guards the guard: a refactor that made the builder return "" would pass
    // the budget check vacuously.
    for (const scope of SCOPES) {
      const prompt = buildOperatorSystemPrompt(defaultOperatorPromptBody(scope), scope, LONGEST_BRAND);
      expect(prompt.length).toBeGreaterThan(10_000);
    }
  });
});
