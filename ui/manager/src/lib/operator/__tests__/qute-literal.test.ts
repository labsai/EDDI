import { describe, expect, it } from "vitest";
import { PRODUCT_NAME_MAX } from "@/lib/brand-validation";
import corpus from "./qute-literal-corpus.json";
import { escapeQuteLiterals, findQuteExpressions } from "../qute-literal";
import { buildOperatorSystemPrompt, defaultOperatorPromptBody } from "../system-prompt";
import type { OperatorScope } from "../tool-scopes";

/**
 * The Operator's system prompt is rendered as a Qute template by EDDI on every
 * turn, and it is full of text that looks like a Qute expression
 * (`eddi_turn_rejected_total{reason}`, `/agents/{agentId}/profile`, `{input}`).
 * 6.7.0 shipped one that did not parse — every turn logged a render ERROR and
 * the model got the raw prompt — and a render that DOES parse still drops the
 * span silently. See `qute-literal.ts`.
 *
 * Two halves, on purpose:
 *
 * 1. The corpus. `qute-literal-corpus.json` is checked here against this
 *    module's rule AND, in `OperatorPromptQuteCorpusTest`, against EDDI's real
 *    Qute engine. That test is the reason this one can trust the rule without
 *    shipping a template engine to the browser.
 * 2. The real prompts. Every prompt the Manager can send, for both scopes and
 *    the longest brand name, is held to "nothing Qute would evaluate except the
 *    Manager's own context block". A prompt section added tomorrow by any of
 *    the open branches that edit `system-prompt.ts` is covered without anyone
 *    remembering to escape it.
 */

type Kind = "literal" | "expression" | "mention" | "live";
const cases = corpus.cases as { name: string; text: string; kind: Kind; escaped: string }[];

describe("qute-literal corpus", () => {
  it.each(cases)("$name: escapes to the recorded form", ({ text, escaped }) => {
    expect(escapeQuteLiterals(text)).toBe(escaped);
  });

  it.each(cases)("$name: is classified as the engine treats it", ({ text, kind }) => {
    // `expression` is the only kind the engine evaluates AND this module reports.
    expect(findQuteExpressions(text).length > 0).toBe(kind === "expression");
  });

  it("is idempotent", () => {
    for (const { text } of cases) {
      const once = escapeQuteLiterals(text);
      expect(escapeQuteLiterals(once)).toBe(once);
    }
  });

  it("covers every kind, so a rule cannot quietly lose its case", () => {
    expect(new Set(cases.map((c) => c.kind))).toEqual(new Set(["literal", "expression", "mention", "live"]));
  });
});

describe("the operator system prompt", () => {
  const SCOPES: readonly OperatorScope[] = ["read_only", "read_write"];
  const LONGEST_BRAND = "B".repeat(PRODUCT_NAME_MAX);

  it.each(SCOPES)("%s: the BODY holds text the engine would evaluate, so the escape is doing work", (scope) => {
    // Guards the guard. If the documentation stopped containing any, the check
    // below would pass vacuously and a later section would be unprotected.
    expect(findQuteExpressions(defaultOperatorPromptBody(scope)).length).toBeGreaterThan(5);
  });

  it.each(SCOPES)("%s: nothing is left for Qute to evaluate or drop, except the context block", (scope) => {
    for (const brand of [undefined, LONGEST_BRAND]) {
      const prompt = buildOperatorSystemPrompt(defaultOperatorPromptBody(scope), scope, brand);
      expect(findQuteExpressions(prompt)).toEqual([]);
    }
  });

  it.each(SCOPES)("%s: still delivers the per-turn context block live", (scope) => {
    const prompt = buildOperatorSystemPrompt(defaultOperatorPromptBody(scope), scope);
    expect(prompt).toContain("{#if context.screen}");
    expect(prompt).toContain("{context.agentId}");
    expect(prompt).not.toContain("{|{#if");
    expect(prompt).not.toContain("{|{context.");
  });

  it.each(SCOPES)("%s: documentation braces survive as written once rendered", (scope) => {
    const body = defaultOperatorPromptBody(scope);
    // What the model should read for each span: the span itself. Unwrapping the
    // blocks is what the engine does, so the escaped prompt minus its markers
    // must be the body, apart from the context block the engine fills in.
    const prompt = buildOperatorSystemPrompt(body, scope);
    const unwrapped = prompt.replace(/\{\|([\s\S]*?)\|\}/g, "$1");
    for (const span of findQuteExpressions(body)) expect(unwrapped).toContain(span);
  });

  it("an edited body is literal text: template namespaces are NOT evaluated, only the context block is", () => {
    // Pins a decision. Nothing documents or suggests template expressions in the operator
    // instructions (the editor hint says the text is sent as written), so every namespace
    // expression an admin types is escaped rather than silently evaluated to nothing.
    const body = "Hi {properties.x} {snippets.s} {vars.v} {userInfo.userId} {conversationInfo.agentId} {memory.current.input}";
    const prompt = buildOperatorSystemPrompt(body, "read_only");
    expect(findQuteExpressions(prompt)).toEqual([]);
    for (const span of body.match(/\{[^}]+\}/g) ?? []) expect(prompt).toContain(`{|${span}|}`);
    const live = buildOperatorSystemPrompt("Screen {context.screen}", "read_only");
    expect(live).toContain("Screen {context.screen}");
  });

  it("an edited body with a stray opener cannot break the render", () => {
    const prompt = buildOperatorSystemPrompt("Use the {forEach object", "read_only");
    expect(findQuteExpressions(prompt)).toEqual([]);
    expect(prompt).toContain("{|{|}forEach object");
  });
});
