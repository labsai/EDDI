import type { TourChapterId } from "@/hooks/use-onboarding";

/* ================================================================
   Tour step & chapter types
   ================================================================ */
export type TourPlacement = "top" | "bottom" | "left" | "right" | "auto";

export interface TourStep {
  /** CSS selector used to find the target element (prefer data-tour or data-testid) */
  target: string;
  /** i18n key for the step title */
  titleKey: string;
  /** i18n key for the step description */
  descriptionKey: string;
  /** Preferred tooltip placement */
  placement: TourPlacement;
  /** Extra padding around spotlight cutout (px) */
  padding?: number;
}

export interface TourChapter {
  id: TourChapterId;
  /** i18n key for chapter name (shown in Help menu) */
  titleKey: string;
  /** lucide icon name */
  icon: string;
  /** Route prefix this chapter applies to */
  route: string;
  steps: TourStep[];
}

/* ================================================================
   Chapter definitions
   ================================================================ */

const dashboardChapter: TourChapter = {
  id: "dashboard",
  titleKey: "onboarding.tour.dashboard.title",
  icon: "LayoutDashboard",
  route: "/manage",
  steps: [
    {
      target: '[data-testid="sidebar"] nav',
      titleKey: "onboarding.tour.dashboard.step1Title",
      descriptionKey: "onboarding.tour.dashboard.step1Desc",
      placement: "right",
      padding: 4,
    },
    {
      target: '[data-tour="dashboard-stats"]',
      titleKey: "onboarding.tour.dashboard.step2Title",
      descriptionKey: "onboarding.tour.dashboard.step2Desc",
      placement: "bottom",
    },
    {
      target: '[data-tour="dashboard-actions"]',
      titleKey: "onboarding.tour.dashboard.step3Title",
      descriptionKey: "onboarding.tour.dashboard.step3Desc",
      placement: "bottom",
    },
    {
      target: '[data-tour="topbar-personalize"]',
      titleKey: "onboarding.tour.dashboard.step4Title",
      descriptionKey: "onboarding.tour.dashboard.step4Desc",
      placement: "bottom",
    },
  ],
};

const agentsChapter: TourChapter = {
  id: "agents",
  titleKey: "onboarding.tour.agents.title",
  icon: "Bot",
  route: "/manage/agents",
  steps: [
    {
      target: '[data-testid="create-agent-btn"]',
      titleKey: "onboarding.tour.agents.step1Title",
      descriptionKey: "onboarding.tour.agents.step1Desc",
      placement: "bottom",
    },
    {
      target: '[data-testid="import-agent-btn"]',
      titleKey: "onboarding.tour.agents.step2Title",
      descriptionKey: "onboarding.tour.agents.step2Desc",
      placement: "bottom",
    },
    {
      target: '[data-tour="agents-search"]',
      titleKey: "onboarding.tour.agents.step3Title",
      descriptionKey: "onboarding.tour.agents.step3Desc",
      placement: "bottom",
    },
    {
      target: '[data-tour="agents-content"]',
      titleKey: "onboarding.tour.agents.step4Title",
      descriptionKey: "onboarding.tour.agents.step4Desc",
      placement: "top",
      padding: 8,
    },
  ],
};

const workflowsChapter: TourChapter = {
  id: "workflows",
  titleKey: "onboarding.tour.workflows.title",
  icon: "Workflow",
  route: "/manage/workflows",
  steps: [
    {
      target: '[data-testid="create-workflow-btn"]',
      titleKey: "onboarding.tour.workflows.step1Title",
      descriptionKey: "onboarding.tour.workflows.step1Desc",
      placement: "bottom",
    },
    {
      target: '[data-tour="workflows-content"]',
      titleKey: "onboarding.tour.workflows.step2Title",
      descriptionKey: "onboarding.tour.workflows.step2Desc",
      placement: "top",
      padding: 8,
    },
    {
      target: '[data-tour="workflows-search"]',
      titleKey: "onboarding.tour.workflows.step3Title",
      descriptionKey: "onboarding.tour.workflows.step3Desc",
      placement: "bottom",
    },
  ],
};

const chatChapter: TourChapter = {
  id: "chat",
  titleKey: "onboarding.tour.chat.title",
  icon: "MessageCircle",
  route: "/manage/chat",
  steps: [
    {
      target: '[data-testid="agent-selector"]',
      titleKey: "onboarding.tour.chat.step1Title",
      descriptionKey: "onboarding.tour.chat.step1Desc",
      placement: "bottom",
    },
    {
      target: '[data-testid="streaming-toggle"]',
      titleKey: "onboarding.tour.chat.step2Title",
      descriptionKey: "onboarding.tour.chat.step2Desc",
      placement: "bottom",
    },
    {
      target: '[data-testid="history-toggle"]',
      titleKey: "onboarding.tour.chat.step3Title",
      descriptionKey: "onboarding.tour.chat.step3Desc",
      placement: "bottom",
    },
    {
      target: '[data-tour="chat-input-area"]',
      titleKey: "onboarding.tour.chat.step4Title",
      descriptionKey: "onboarding.tour.chat.step4Desc",
      placement: "top",
    },
  ],
};

