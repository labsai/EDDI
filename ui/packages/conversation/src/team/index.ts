/* ──────────────────────────────────────────────
   @eddi/conversation/team — the team kind of conversation (frontend
   unification plan §3.2): the GroupDiscussion controller and the team
   components the Manager's group workspace and Workforce's board and
   history render. A separate entry from the package's main one, so the
   Chat UI (agent kind only) never resolves it. Router-free like the rest of
   the package: hosts pass ids and callbacks. Its strings are i18next keys,
   read through the app's react-i18next (`withTeamLocales` merges them in).
   ────────────────────────────────────────────── */

export * from "../controller/group-discussion";
export * from "./i18n";

// What a transcript entry says, read once for every surface.
export * from "./group-utils";
export * from "./group-payloads";
export * from "./group-entry-body";
export * from "./group-decision";
export * from "./decision-summary";
export * from "./artifact-version-list";
export * from "./discussion-styles";
export * from "./duration";
export * from "./use-discussion-digest";

// The team components.
export * from "./structured-turn-card";
export * from "./structured-entry-body";
export * from "./decision-record-card";
export * from "./task-board";
export * from "./negotiation-ledger";
export * from "./artifacts-panel";
export * from "./artifact-content";
export * from "./artifact-versions";
export * from "./discussion-insights";
export * from "./human-turn-banner";

// The overview: phase rail, roster, participation, interactions, bids.
export * from "./overview/phase-rail";
export * from "./overview/member-roster";
export * from "./overview/participation-matrix";
export * from "./overview/interaction-map";
export * from "./overview/bid-board";
export * from "./overview/discussion-overview";
export * from "./overview/discussion-view-toggle";
export * from "./overview/discussion-view-mode";
export * from "./overview/style-recipe";
