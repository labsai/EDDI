import { describe, expect, it } from "vitest";
import { PRODUCT_NAME_MAX } from "@/lib/brand-validation";
import corpus from "./qute-literal-corpus.json";
import { BODY_APP_CONTEXT } from "../context-block";
import { escapeQuteLiterals, findUnescapedBraces } from "../qute-literal";
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
 *    module's output AND, in `OperatorPromptQuteCorpusTest`, against EDDI's real
 *    Qute engine, which has to render the escaped form back to what was typed.
 * 2. The real prompts. Every prompt the Manager can send, for both scopes and
 *    the longest brand name, is held to "no brace left for Qute to read, except
 *    the Manager's own context block". A prompt section added tomorrow by any of
 *    the open branches that edit `system-prompt.ts` is covered without anyone
 *    remembering to escape it.
 */

type Kind = "expression" | "inert" | "mention" | "raw" | "live";
const cases = corpus.cases as { name: string; text: string; kind: Kind; escaped: string }[];

describe("qute-literal corpus", () => {
  it.each(cases)("$name: escapes to the recorded form", ({ text, escaped }) => {
    expect(escapeQuteLiterals(text)).toBe(escaped);
  });

  it.each(cases)("$name: leaves no brace outside the places it keeps", ({ escaped }) => {
    expect(findUnescapedBraces(escaped)).toEqual([]);
  });

  it("is idempotent", () => {
    for (const { text } of cases) {
      const once = escapeQuteLiterals(text);
      expect(escapeQuteLiterals(once)).toBe(once);
    }
  });

  it("covers every kind, so a rule cannot quietly lose its case", () => {
    expect(new Set(cases.map((c) => c.kind))).toEqual(new Set(["expression", "inert", "mention", "raw", "live"]));
  });

  it("the live cases carry exactly the Manager's context block", () => {
    const live = cases.filter((c) => c.kind === "live");
    expect(live.length).toBeGreaterThan(1);
    for (const { text, escaped } of live) {
      expect(text).toContain(BODY_APP_CONTEXT);
      expect(escaped).toContain(BODY_APP_CONTEXT);
    }
  });

  it("splits around a reference mention instead of wrapping it", () => {
    // LlmTask.escapeConfigReferenceMentions wraps the mention itself; a wrap here would nest the blocks.
    expect(escapeQuteLiterals("{a: ${vault:key}}")).toBe("{|{|}a: ${vault:key}}");
    expect(escapeQuteLiterals("{a: ${vault:key}}")).not.toContain("{|{vault");
  });
});

describe("the operator system prompt", () => {
  const SCOPES: readonly OperatorScope[] = ["read_only", "read_write"];
  const LONGEST_BRAND = "B".repeat(PRODUCT_NAME_MAX);

  it.each(SCOPES)("%s: the BODY holds braces the engine would read, so the escape is doing work", (scope) => {
    // Guards the guard. If the documentation stopped containing any, the check
    // below would pass vacuously and a later section would be unprotected.
    const outsideContext = defaultOperatorPromptBody(scope).split(BODY_APP_CONTEXT).join("");
    expect(findUnescapedBraces(outsideContext).length).toBeGreaterThan(5);
  });

  it.each(SCOPES)("%s: no brace is left for Qute to read, except the context block", (scope) => {
    for (const brand of [undefined, LONGEST_BRAND]) {
      const prompt = buildOperatorSystemPrompt(defaultOperatorPromptBody(scope), scope, brand);
      expect(findUnescapedBraces(prompt)).toEqual([]);
    }
  });

  it.each(SCOPES)("%s: still delivers the per-turn context block live", (scope) => {
    const prompt = buildOperatorSystemPrompt(defaultOperatorPromptBody(scope), scope);
    expect(prompt).toContain(BODY_APP_CONTEXT);
  });

  it.each(SCOPES)("%s: documentation braces survive as written once rendered", (scope) => {
    const body = defaultOperatorPromptBody(scope);
    const prompt = buildOperatorSystemPrompt(body, scope);
    // What the engine does with an unparsed block is output its content, so
    // unwrapping them must give back the body's own spans.
    const unwrapped = prompt.replace(/\{\|([\s\S]*?)\|\}/g, "$1");
    for (const span of body.split(BODY_APP_CONTEXT).join("").match(/\{[^{}]*\}/g) ?? []) {
      expect(unwrapped).toContain(span);
    }
  });

  it("an edited body is literal text: only the Manager's own context block is evaluated", () => {
    // Pins a decision. Nothing documents template expressions in the operator
    // instructions (the editor hint says the text is sent as written), so every
    // brace an admin types is escaped, `{context.*}` and `{#if …}` included.
    const body =
      "Hi {properties.x} {snippets.s} {vars.v} {userInfo.userId} {memory.current.input} {context.screen} {#if properties.ready}OK{/if}";
    const prompt = buildOperatorSystemPrompt(body, "read_only");
    expect(findUnescapedBraces(prompt)).toEqual([]);
    for (const span of body.match(/\{[^{}]+\}/g) ?? []) expect(prompt).toContain(`{|{|}${span.slice(1)}`);
    expect(prompt).not.toContain(" {context.screen}");
    expect(prompt).toContain("{|{|}context.screen}");
  });

  it("an edited body with a stray opener cannot break the render", () => {
    const prompt = buildOperatorSystemPrompt("Use the {forEach object", "read_only");
    expect(findUnescapedBraces(prompt)).toEqual([]);
    expect(prompt).toContain("{|{|}forEach object");
  });
});
