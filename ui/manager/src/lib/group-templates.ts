import type {
  AgentGroupConfiguration,
  GroupMember,
  DiscussionStyle,
} from "./api/groups";

/**
 * Preset templates for common group configurations.
 * Each template includes suggested roles, display names, and discussion settings.
 */

export interface GroupTemplate {
  key: string;
  name: string;
  description: string;
  icon: string;
  style: DiscussionStyle;
  maxRounds: number;
  roles: Array<{ displayName: string; role: string | null }>;
  moderatorSuggested: boolean;
}

export const GROUP_TEMPLATES: GroupTemplate[] = [
  {
    key: "advisory-board",
    name: "Advisory Board",
    description:
      "A panel of expert advisors consulting on strategic decisions. Each agent represents a different business function and provides domain-specific insights.",
    icon: "👔",
    style: "ROUND_TABLE",
    maxRounds: 2,
    roles: [
      { displayName: "Marketing Expert", role: "Marketing" },
      { displayName: "Tech Lead", role: "Engineering" },
      { displayName: "Finance Director", role: "Finance" },
      { displayName: "Legal Counsel", role: "Legal" },
      { displayName: "Strategy Consultant", role: "Strategy" },
    ],
    moderatorSuggested: true,
  },
  {
    key: "code-review",
    name: "Code Review Panel",
    description:
      "Structured code review with independent opinions, peer critique, revision based on feedback, and a synthesized assessment.",
    icon: "🔍",
    style: "PEER_REVIEW",
    maxRounds: 1,
    roles: [
      { displayName: "Senior Engineer", role: "Code Quality" },
      { displayName: "Architect", role: "Architecture" },
      { displayName: "Security Reviewer", role: "Security" },
    ],
    moderatorSuggested: true,
  },
  {
    key: "risk-assessment",
    name: "Risk Assessment",
    description:
      "Panel with a devil's advocate who challenges assumptions and identifies blind spots. Great for stress-testing proposals and strategies.",
    icon: "⚠️",
    style: "DEVIL_ADVOCATE",
    maxRounds: 1,
    roles: [
      { displayName: "Risk Analyst", role: "Risk" },
      { displayName: "Domain Expert", role: "Domain" },
      { displayName: "Devil's Advocate", role: "DEVIL_ADVOCATE" },
    ],
    moderatorSuggested: true,
  },
  {
    key: "forecasting",
    name: "Forecasting Panel",
    description:
      "Delphi-style anonymous deliberation for reducing groupthink and getting unbiased independent estimates and forecasts.",
    icon: "🔮",
    style: "DELPHI",
    maxRounds: 3,
    roles: [
      { displayName: "Analyst A", role: "Forecasting" },
      { displayName: "Analyst B", role: "Forecasting" },
      { displayName: "Analyst C", role: "Forecasting" },
      { displayName: "Analyst D", role: "Forecasting" },
    ],
    moderatorSuggested: true,
  },
  {
    key: "pro-con",
    name: "Pro/Con Debate",
    description:
      "Formal debate with pro and con teams arguing their positions, followed by rebuttals and a judge's verdict.",
    icon: "⚖️",
    style: "DEBATE",
    maxRounds: 1,
    roles: [
      { displayName: "Pro Advocate 1", role: "PRO" },
      { displayName: "Pro Advocate 2", role: "PRO" },
      { displayName: "Con Advocate 1", role: "CON" },
      { displayName: "Con Advocate 2", role: "CON" },
    ],
    moderatorSuggested: true,
  },
];

/**
 * Build an AgentGroupConfiguration from a template.
 * Members are placeholders — users must assign real agent IDs.
 */
export function buildGroupFromTemplate(
  template: GroupTemplate,
  overrides?: {
    name?: string;
    members?: GroupMember[];
    moderatorAgentId?: string;
    maxRounds?: number;
  }
): AgentGroupConfiguration {
  const members: GroupMember[] =
    overrides?.members ??
    template.roles.map((r, i) => ({
      agentId: "", // Placeholder — user must select an agent
      displayName: r.displayName,
      speakingOrder: i + 1,
      role: r.role,
      memberType: "AGENT" as const,
    }));

  return {
    name: overrides?.name ?? template.name,
    description: template.description,
    members,
    moderatorAgentId: overrides?.moderatorAgentId ?? null,
    style: template.style,
    maxRounds: overrides?.maxRounds ?? template.maxRounds,
    phases: null, // Preset styles auto-expand
    protocol: {
      agentTimeoutSeconds: 60,
      onAgentFailure: "SKIP",
      maxRetries: 2,
      onMemberUnavailable: "SKIP",
    },
  };
}
