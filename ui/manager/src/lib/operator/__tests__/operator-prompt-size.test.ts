import { describe, expect, it } from "vitest";
import { SETUP_MAX_SYSTEM_PROMPT_LENGTH } from "@eddi/app-kit/lib/api/agent-setup";
import { PRODUCT_NAME_MAX } from "@eddi/app-kit/lib/brand-validation";
import { buildOperatorSystemPrompt, defaultOperatorPromptBody } from "../system-prompt";
import type { OperatorScope } from "../tool-scopes";

/**
 * The Operator is provisioned (and upgraded) through `setup-api`, whose
 * `systemPrompt` is capped by the backend. 6.7.0 shipped a prompt of ~110,000
 * characters against a cap of 100,000: every activation and every upgrade got
 * "System prompt is too long (HTTP 400)", and no unit test noticed because every
 * Operator unit test runs against a mocked backend.
 *
 * This test sends nothing; it measures the exact string `provisionOperator`
 * sends, for every scope (`read_write` grants every allow-listed endpoint, so it
 * carries every optional section) and the longest brand name the validator
 * allows, and holds it to 80% of the cap. The headroom is deliberate: the prompt
 * grows with every release, and running into the guard on a feature branch is
 * cheap where running into the cap after a release is not. When this fails,
 * shorten the prompt, or raise the cap in BOTH places
 * (`AgentSetupService.MAX_SYSTEM_PROMPT_LENGTH` and
 * `SETUP_MAX_SYSTEM_PROMPT_LENGTH`; `SetupPromptCapMirrorTest` checks they
 * agree) — never just this budget.
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

/**
 * The cap above only says whether setup-api ACCEPTS the prompt. This budget is
 * about what it COSTS: the whole prompt is resent on every round of the
 * operator's tool loop (up to 100 per turn), next to roughly as many characters
 * again of generated tool definitions. The source grew from 42 KB to 169 KB in
 * five weeks of 2026, a paragraph per feature, with nothing pushing back but a
 * cap at 200,000 characters; a trim to below this budget would have grown back
 * the same way.
 *
 * When this fails, do not raise the number first. Move detail out instead:
 * the prompt holds what the operator must not do and where to send the admin,
 * the symptoms it should recognise with their usual cause, and which docs page
 * to read; field lists, limits, defaults and mechanism belong on a docs page
 * (a small dedicated one — the operator reads a page whole), with a one-line
 * pointer here. See "Platform Operator" in ui/manager/AGENTS.md. Raising the
 * budget is a decision to make on purpose, with the reason in the commit.
 */
const COST_BUDGET_CHARS = 125_000;

describe("Operator prompt size vs its cost budget", () => {
  it.each(SCOPES)("the %s prompt stays within the cost budget", (scope) => {
    const prompt = buildOperatorSystemPrompt(defaultOperatorPromptBody(scope), scope, LONGEST_BRAND);

    expect(
      prompt.length,
      `The ${scope} Operator prompt is ${prompt.length} characters; its cost budget is ${COST_BUDGET_CHARS}. ` +
        `Move detail to a docs page and leave a pointer before raising it — see this block's comment.`,
    ).toBeLessThanOrEqual(COST_BUDGET_CHARS);
  });

  it("is well below the setup-api acceptance budget, or it would guard nothing new", () => {
    expect(COST_BUDGET_CHARS).toBeLessThan(BUDGET * 0.75);
  });
});
