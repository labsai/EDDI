/**
 * Literal braces in the operator's system prompt.
 *
 * The prompt is stored as an LLM task's `systemMessage`, and EDDI renders every
 * `systemMessage` as a Qute template on every turn (`LlmTask`). The prompt is
 * documentation: it names metric labels (`eddi_turn_rejected_total{reason}`),
 * path templates (`/agents/{agentId}/profile`) and guardrail variables
 * (`{input}`, `{output}`). To Qute each of those is an EXPRESSION, and it
 * fails in one of two ways:
 *
 * - it does not parse (`forEach {pathToTargetArray (…), …}` — "invalid virtual
 *   method"): the render throws, `LlmTask` logs an ERROR on every turn and sends
 *   the whole prompt UNRENDERED, `{#if}` sections and all. 6.7.0 shipped this.
 * - it parses and resolves to nothing: no error, no warning, the text just
 *   vanishes (`quarkus.qute.property-not-found-strategy=NOOP`). The operator is
 *   then told that a guardrail template is "rendered with , and" — and writes
 *   its own templates accordingly. This is the worse one, because nothing
 *   reports it.
 *
 * Rewording each mention fixes only the ones somebody noticed. This escapes
 * them all, at the one place the prompt is built: every span Qute would treat
 * as an expression is wrapped in an unparsed block (`{|…|}`, the convention of
 * `TemplateEscaping` on the backend), so it reaches the model as typed.
 *
 * Left alone, because they are meant to be live:
 *
 * - the Manager's own per-turn context block (`{#if context.screen}…{/if}`,
 *   `{context.agentId}`), which {@link LIVE_SYNTAX} names exactly — a new
 *   template feature has to be added there on purpose;
 * - `{vault:…}`, `{vars:…}`, `{connection:…}`, `{caller:…}` and the legacy
 *   `{eddivault:…}`: `LlmTask.escapeConfigReferenceMentions` wraps those itself,
 *   and a second wrap would render its markers (its "known limit");
 * - an unparsed block the author already wrote.
 *
 * What counts as an expression is the lexer's rule, not a guess:
 * `qute-literal-corpus.json` records, case by case, what the engine does with a
 * brace in each position, and `OperatorPromptQuteCorpusTest` (Java) renders
 * every case through EDDI's real templating engine and fails when this file's
 * idea of the rule and the engine's drift apart. In short: a `{` opens an
 * expression when the next character is an ASCII letter or one of `! # / |`;
 * anything else (`{"json": 1}`, `{}`, `{ x }`, `{0}`) is literal text, and a
 * doubled `{{` is a pair of literal braces.
 */

/**
 * `{{`: two literal braces. Qute reads a doubled opener as a pair of plain
 * characters, so `{{a}}` is text and only the odd brace of a longer run can
 * open an expression. Consumed pairwise, left to right, like the lexer.
 */
const DOUBLE_OPENER = String.raw`\{\{`;
/** A `{vault:…}`-style reference mention, which the backend escapes itself. */
const MENTION = String.raw`\{(?:vault|eddivault|vars|connection|caller):[^}]*\}`;
/** An unparsed block the author already wrote. */
const RAW_BLOCK = String.raw`\{\|[\s\S]*?\|\}`;
/** `\{` and `\}`: Qute consumes the backslash. */
const BACKSLASH_BRACE = String.raw`\\[{}]`;
/** An expression: opened by a letter or `! # /`, closed by the next `}`. */
const EXPRESSION = String.raw`\{[A-Za-z!#/][^}]*\}`;
/** An opener with no closing brace anywhere after it: a parse error to Qute. */
const UNTERMINATED = String.raw`\{(?=[A-Za-z!#/|])`;

const TOKEN = new RegExp(
  [DOUBLE_OPENER, MENTION, RAW_BLOCK, BACKSLASH_BRACE, EXPRESSION, UNTERMINATED].join("|"),
  "g",
);

/**
 * The only template syntax the prompt is meant to evaluate: the per-turn
 * application context block of `system-prompt.ts` (`BODY_APP_CONTEXT`).
 */
const LIVE_SYNTAX = /^\{(?:#if context\.[A-Za-z]+|\/if|context\.[A-Za-z]+)\}$/;

/** How a matched span is treated. */
function isKept(token: string): boolean {
  return (
    new RegExp(`^(?:${DOUBLE_OPENER}|${MENTION}|${RAW_BLOCK})$`).test(token) ||
    LIVE_SYNTAX.test(token)
  );
}

/**
 * Every span of `text` that Qute would evaluate and that is neither a
 * reference mention, an existing unparsed block nor the Manager's own context
 * syntax. Empty means the text renders exactly as written (apart from that
 * context block).
 */
export function findQuteExpressions(text: string): string[] {
  const found: string[] = [];
  for (const match of text.matchAll(TOKEN)) {
    if (!isKept(match[0])) found.push(match[0]);
  }
  return found;
}

/**
 * `text` with every span {@link findQuteExpressions} reports wrapped in an
 * unparsed block, so the engine outputs it unchanged. Idempotent: a wrapped
 * span is an unparsed block, which is left alone.
 */
export function escapeQuteLiterals(text: string): string {
  return text.replace(TOKEN, (token) => {
    if (isKept(token)) return token;
    // An opener with nothing to close it: wrap just the brace.
    if (token === "{") return "{|{|}";
    return `{|${token}|}`;
  });
}
