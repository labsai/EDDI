import { api } from "../api-client";

/* ─── Types ─── */

export interface AuditEntry {
  id: string;
  conversationId: string;
  agentId: string;
  agentVersion: number | null;
  userId: string | null;
  environment: string | null;
  stepIndex: number;
  taskId: string;
  taskType: string;
  taskIndex: number;
  durationMs: number;
  input: Record<string, unknown> | null;
  output: Record<string, unknown> | null;
  llmDetail: Record<string, unknown> | null;
  /**
   * The raw tool trace. On the wire this is a MAP, `{ calls: [...] }`
   * (`LlmTask.accumulateToolCalls` → `AuditEntry.toolCalls: Map<String,Object>`),
   * whose `calls` are trace events — `{type:"tool_call", tool, arguments}`,
   * `{type:"tool_result", tool, result}`, plus budget/cap markers. It was typed as
   * an array of `{name, arguments}`, so no tool call ever rendered. Read it
   * through {@link auditToolCalls}, never directly.
   */
  toolCalls: Record<string, unknown> | Array<Record<string, unknown>> | null;
  actions: string[] | null;
  cost: number;
  timestamp: string; // ISO instant
  hmac: string | null;
  agentSignature: string | null;
}

/** One tool invocation recorded in an audit entry, with its result when the trace has one. */
export interface AuditToolCall {
  tool: string;
  /** The model's arguments — a JSON string as recorded, already secret-redacted. */
  arguments: unknown;
  result?: unknown;
  /** Why the call was refused (a `tool_error` trace event: budget, quota, HITL cap). */
  error?: unknown;
  /** Which LLM sub-task issued the call, when the entry merges several. */
  llmTaskId?: string;
}

/**
 * The tool calls of an audit entry, in order, each paired with its result.
 *
 * Accepts the backend's `{ calls: [...] }` map and, defensively, a bare array.
 * Events without a type are treated as calls (a hand-written or older entry);
 * other markers are skipped — they are not invocations. A `tool_error` attaches
 * its reason as `error` to the call it refused. A
 * `tool_result` attaches to the most recent unanswered call of the same tool,
 * the same pairing `RestToolHistory` uses.
 */
export function auditToolCalls(entry: Pick<AuditEntry, "toolCalls"> | null | undefined): AuditToolCall[] {
  const raw = entry?.toolCalls;
  const events: unknown[] = Array.isArray(raw)
    ? raw
    : raw && typeof raw === "object" && Array.isArray((raw as { calls?: unknown }).calls)
      ? (raw as { calls: unknown[] }).calls
      : [];
  const calls: AuditToolCall[] = [];
  for (const event of events) {
    if (!event || typeof event !== "object") continue;
    const e = event as Record<string, unknown>;
    const tool = typeof e.tool === "string" ? e.tool : typeof e.name === "string" ? e.name : null;
    const type = typeof e.type === "string" ? e.type : undefined;
    if (type === "tool_result") {
      for (let i = calls.length - 1; i >= 0; i--) {
        const call = calls[i]!;
        if (call.tool === tool && call.result === undefined) {
          call.result = e.result;
          break;
        }
      }
      continue;
    }
    if (type === "tool_error") {
      // Budget, cost-quota and HITL-cap refusals (ToolLoopRunner). Attach the
      // reason to the call it refused; a refusal with no recorded call (the
      // pause cap fires before one is logged) is shown as its own entry, so
      // the reason is never lost.
      const pending = [...calls].reverse().find(
        (c) => c.tool === tool && c.result === undefined && c.error === undefined,
      );
      if (pending) {
        pending.error = e.error;
      } else if (tool !== null) {
        calls.push({ tool, arguments: undefined, error: e.error });
      }
      continue;
    }
    if ((type !== undefined && type !== "tool_call") || tool === null) continue;
    const call: AuditToolCall = { tool, arguments: e.arguments };
    if (typeof e.llmTaskId === "string") call.llmTaskId = e.llmTaskId;
    calls.push(call);
  }
  return calls;
}

/* ─── API Functions ─── */

const BASE = "/auditstore";

/** Get the audit trail for a specific conversation. */
export async function getAuditTrail(
  conversationId: string,
  skip = 0,
  limit = 100,
): Promise<AuditEntry[]> {
  return api.get<AuditEntry[]>(
    `${BASE}/${conversationId}?skip=${skip}&limit=${limit}`,
  );
}

/** Get the audit trail for a specific agent. */
export async function getAuditTrailByAgent(
  agentId: string,
  agentVersion?: number | null,
  skip = 0,
  limit = 100,
): Promise<AuditEntry[]> {
  const params = new URLSearchParams({ skip: String(skip), limit: String(limit) });
  if (agentVersion != null) {
    params.set("agentVersion", String(agentVersion));
  }
  return api.get<AuditEntry[]>(`${BASE}/agent/${agentId}?${params.toString()}`);
}

/** Get the number of audit entries for a conversation. */
export async function getEntryCount(conversationId: string): Promise<number> {
  return api.get<number>(`${BASE}/${conversationId}/count`);
}
