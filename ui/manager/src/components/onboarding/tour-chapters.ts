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
      target: '[data-testid="agent-wizard-btn"]',
      titleKey: "onboarding.tour.agents.step1Title",
      descriptionKey: "onboarding.tour.agents.step1Desc",
      placement: "bottom",
    },
    {
      target: '[data-testid="create-agent-btn"]',
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

/* ================================================================
   Export all chapters as a map + ordered list
   ================================================================ */
export const TOUR_CHAPTERS: Record<TourChapterId, TourChapter> = {
  dashboard: dashboardChapter,
  agents: agentsChapter,
  workflows: workflowsChapter,
  chat: chatChapter,
  resources: resourcesChapter,
};

export const TOUR_CHAPTER_ORDER: TourChapterId[] = [
  "dashboard",
  "agents",
  "workflows",
  "chat",
  "resources",
];
