import type { TFunction } from "i18next";
import { DISCUSSION_STYLES, STYLE_INFO, type DiscussionStyle } from "./api/groups";

/**
 * Localized display info for a discussion style.
 *
 * `STYLE_INFO` holds the icons and the English fallbacks; this module is the
 * display path. Before it existed every surface read `STYLE_INFO` directly, so
 * the style name and its one-line flow stayed English in all eleven locales
 * while the labels around them translated.
 */
export interface StyleInfo {
  label: string;
  flow: string;
  icon: string;
}

/** i18n key for a style's name, so every surface labels it the same way. */
export function styleLabelKey(style: DiscussionStyle | string): string {
  return `groups.styles.${style}.label`;
}

/** i18n key for a style's one-line description of how the discussion runs. */
export function styleFlowKey(style: DiscussionStyle | string): string {
  return `groups.styles.${style}.flow`;
}

/**
 * Localized info for one style, or `undefined` for a style this build does not
 * know (the backend enum can grow ahead of the UI).
 */
export function styleInfo(
  style: DiscussionStyle | string,
  t?: TFunction,
): StyleInfo | undefined {
  const fallback = STYLE_INFO[style as DiscussionStyle];
  if (!fallback) return undefined;
  if (!t) return fallback;
  return {
    icon: fallback.icon,
    label: t(styleLabelKey(style), fallback.label),
    flow: t(styleFlowKey(style), fallback.flow),
  };
}

/** Localized name for a style; falls back to the raw enum for unknown values. */
export function styleLabel(style: DiscussionStyle | string, t?: TFunction): string {
  return styleInfo(style, t)?.label ?? String(style);
}

/**
 * The whole `STYLE_INFO` record, localized — for the many call sites that index
 * it by a style resolved at render time.
 */
export function getStyleInfo(t?: TFunction): Record<DiscussionStyle, StyleInfo> {
  return Object.fromEntries(
    DISCUSSION_STYLES.map((s) => [s, styleInfo(s, t)!]),
  ) as Record<DiscussionStyle, StyleInfo>;
}

/**
 * Display info that never comes back empty — for pickers that may offer a
 * backend-only style this build has no localized entry for. `backendLabel` is
 * the label the styles endpoint reported for it, when one exists.
 */
export function styleDisplay(
  style: DiscussionStyle | string,
  t?: TFunction,
  backendLabel?: string,
): StyleInfo {
  return (
    styleInfo(style, t) ?? {
      label: backendLabel ?? String(style),
      flow: "",
      icon: "💬",
    }
  );
}