const resourcesChapter: TourChapter = {
  id: "resources",
  titleKey: "onboarding.tour.resources.title",
  icon: "FileCode",
  route: "/manage/resources",
  steps: [
    {
      target: '[data-testid="resource-types-grid"]',
      titleKey: "onboarding.tour.resources.step1Title",
      descriptionKey: "onboarding.tour.resources.step1Desc",
      placement: "bottom",
      padding: 8,
    },
    {
      target: '[data-testid="resource-type-llm"]',
      titleKey: "onboarding.tour.resources.step2Title",
      descriptionKey: "onboarding.tour.resources.step2Desc",
      placement: "bottom",
    },
    {
      target: '[data-testid="resource-type-rules"]',
      titleKey: "onboarding.tour.resources.step3Title",
      descriptionKey: "onboarding.tour.resources.step3Desc",
      placement: "right",
    },
  ],
};

const conversationsChapter: TourChapter = {
  id: "conversations",
  titleKey: "onboarding.tour.conversations.title",
  icon: "MessagesSquare",
  route: "/manage/conversations",
  steps: [
    {
      target: '[data-testid="conversation-search"]',
      titleKey: "onboarding.tour.conversations.step1Title",
      descriptionKey: "onboarding.tour.conversations.step1Desc",
      placement: "bottom",
    },
    {
      target: '[data-tour="conversations-filters"]',
      titleKey: "onboarding.tour.conversations.step2Title",
      descriptionKey: "onboarding.tour.conversations.step2Desc",
      placement: "bottom",
    },
    {
      target: '[data-tour="conversations-content"]',
      titleKey: "onboarding.tour.conversations.step3Title",
      descriptionKey: "onboarding.tour.conversations.step3Desc",
      placement: "top",
      padding: 8,
    },
  ],
};

const groupsChapter: TourChapter = {
  id: "groups",
  titleKey: "onboarding.tour.groups.title",
  icon: "Boxes",
  route: "/manage/groups",
  steps: [
    {
      target: '[data-testid="create-group-btn"]',
      titleKey: "onboarding.tour.groups.step1Title",
      descriptionKey: "onboarding.tour.groups.step1Desc",
      placement: "bottom",
    },
    {
      target: '[data-testid="group-search"]',
      titleKey: "onboarding.tour.groups.step2Title",
      descriptionKey: "onboarding.tour.groups.step2Desc",
      placement: "bottom",
    },
    {
      target: '[data-tour="groups-content"]',
      titleKey: "onboarding.tour.groups.step3Title",
      descriptionKey: "onboarding.tour.groups.step3Desc",
      placement: "top",
      padding: 8,
    },
  ],
};

const logsChapter: TourChapter = {
  id: "logs",
  titleKey: "onboarding.tour.logs.title",
  icon: "ScrollText",
  route: "/manage/logs",
  steps: [
    {
      target: '[data-tour="logs-tabs"]',
      titleKey: "onboarding.tour.logs.step1Title",
      descriptionKey: "onboarding.tour.logs.step1Desc",
      placement: "bottom",
    },
    {
      target: '[data-tour="logs-filters"]',
      titleKey: "onboarding.tour.logs.step2Title",
      descriptionKey: "onboarding.tour.logs.step2Desc",
      placement: "bottom",
    },
    {
      target: '[data-testid="export-logs-btn"]',
      titleKey: "onboarding.tour.logs.step3Title",
      descriptionKey: "onboarding.tour.logs.step3Desc",
      placement: "bottom",
    },
  ],
};

const secretsChapter: TourChapter = {
  id: "secrets",
  titleKey: "onboarding.tour.secrets.title",
  icon: "KeyRound",
  route: "/manage/secrets",
  steps: [
    {
      target: '[data-testid="create-secret-button"]',
      titleKey: "onboarding.tour.secrets.step1Title",
      descriptionKey: "onboarding.tour.secrets.step1Desc",
      placement: "bottom",
    },
    {
      target: '[data-testid="tenant-input"]',
      titleKey: "onboarding.tour.secrets.step2Title",
      descriptionKey: "onboarding.tour.secrets.step2Desc",
      placement: "bottom",
    },
    {
      target: '[data-tour="secrets-info"]',
      titleKey: "onboarding.tour.secrets.step3Title",
      descriptionKey: "onboarding.tour.secrets.step3Desc",
      placement: "bottom",
      padding: 4,
    },
  ],
};

const auditChapter: TourChapter = {
  id: "audit",
  titleKey: "onboarding.tour.audit.title",
  icon: "ShieldCheck",
  route: "/manage/audit",
  steps: [
    {
      target: '[data-tour="audit-mode-toggle"]',
      titleKey: "onboarding.tour.audit.step1Title",
      descriptionKey: "onboarding.tour.audit.step1Desc",
      placement: "bottom",
    },
    {
      target: '[data-testid="search-button"]',
      titleKey: "onboarding.tour.audit.step2Title",
      descriptionKey: "onboarding.tour.audit.step2Desc",
      placement: "bottom",
    },
    {
      target: '[data-testid="export-btn"]',
      titleKey: "onboarding.tour.audit.step3Title",
      descriptionKey: "onboarding.tour.audit.step3Desc",
      placement: "bottom",
    },
  ],
};

