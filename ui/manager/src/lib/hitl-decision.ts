import type { HitlVerdict, PauseDetails, ToolCallDecision } from "@/lib/api/hitl";

/**
 * What a reviewer agreed to, captured the moment the decision was submitted, so
 * the approval card can collapse into a short receipt while the resumed work
 * runs. One shape for every deciding surface: tool calls (1:1), tasks (group
 * TASK granularity) or a whole phase (group PHASE granularity).
 */
export interface SubmittedDecision {
  /** Identity of the pause this was decided for; a summary never outlives it. */
  cardKey: string;
  verdict: HitlVerdict;
  note?: string;
  /** Group PHASE/TASK: the phase that was decided. */
  phaseName?: string;
  /** Per-call (or per-task) outcomes; empty for a plain RULE or PHASE decision. */
  items: { id: string; label: string; outcome: HitlVerdict; amended: boolean }[];
}

interface SummariseArgs {
  cardKey: string;
  verdict: HitlVerdict;
  note?: string;
  pauseDetails?: PauseDetails | null;
  toolDecisions?: Record<string, ToolCallDecision>;
  /** Group TASK granularity: the tasks that were on the card. */
  pendingTaskIds?: readonly string[];
  taskApprovals?: Record<string, string>;
  phaseName?: string;
}

/**
 * The receipt for a decision. A call or task that is not listed inherits the
 * top-level verdict (that is what the backend does with it); a top-level
 * REJECTED rejects everything.
 */
export function summariseDecision(args: SummariseArgs): SubmittedDecision {
  const { cardKey, verdict, pauseDetails, toolDecisions, pendingTaskIds, taskApprovals } = args;
  let items: SubmittedDecision["items"] = [];
  if (pauseDetails?.type === "TOOL_CALL") {
    items = pauseDetails.calls.map((call) => ({
      id: call.callId,
      label: call.toolName,
      outcome: verdict === "REJECTED" ? "REJECTED" : (toolDecisions?.[call.callId]?.verdict ?? "APPROVED"),
      amended: verdict === "APPROVED" && !!toolDecisions?.[call.callId]?.amendedArguments,
    }));
  } else if (pendingTaskIds && pendingTaskIds.length > 0) {
    items = pendingTaskIds.map((id) => ({
      id,
      label: id,
      outcome: verdict === "REJECTED" ? "REJECTED" : taskApprovals?.[id] === "REJECTED" ? "REJECTED" : "APPROVED",
      amended: false,
    }));
  }
  return {
    cardKey,
    verdict,
    note: args.note?.trim() || undefined,
    phaseName: args.phaseName,
    items,
  };
}
