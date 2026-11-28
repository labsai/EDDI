// @eddi/ui — tokens, theme, i18n setup and the primitives both apps share.
// No API calls, no app import, no window.__EDDI_*__ (eslint.config.js; the
// boundaries test in @eddi/core checks the same). The Tailwind tokens are a
// stylesheet: `@import "@eddi/ui/theme.css";`.

export { CHAT_TOKEN_ALIASES, SEMANTIC_TOKENS, type ChatAliasName, type SemanticToken } from "./chat-aliases";

export {
  applyTheme,
  getSystemTheme,
  isThemeMode,
  nextTheme,
  readStoredTheme,
  resolveTheme,
  storeTheme,
  subscribeSystemTheme,
  type ResolvedTheme,
  type ThemeAttribute,
  type ThemeMode,
} from "./theme/theme";
export { ThemeProvider, type ThemeProviderProps } from "./theme/theme-provider";
export { useTheme, type ThemeState } from "./theme/theme-context";

export {
  SUPPORTED_LANGUAGES,
  RTL_LANGUAGES,
  isRtlLanguage,
  matchLanguage,
  type SupportedLanguage,
} from "./i18n/languages";
export {
  createLazyLocaleBackend,
  renderedLanguage,
  setDefaultVariables,
  setupI18n,
  syncDocumentLanguage,
  type DefaultVariables,
  type I18nSetupOptions,
  type LocaleLoader,
} from "./i18n/setup";
// Per-locale fonts are a separate entry, "@eddi/ui/fonts": the loader map names
// seven font packages, and a bundler emits their chunks for any app that imports
// the module, used or not. The Chat UI keeps its own fonts and never imports it.

export { cn } from "./lib/cn";
export { formatDuration, formatUsd, getInitials, hashColor } from "./lib/format";
export { Button, buttonVariants, type ButtonProps } from "./primitives/button";
export { ThemedToaster, type ThemedToasterProps } from "./primitives/toaster";
export { Badge, badgeVariants, type BadgeProps } from "./primitives/badge";
