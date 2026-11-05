import type { ApprovalContextBlock } from "./api/hitl";

/**
 * Client-side mirror of the backend `ApprovalContextValidation`. The backend is
 * the source of truth (it also parses the template on the restricted runtime
 * engine and refuses a bad one with a 400 that names the block); this catches
 * what is knowable without a parser, so the editor can say so while the author
 * types instead of after a failed save.
 */

/** Blocks per scope — `ApprovalContextValidation.MAX_*_BLOCKS`. */
export const MAX_APPROVAL_CONTEXT_BLOCKS = { agent: 8, rule: 4, group: 8 } as const;
export type ApprovalContextScope = keyof typeof MAX_APPROVAL_CONTEXT_BLOCKS;

export const MAX_APPROVAL_CONTEXT_LABEL = 80;
export const MAX_APPROVAL_CONTEXT_TEMPLATE = 4000;
export const MIN_APPROVAL_CONTEXT_CHARS = 50;
export const MAX_APPROVAL_CONTEXT_CHARS = 2900;
export const DEFAULT_APPROVAL_CONTEXT_CHARS = 1000;

export type ApprovalContextBlockError =
  | "label"
  | "template"
  | "maxChars"
  | "secretNamespace"
  | "callOutsideRule";

/** Namespaces that would put a secret's or connection's NAME on a card. */
const REFUSED_NAMESPACE = /\{[^{}]*\b(?:vault|eddivault|connection|caller):/i;
// The root of an expression, as the backend reads it: `call.` directly after the brace, not `properties.call.`.
const CALL_ROOT = /\{\s*call\./;

/** What is wrong with one block, or `null`. First problem wins. */
export function validateApprovalContextBlock(
  block: ApprovalContextBlock,
  scope: ApprovalContextScope,
): ApprovalContextBlockError | null {
  if (!block.label || block.label.trim() === "") return "label";
  if (!block.template || block.template.trim() === "") return "template";
  if (
    block.maxChars != null &&
    (!Number.isInteger(block.maxChars) ||
      block.maxChars < MIN_APPROVAL_CONTEXT_CHARS ||
      block.maxChars > MAX_APPROVAL_CONTEXT_CHARS)
  ) {
    return "maxChars";
  }
  if (REFUSED_NAMESPACE.test(block.template)) return "secretNamespace";
  if (scope !== "rule" && CALL_ROOT.test(block.template)) return "callOutsideRule";
  return null;
}

/** Whether any block of a list cannot be saved. */
export function approvalContextHasErrors(
  blocks: readonly ApprovalContextBlock[] | null | undefined,
  scope: ApprovalContextScope,
): boolean {
  return (blocks ?? []).some((b) => validateApprovalContextBlock(b, scope) !== null);
}
