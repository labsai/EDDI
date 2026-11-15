import { validateToolPattern } from "@/lib/hitl-tool-approvals";
import { newGuardrail } from "./guardrails";
import type { LlmTask, ToolCallGuardrailConfig } from "./types";

/** Mirrors `ToolCallGuardrailConfigValidator`. */
export const MAX_TOOL_CALL_GUARDRAILS = 32;
export const MAX_TOOL_PATTERNS = 32;
export const MAX_FIELDS = 16;

/** How the whole-arguments pointer `""` is written in the one-per-line editor. */
export const WHOLE_ARGUMENTS = '""';

/** A new entry of `type`; `tools` is required, so it starts empty and is flagged. */
export function newToolCallGuardrail(type: string): ToolCallGuardrailConfig {
  return { ...newGuardrail(type), tools: [] };
}

/** One non-blank, trimmed entry per line. */
export function linesOf(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line !== "");
}

/** The fields as the editor shows them: `""` stands for the whole arguments. */
export function fieldsToText(fields: string[] | undefined): string {
  return (fields ?? []).map((f) => (f === "" ? WHOLE_ARGUMENTS : f)).join("\n");
}

/** The editor's lines back to pointers. */
export function textToFields(text: string): string[] {
  return linesOf(text).map((line) => (line === WHOLE_ARGUMENTS ? "" : line));
}

export function withTools(entry: ToolCallGuardrailConfig, tools: string[]): ToolCallGuardrailConfig {
  return { ...entry, tools };
}

/** Sets the fields; none means "every string", stored as absent. */
export function withFields(entry: ToolCallGuardrailConfig, fields: string[]): ToolCallGuardrailConfig {
  const { fields: _drop, ...rest } = entry;
  void _drop;
  return fields.length > 0 ? { ...rest, fields } : rest;
}

/**
 * True for a pattern the server would refuse: the same rules as the tool-approval
 * editor (`ToolApprovalPatterns.validate` — length, characters, colons, a known
 * source prefix, an endpoint path behind an http method with its leading slash).
 */
export function toolPatternProblem(pattern: string): boolean {
  return validateToolPattern(pattern) !== null;
}

/**
 * Mirrors `ArgumentFields.pointerProblem`: `""`, or a JSON pointer whose `~`
 * escapes are `~0`/`~1` and whose `*` stands for a whole segment.
 */
export function pointerProblem(pointer: string): boolean {
  if (pointer === "") return false;
  if (!pointer.startsWith("/")) return true;
  for (const segment of pointer.slice(1).split("/")) {
    if (/~(?![01])/.test(segment)) return true;
    if (segment.includes("*") && segment !== "*") return true;
  }
  return false;
}

/**
 * Writes the list back; an emptied list removes the field, so a task the author
 * never gave one reaches an older server unchanged.
 */
export function withToolCallGuardrails(task: LlmTask, list: ToolCallGuardrailConfig[]): LlmTask {
  const { toolCallGuardrails: _drop, ...rest } = task;
  void _drop;
  return list.length > 0 ? { ...rest, toolCallGuardrails: list } : (rest as LlmTask);
}
