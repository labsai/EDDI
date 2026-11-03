import {
  BUILT_IN_TOOLS,
  type AgentOpsConfig,
  type AgentOpsSlug,
  type LlmTask,
} from "./types";

export type OpsBlock = "scorecard" | "budgetProposal" | "followUp";

/**
 * `task` with one slug switched on or off in `builtInToolsWhitelist`.
 *
 * Switching one on while the whitelist is empty ("all built-in tools") must not
 * narrow the agent to this one tool: the plain tools are listed first, so the
 * agent keeps what it had. These three are never part of "all": an omitted
 * whitelist does not admit them, which is the point of listing them here.
 */
export function withOpsTool(task: LlmTask, slug: AgentOpsSlug, on: boolean): LlmTask {
  const current = task.builtInToolsWhitelist ?? [];
  if (on) {
    const base = current.length > 0 ? current : [...BUILT_IN_TOOLS];
    return {
      ...task,
      enableBuiltInTools: true,
      builtInToolsWhitelist: base.includes(slug) ? base : [...base, slug],
    };
  }
  const next = current.filter((item) => item !== slug);
  return { ...task, builtInToolsWhitelist: next.length > 0 ? next : undefined };
}

/**
 * `task` with one field of one `agentOps` block changed. A cleared field is
 * dropped, and a block or object left with nothing is dropped too: the field is
 * written only when the author changed something, because an older backend's
 * strict parser refuses a field it does not know.
 */
export function withOpsField(task: LlmTask, block: OpsBlock, field: string, value: unknown): LlmTask {
  const ops: Record<string, Record<string, unknown> | undefined> = { ...task.agentOps };
  const nextBlock: Record<string, unknown> = { ...ops[block] };
  if (value === undefined || (Array.isArray(value) && value.length === 0)) delete nextBlock[field];
  else nextBlock[field] = value;
  if (Object.keys(nextBlock).length > 0) ops[block] = nextBlock;
  else delete ops[block];
  return { ...task, agentOps: Object.keys(ops).length > 0 ? (ops as AgentOpsConfig) : undefined };
}
