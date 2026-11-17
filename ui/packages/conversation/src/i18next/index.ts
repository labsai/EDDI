/* ──────────────────────────────────────────────
   @eddi/conversation/i18next — the package's components bound to an app
   that runs react-i18next (the Manager and Workforce): the one markdown
   renderer with the app's translator handed to the shared components. The
   Chat UI passes its own translator (`ConversationTextProvider`) and never
   resolves this entry.
   ────────────────────────────────────────────── */

export * from "./conversation-markdown";
