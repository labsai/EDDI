import type { GroupConversationState, DiscussionStyle } from "@/lib/api/groups";
import { STYLE_INFO } from "@/lib/api/groups";

// ─── Filter types ────────────────────────────────────────────────

export interface ActiveFilter {
  type: "outcome" | "style" | "date" | "agent";
  label: string;
  value: string;
}

// ─── Label helpers ───────────────────────────────────────────────

const STATE_LABELS: Record<GroupConversationState, string> = {
  COMPLETED: "Completed",
  FAILED: "Failed",
  IN_PROGRESS: "In Progress",
  SYNTHESIZING: "Synthesizing",
  CREATED: "Created",
  CANCELLED: "Cancelled",
  AWAITING_APPROVAL: "Pending",
};

export function stateLabel(s: GroupConversationState): string {
  return STATE_LABELS[s] ?? s;
}

export function styleLabel(s: DiscussionStyle): string {
  return STYLE_INFO[s]?.label ?? s;
}
