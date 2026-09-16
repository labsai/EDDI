import type { ToolTraceEntry } from "@/hooks/use-debug-events";

/**
 * Pairs each tool_call with ITS result by walking the trace in order, not by
 * zipping two filtered arrays by index.
 *
 * The positional zip broke on `tool_error`: the backend interleaves error
 * entries with NO matching tool_result (budget/quota refusals), and emits
 * pause-cap errors with no preceding call at all. One refused call mid-turn
 * shifted every later pairing — the refused call showed the NEXT call's result
 * under a green check, and the final call spun forever: the exact symptom
 * class the "spinning last call" fix addressed, from a different cause.
 *
 * A call's outcome is the first tool_result OR tool_error that follows it
 * before the next tool_call; an error entry with no preceding call is
 * surfaced as its own row rather than silently dropped.
 */
export function pairToolTrace(trace: ToolTraceEntry[] | undefined): {
  call?: ToolTraceEntry;
  result?: ToolTraceEntry;
  error?: ToolTraceEntry;
}[] {
  if (!trace?.length) return [];
  const pairs: { call?: ToolTraceEntry; result?: ToolTraceEntry; error?: ToolTraceEntry }[] = [];
  let open: { call?: ToolTraceEntry; result?: ToolTraceEntry; error?: ToolTraceEntry } | null = null;
  for (const entry of trace) {
    if (entry.type === "tool_call") {
      open = { call: entry };
      pairs.push(open);
    } else if (entry.type === "tool_result") {
      if (open && !open.result && !open.error) {
        open.result = entry;
        open = null;
      }
    } else if (entry.type === "tool_error") {
      if (open && !open.result && !open.error) {
        open.error = entry;
        open = null;
      } else {
        // A cap/quota refusal with no preceding call — visible, not dropped.
        pairs.push({ error: entry });
      }
    }
  }
  return pairs;
}
