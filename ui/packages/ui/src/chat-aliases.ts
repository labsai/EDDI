/**
 * The Chat UI's `--chat-*` custom properties, as documented aliases of the
 * shared design tokens (frontend unification plan §3.3, D3).
 *
 * The widget's colour URL parameters (`?bgColor=`, `?accentColor=` …), its
 * operator settings and `/branding/brand.css` all write `--chat-*` names, and
 * embedders have them in their links. They stay. What changed in phase 3a is
 * where their DEFAULTS come from: an aliased `--chat-*` variable is declared as
 * `var(<token>)`, and the widget gives the token its own value — so an override
 * of the `--chat-*` name still wins (it is set inline on `<html>`, or later in
 * the cascade), and the widget renders exactly as before.
 *
 * A name absent from this table is widget-specific (the quick-reply tints, the
 * bubble colours, hover and placeholder shades) and keeps a literal default.
 * `ui/chat/src/styles/chat-aliases.test.ts` pins `variables.css` to this table,
 * and the values each name resolves to in both themes.
 *
 * Two aliases point at another `--chat-*` name instead of a token: the user
 * bubble IS the accent (`?accentColor=` already set both), and that is now said
 * once.
 */
export const CHAT_TOKEN_ALIASES = {
  "--chat-bg": "--color-background",
  "--chat-surface": "--color-card",
  "--chat-surface-raised": "--color-popover",
  "--chat-text": "--color-foreground",
  "--chat-text-muted": "--color-muted-foreground",
  "--chat-accent": "--color-primary",
  "--chat-agent-text": "--color-card-foreground",
  "--chat-user-bg": "--chat-accent",
  "--chat-input-border": "--color-input",
  "--chat-input-focus": "--color-ring",
  "--chat-input-text": "--color-foreground",
  "--chat-border": "--color-border",
  "--chat-warning": "--color-warning",
  "--chat-radius-sm": "--radius",
  "--chat-font": "--font-sans",
} as const satisfies Record<`--chat-${string}`, string>;

export type ChatAliasName = keyof typeof CHAT_TOKEN_ALIASES;

/**
 * The shared token names a non-Tailwind consumer may read. Every one is declared
 * by `styles/theme.css` (a test checks it); an app that does not use Tailwind
 * gives them values of its own.
 */
export const SEMANTIC_TOKENS = [
  "--color-background",
  "--color-foreground",
  "--color-card",
  "--color-card-foreground",
  "--color-popover",
  "--color-popover-foreground",
  "--color-primary",
  "--color-primary-foreground",
  "--color-secondary",
  "--color-secondary-foreground",
  "--color-muted",
  "--color-muted-foreground",
  "--color-accent",
  "--color-accent-foreground",
  "--color-destructive",
  "--color-destructive-foreground",
  "--color-warning",
  "--color-warning-foreground",
  "--color-border",
  "--color-input",
  "--color-ring",
  "--radius",
  "--font-sans",
] as const;

export type SemanticToken = (typeof SEMANTIC_TOKENS)[number];
