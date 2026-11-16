import { BODY_APP_CONTEXT } from "./context-block";

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
 * The rule is deliberately NOT "escape what Qute's lexer treats as an
 * expression". That rule has to be mirrored (letters, `! # / |`, underscores,
 * Unicode identifiers, `{@…}` declarations, `{0}`, backslash escapes …), a
 * mirror drifts, and what it gets wrong is dropped silently. The rule is the
 * conservative one instead: every `{` is escaped, except in exactly three places.
 *
 * - the Manager's own per-turn context block ({@link BODY_APP_CONTEXT}), matched
 *   as that exact text and nothing looser. `{context.screen}` or `{#if …}`
 *   typed into the instructions is therefore literal text;
 * - a reference mention (`${vault:…}`, `${vars:…}`, `${connection:…}`,
 *   `${caller:…}`, legacy `${eddivault:…}`): `LlmTask.escapeConfigReferenceMentions`
 *   wraps those itself, and a second wrap would nest raw blocks and render their
 *   markers. The escape therefore splits AROUND a mention and never wraps it;
 * - an unparsed block (`{|…|}`) the author already wrote, which is safe for any
 *   content.
 *
 * An escaped brace is `{|{|}`: an unparsed block holding the brace. Per brace,
 * not per span, so no span can contain the `|}` that would end the block early
 * and nothing has to decide where an "expression" stops. A run of backslashes in
 * front of a brace goes inside the block with it, because Qute reads `\{` and
 * `\}` as escapes and eats the backslash.
 *
 * `qute-literal-corpus.json` records the outcome, case by case, and
 * `OperatorPromptQuteCorpusTest` (Java) renders every case through EDDI's real
 * templating engine, the way `LlmTask` does, and fails if the escaped text does
 * not come back exactly as typed.
 */

/** A reference mention, which the backend escapes itself. Same shape as `LlmTask`'s. */
const MENTION = String.raw`\{(?:vault|eddivault|vars|connection|caller):[^}]*\}`;
/** An unparsed block the author already wrote: opened by one or more pipes, closed by the same number. */
const RAW_BLOCK = String.raw`\{(\|+)[\s\S]*?\1\}`;
/** Spans the escape leaves exactly as it found them (the context block is matched separately). */
const KEPT = new RegExp(`${RAW_BLOCK}|${MENTION}`, "g");
/** What gets wrapped: an opener (with any backslashes before it), or a backslash-escaped closer. */
const BRACE = /\\*\{|\\+\}/g;

/** One piece of the text, in order: left alone, or subject to the escape. */
interface Segment {
  text: string;
  /** True when the piece is left exactly as written. */
  kept: boolean;
}

/** `text` cut at the spans the escape never touches. */
function segments(text: string): Segment[] {
  const out: Segment[] = [];
  let rest = text;
  while (rest.length > 0) {
    const at = rest.indexOf(BODY_APP_CONTEXT);
    const head = at < 0 ? rest : rest.slice(0, at);
    let last = 0;
    for (const match of head.matchAll(KEPT)) {
      if (match.index > last) out.push({ text: head.slice(last, match.index), kept: false });
      out.push({ text: match[0], kept: true });
      last = match.index + match[0].length;
    }
    if (last < head.length) out.push({ text: head.slice(last), kept: false });
    if (at < 0) break;
    out.push({ text: BODY_APP_CONTEXT, kept: true });
    rest = rest.slice(at + BODY_APP_CONTEXT.length);
  }
  return out;
}

/**
 * Every brace of `text` the engine would still read as syntax: each `{` (with
 * its backslashes) and each backslash-escaped `}` outside the places the
 * escape leaves alone. Empty means the text renders exactly as written, apart
 * from the context block. Fed `escapeQuteLiterals`' output, it is empty.
 */
export function findUnescapedBraces(text: string): string[] {
  const found: string[] = [];
  for (const segment of segments(text)) {
    if (segment.kept) continue;
    for (const match of segment.text.matchAll(BRACE)) found.push(match[0]);
  }
  return found;
}

/**
 * `text` with every brace outside the three exceptions wrapped in an unparsed
 * block, so the engine outputs it unchanged. Idempotent: what it wrote is an
 * unparsed block, which is left alone.
 */
export function escapeQuteLiterals(text: string): string {
  return segments(text)
    .map((segment) => (segment.kept ? segment.text : segment.text.replace(BRACE, (brace) => `{|${brace}|}`)))
    .join("");
}
