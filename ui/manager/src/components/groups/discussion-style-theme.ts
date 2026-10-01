import type { DiscussionStyle } from "@/lib/api/groups";

// Its own module rather than an export of discussion-transcript.tsx: a
// component file that also exports a constant breaks React Fast Refresh for
// it (react-refresh/only-export-components), and group-detail needs the
// constant too.
/** Style-aware accent colors for transcript theming */
export const STYLE_THEME: Record<DiscussionStyle, {
  accent: string;
  dotColor: string;
  phaseAccent: string;
  questionBg: string;
  flowBg: string;
  flowText: string;
  progressBg: string;
  progressText: string;
  progressBorder: string;
}> = {
  ROUND_TABLE: {
    accent: "text-amber-500",
    dotColor: "bg-amber-500",
    phaseAccent: "border-amber-500/30 bg-amber-500/5",
    questionBg: "bg-amber-500/5 border-b-amber-500/20",
    flowBg: "bg-amber-500/10",
    flowText: "text-amber-600 dark:text-amber-400",
    progressBg: "bg-amber-500/5",
    progressText: "text-amber-600 dark:text-amber-400",
    progressBorder: "border-amber-500/20",
  },
  PEER_REVIEW: {
    accent: "text-teal-500",
    dotColor: "bg-teal-500",
    phaseAccent: "border-teal-500/30 bg-teal-500/5",
    questionBg: "bg-teal-500/5 border-b-teal-500/20",
    flowBg: "bg-teal-500/10",
    flowText: "text-teal-600 dark:text-teal-400",
    progressBg: "bg-teal-500/5",
    progressText: "text-teal-600 dark:text-teal-400",
    progressBorder: "border-teal-500/20",
  },
  DEVIL_ADVOCATE: {
    accent: "text-rose-500",
    dotColor: "bg-rose-500",
    phaseAccent: "border-rose-500/30 bg-rose-500/5",
    questionBg: "bg-rose-500/5 border-b-rose-500/20",
    flowBg: "bg-rose-500/10",
    flowText: "text-rose-600 dark:text-rose-400",
    progressBg: "bg-rose-500/5",
    progressText: "text-rose-600 dark:text-rose-400",
    progressBorder: "border-rose-500/20",
  },
  DELPHI: {
    accent: "text-violet-500",
    dotColor: "bg-violet-500",
    phaseAccent: "border-violet-500/30 bg-violet-500/5",
    questionBg: "bg-violet-500/5 border-b-violet-500/20",
    flowBg: "bg-violet-500/10",
    flowText: "text-violet-600 dark:text-violet-400",
    progressBg: "bg-violet-500/5",
    progressText: "text-violet-600 dark:text-violet-400",
    progressBorder: "border-violet-500/20",
  },
  DEBATE: {
    accent: "text-indigo-500",
    dotColor: "bg-indigo-500",
    phaseAccent: "border-indigo-500/30 bg-indigo-500/5",
    questionBg: "bg-indigo-500/5 border-b-indigo-500/20",
    flowBg: "bg-indigo-500/10",
    flowText: "text-indigo-600 dark:text-indigo-400",
    progressBg: "bg-indigo-500/5",
    progressText: "text-indigo-600 dark:text-indigo-400",
    progressBorder: "border-indigo-500/20",
  },
  TASK_FORCE: {
    accent: "text-orange-500",
    dotColor: "bg-orange-500",
    phaseAccent: "border-orange-500/30 bg-orange-500/5",
    questionBg: "bg-orange-500/5 border-b-orange-500/20",
    flowBg: "bg-orange-500/10",
    flowText: "text-orange-600 dark:text-orange-400",
    progressBg: "bg-orange-500/5",
    progressText: "text-orange-600 dark:text-orange-400",
    progressBorder: "border-orange-500/20",
  },
  NEGOTIATION: {
    accent: "text-emerald-500",
    dotColor: "bg-emerald-500",
    phaseAccent: "border-emerald-500/30 bg-emerald-500/5",
    questionBg: "bg-emerald-500/5 border-b-emerald-500/20",
    flowBg: "bg-emerald-500/10",
    flowText: "text-emerald-600 dark:text-emerald-400",
    progressBg: "bg-emerald-500/5",
    progressText: "text-emerald-600 dark:text-emerald-400",
    progressBorder: "border-emerald-500/20",
  },
  CUSTOM: {
    accent: "text-primary",
    dotColor: "bg-primary",
    phaseAccent: "border-primary/30 bg-primary/5",
    questionBg: "bg-card/50",
    flowBg: "bg-primary/10",
    flowText: "text-primary",
    progressBg: "bg-primary/5",
    progressText: "text-primary",
    progressBorder: "border-primary/20",
  },
};
