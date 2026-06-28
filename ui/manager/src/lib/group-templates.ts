import type {
  AgentGroupConfiguration,
  GroupMember,
  DiscussionStyle,
} from "./api/groups";
import type { TFunction } from "i18next";

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

/**
 * Returns translated group templates.
 * Accepts a `t` function from `useTranslation()` so all display
 * strings are locale-aware and update when the language changes.
 */
export function getGroupTemplates(t: TFunction): GroupTemplate[] {
  return [
    {
      key: "advisory-board",
      name: t("groupTemplates.advisoryBoard"),
      description: t("groupTemplates.advisoryBoardDesc"),
      icon: "👔",
      style: "ROUND_TABLE",
      maxRounds: 2,
      roles: [
        { displayName: t("groupTemplates.roles.marketingExpert"), role: "Marketing" },
        { displayName: t("groupTemplates.roles.techLead"), role: "Engineering" },
        { displayName: t("groupTemplates.roles.financeDirector"), role: "Finance" },
        { displayName: t("groupTemplates.roles.legalCounsel"), role: "Legal" },
        { displayName: t("groupTemplates.roles.strategyConsultant"), role: "Strategy" },
      ],
      moderatorSuggested: true,
    },
    {
      key: "code-review",
      name: t("groupTemplates.codeReview"),
      description: t("groupTemplates.codeReviewDesc"),
      icon: "🔍",
      style: "PEER_REVIEW",
      maxRounds: 1,
      roles: [
        { displayName: t("groupTemplates.roles.seniorEngineer"), role: "Code Quality" },
        { displayName: t("groupTemplates.roles.architect"), role: "Architecture" },
        { displayName: t("groupTemplates.roles.securityReviewer"), role: "Security" },
      ],
      moderatorSuggested: true,
    },
    {
      key: "risk-assessment",
      name: t("groupTemplates.riskAssessment"),
      description: t("groupTemplates.riskAssessmentDesc"),
      icon: "⚠️",
      style: "DEVIL_ADVOCATE",
      maxRounds: 1,
      roles: [
        { displayName: t("groupTemplates.roles.riskAnalyst"), role: "Risk" },
        { displayName: t("groupTemplates.roles.domainExpert"), role: "Domain" },
        { displayName: t("groupTemplates.roles.devilsAdvocate"), role: "DEVIL_ADVOCATE" },
      ],
      moderatorSuggested: true,
    },
    {
      key: "forecasting",
      name: t("groupTemplates.forecasting"),
      description: t("groupTemplates.forecastingDesc"),
      icon: "🔮",
      style: "DELPHI",
      maxRounds: 3,
      roles: [
        { displayName: t("groupTemplates.roles.analystA"), role: "Forecasting" },
        { displayName: t("groupTemplates.roles.analystB"), role: "Forecasting" },
        { displayName: t("groupTemplates.roles.analystC"), role: "Forecasting" },
        { displayName: t("groupTemplates.roles.analystD"), role: "Forecasting" },
      ],
      moderatorSuggested: true,
    },
    {
      key: "pro-con",
      name: t("groupTemplates.proCon"),
      description: t("groupTemplates.proConDesc"),
      icon: "⚖️",
      style: "DEBATE",
      maxRounds: 1,
      roles: [
        { displayName: t("groupTemplates.roles.proAdvocate1"), role: "PRO" },
        { displayName: t("groupTemplates.roles.proAdvocate2"), role: "PRO" },
        { displayName: t("groupTemplates.roles.conAdvocate1"), role: "CON" },
        { displayName: t("groupTemplates.roles.conAdvocate2"), role: "CON" },
      ],
      moderatorSuggested: true,
    },
    {
      key: "task-force",
      name: t("groupTemplates.taskForce"),
      description: t("groupTemplates.taskForceDesc"),
      icon: "🎯",
      style: "TASK_FORCE",
      maxRounds: 1,
      roles: [
        { displayName: t("groupTemplates.roles.projectLead"), role: "Lead" },
        { displayName: t("groupTemplates.roles.researcher"), role: "Research" },
        { displayName: t("groupTemplates.roles.implementer"), role: "Implementation" },
        { displayName: t("groupTemplates.roles.qualityAssurance"), role: "QA" },
      ],
      moderatorSuggested: true,
    },
  ];
}

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
