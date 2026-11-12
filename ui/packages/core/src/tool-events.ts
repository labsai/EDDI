/* ──────────────────────────────────────────────
   @eddi/core/tool-events — the `tool_result` stream event (B6)
   A separate entry, not re-exported from the package root: the Chat UI widget
   does not show tool activity and is at its bundle budget, so nothing here may
   reach its bundle. Hosts that show tool cards import it explicitly.
   ────────────────────────────────────────────── */

import { parseToolCallName } from "./stream-events";
import type { SSEEvent } from "./types";

/**
 * One finished tool call, from a `tool_result` frame — sent after every tool
 * call the turn announced with `tool_call`, in the same order:
 * `{"tool":"<name>","ok":true,"summary":"…"}`.
 */
export interface ToolResultEvent {
  /** The tool's name, trimmed and capped like a `tool_call` name. */
  tool: string;
  /** The tool ran and its result does not report a failure. */
  ok: boolean;
  /**
   * The start of the result, or why the call was refused (budget, quota,
   * deadline). Redacted on the server exactly like `task_complete`'s
   * `toolTrace`, whitespace collapsed, at most 200 characters. May be empty.
   */
  summary: string;
}

/** Longest summary the backend sends (`ToolTraceRedaction.MAX_SUMMARY_LENGTH`). */
export const MAX_TOOL_RESULT_SUMMARY_LENGTH = 200;

/**
 * Parse a `tool_result` payload. Null for anything unusable — no tool name, not
 * JSON — so a malformed frame shows nothing rather than "undefined finished".
 * `ok` is true only when the payload says `true`; a summary longer than the
 * backend ever sends is cut, in case something in between grew it.
 */
export function parseToolResult(data: string): ToolResultEvent | null {
  const tool = parseToolCallName(data);
  if (!tool) return null;
  const parsed = JSON.parse(data) as { ok?: unknown; summary?: unknown };
  const summary = typeof parsed.summary === "string" ? parsed.summary : "";
  return {
    tool,
    ok: parsed.ok === true,
    summary:
      summary.length > MAX_TOOL_RESULT_SUMMARY_LENGTH
        ? `${summary.slice(0, MAX_TOOL_RESULT_SUMMARY_LENGTH - 1)}…`
        : summary,
  };
}

/**
 * An `onStreamEvent` listener (AgentConversation's option, or any loop over
 * `sendMessageStreaming`) that calls `onToolResult` for each usable
 * `tool_result` frame and ignores every other event.
 */
export function toolResultListener(
  onToolResult: (result: ToolResultEvent) => void,
): (event: SSEEvent) => void {
  return (event) => {
    if (event.type !== "tool_result") return;
    const result = parseToolResult(event.data);
    if (result) onToolResult(result);
  };
}