const schedulesChapter: TourChapter = {
  id: "schedules",
  titleKey: "onboarding.tour.schedules.title",
  icon: "CalendarClock",
  route: "/manage/schedules",
  steps: [
    {
      target: '[data-testid="create-schedule-btn"]',
      titleKey: "onboarding.tour.schedules.step1Title",
      descriptionKey: "onboarding.tour.schedules.step1Desc",
      placement: "bottom",
    },
    {
      target: '[data-tour="schedules-stats"]',
      titleKey: "onboarding.tour.schedules.step2Title",
      descriptionKey: "onboarding.tour.schedules.step2Desc",
      placement: "bottom",
      padding: 8,
    },
    {
      target: '[data-testid="schedules-table-container"]',
      titleKey: "onboarding.tour.schedules.step3Title",
      descriptionKey: "onboarding.tour.schedules.step3Desc",
      placement: "top",
      padding: 8,
    },
  ],
};

const quotasChapter: TourChapter = {
  id: "quotas",
  titleKey: "onboarding.tour.quotas.title",
  icon: "SlidersHorizontal",
  route: "/manage/quotas",
  steps: [
    {
      target: '[data-tour="quotas-config"]',
      titleKey: "onboarding.tour.quotas.step1Title",
      descriptionKey: "onboarding.tour.quotas.step1Desc",
      placement: "right",
      padding: 8,
    },
    {
      target: '[data-tour="quotas-usage"]',
      titleKey: "onboarding.tour.quotas.step2Title",
      descriptionKey: "onboarding.tour.quotas.step2Desc",
      placement: "left",
      padding: 8,
    },
    {
      target: '[data-testid="quotas-save"]',
      titleKey: "onboarding.tour.quotas.step3Title",
      descriptionKey: "onboarding.tour.quotas.step3Desc",
      placement: "bottom",
    },
  ],
};

const coordinatorChapter: TourChapter = {
  id: "coordinator",
  titleKey: "onboarding.tour.coordinator.title",
  icon: "Activity",
  route: "/manage/coordinator",
  steps: [
    {
      target: '[data-testid="coordinator-connection-card"]',
      titleKey: "onboarding.tour.coordinator.step1Title",
      descriptionKey: "onboarding.tour.coordinator.step1Desc",
      placement: "bottom",
      padding: 8,
    },
    {
      target: '[data-testid="coordinator-queues"]',
      titleKey: "onboarding.tour.coordinator.step2Title",
      descriptionKey: "onboarding.tour.coordinator.step2Desc",
      placement: "top",
      padding: 8,
    },
    {
      target: '[data-testid="coordinator-dead-letters"]',
      titleKey: "onboarding.tour.coordinator.step3Title",
      descriptionKey: "onboarding.tour.coordinator.step3Desc",
      placement: "top",
      padding: 8,
    },
  ],
};

const orphansChapter: TourChapter = {
  id: "orphans",
  titleKey: "onboarding.tour.orphans.title",
  icon: "Link2Off",
  route: "/manage/orphans",
  steps: [
    {
      target: '[data-testid="scan-button"]',
      titleKey: "onboarding.tour.orphans.step1Title",
      descriptionKey: "onboarding.tour.orphans.step1Desc",
      placement: "bottom",
    },
    {
      target: '[data-testid="include-deleted-checkbox"]',
      titleKey: "onboarding.tour.orphans.step2Title",
      descriptionKey: "onboarding.tour.orphans.step2Desc",
      placement: "bottom",
    },
    {
      target: '[data-testid="pre-scan-state"]',
      titleKey: "onboarding.tour.orphans.step3Title",
      descriptionKey: "onboarding.tour.orphans.step3Desc",
      placement: "top",
      padding: 8,
    },
  ],
};

/* ================================================================
   Export all chapters as a map + ordered list
   ================================================================ */
export const TOUR_CHAPTERS: Record<TourChapterId, TourChapter> = {
  dashboard: dashboardChapter,
  agents: agentsChapter,
  workflows: workflowsChapter,
  chat: chatChapter,
  resources: resourcesChapter,
  conversations: conversationsChapter,
  groups: groupsChapter,
  logs: logsChapter,
  secrets: secretsChapter,
  audit: auditChapter,
  schedules: schedulesChapter,
  quotas: quotasChapter,
  coordinator: coordinatorChapter,
  orphans: orphansChapter,
};

export const TOUR_CHAPTER_ORDER: TourChapterId[] = [
  "dashboard",
  "agents",
  "workflows",
  "chat",
  "resources",
  "conversations",
  "groups",
  "logs",
  "secrets",
  "audit",
  "schedules",
  "quotas",
  "coordinator",
  "orphans",
];

