/* ──────────────────────────────────────────────
   @eddi/conversation — controllers and components for every kind of EDDI
   conversation (frontend unification plan §3.2). Router-free: hosts pass ids
   and callbacks. Never reads window.__EDDI_*__. Consumed as source through the
   npm workspace link; there is no build step. See README.md.
   ────────────────────────────────────────────── */

export * from "./controller/state";
export * from "./controller/transport";
export * from "./controller/agent-conversation";
export * from "./controller/notices";
export * from "./controller/composer-mode";
export * from "./i18n/text";
export * from "./react/hooks";
export * from "./react/context";
export { MarkdownContent, CopyButton, type MarkdownContentProps } from "./markdown/Markdown";
export { looksLikeCode, looksLikeHtml, looksLikeMath } from "./markdown/markdown-plugins";
export { Message, MessageAttachments, GuardrailNotes, type MessageProps } from "./components/Message";
export { MessageList, type MessageListProps } from "./components/MessageList";
export { Composer, discardAttachment, DISCARD_RETRY_MS, type ComposerProps } from "./components/Composer";
export { SecretInput } from "./components/SecretInput";
export { QuickReplies } from "./components/QuickReplies";
export { ThinkingIndicator, TypingIndicator, indicatorStatusText, guardrailActive } from "./components/ActivityIndicator";
export { PausedCard } from "./components/PausedCard";
export { RatingCard } from "./components/RatingCard";
export { StepRating } from "./components/StepRating";
export { ScrollToBottom } from "./components/ScrollToBottom";
export { ReplyAnnouncer, nextAnnouncement, speakable, type AnnouncedMessage, type ReplyAnnouncerProps } from "./components/ReplyAnnouncer";
export { isImeComposing } from "./components/ime";
