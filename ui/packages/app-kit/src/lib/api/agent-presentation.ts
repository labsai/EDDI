import type { ConversationControls } from "@eddi/core";

/*
 * An agent's presentation as the profile and the catalog serve it (B4). Its own
 * module so that agents.ts (the profile) and catalog.ts (the catalog) can both
 * use it without importing each other through groups.ts.
 */

/** `AppearanceProfile` of the profile and the catalog: validated when the agent was saved. */
export interface AgentAppearanceProfile {
  /** The name to show, or null for the agent's name. */
  displayName?: string | null;
  /** An https URL or a data:image URI, or null. */
  avatar?: string | null;
  /** `#rgb` or `#rrggbb`, or null for the theme's. */
  accentColor?: string | null;
  /** Conversation starters, in order. */
  starters?: string[];
}

/**
 * `ControlsProfile`: which chat controls to offer, every default applied —
 * true unless the agent switched it off, except `rawHtml` (false unless on).
 * The rule lives in @eddi/core (`parseConversationControls`), shared with the
 * Chat UI.
 */
export type ConversationControlsProfile = ConversationControls;
