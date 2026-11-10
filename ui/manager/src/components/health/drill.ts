import type { ResolutionKey } from "./format";

/** What the sessions drill-down is filtered to. */
export interface DrillFilter {
  outcome?: string[];
  evidence?: string[];
  /** i18n key + English fallback for the dialog subtitle. */
  title: [key: string, fallback: string];
}

const BY_MEASURE: Record<string, DrillFilter> = {
  resolutionRate: { outcome: ["RESOLVED", "ASSUMED_RESOLVED"], title: ["health.drill.resolved", "Resolved sessions"] },
  escalationRate: { outcome: ["ESCALATED"], title: ["health.drill.escalated", "Escalated sessions"] },
  // ENDED_QUIETLY is its own outcome (not a failure to resolve), so it is not listed here.
  abandonmentRate: { outcome: ["ABANDONED"], title: ["health.drill.abandoned", "Abandoned sessions"] },
  deflectionRate: { outcome: ["DEFLECTED"], title: ["health.drill.deflected", "Deflected sessions"] },
};

/** Effectiveness measures that open a session list; others have no drill-down. */
export function drillForMeasure(measureId: string): DrillFilter | null {
  return BY_MEASURE[measureId] ?? null;
}

const BY_RESOLUTION: Record<ResolutionKey, DrillFilter> = {
  // External events and explicit agent actions only; the model's own report is separate.
  confirmed: {
    outcome: ["RESOLVED", "ASSUMED_RESOLVED"],
    evidence: ["external_event", "conversation_id_event", "agent_action"],
    title: ["health.drill.confirmed", "Sessions with a confirmed resolution"],
  },
  selfReported: {
    outcome: ["RESOLVED", "ASSUMED_RESOLVED"],
    evidence: ["self_reported"],
    title: ["health.drill.selfReported", "Sessions the agent reported as resolved"],
  },
  agentClosed: {
    outcome: ["ASSUMED_RESOLVED"],
    evidence: ["agent_closed"],
    title: ["health.drill.agentClosed", "Sessions the agent closed"],
  },
  silence: {
    outcome: ["ASSUMED_RESOLVED"],
    evidence: ["silence"],
    title: ["health.drill.silence", "Sessions assumed resolved from silence"],
  },
};

export function drillForResolution(key: ResolutionKey): DrillFilter {
  return BY_RESOLUTION[key];
}
