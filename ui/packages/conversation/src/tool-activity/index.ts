/* ──────────────────────────────────────────────
   @eddi/conversation/tool-activity — what the tools of a turn did (T1.9) and
   the notice before Edit or Regenerate of such a turn (T1.6), from the
   stream's `tool_result` events (@eddi/core/tool-events, plan B6); and the
   turn notes (T2.7): a cascade escalation, a re-ask or a guardrail, in plain
   words, from `cascade_escalation`, `llm_retry` and `guardrail_triggered`.

   A separate entry, like `./team`: only the signed-in apps render it (Tailwind,
   react-i18next). The Chat UI widget shows no tool activity and is at its
   bundle budget, so it never resolves this entry.
   ────────────────────────────────────────────── */

export * from "./tool-activity-log";
export * from "./ToolActivity";
export * from "./turn-notes";
export * from "./TurnNotes";
export * from "./i18n";
