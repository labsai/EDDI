/* ──────────────────────────────────────────────
   Small display helpers the signed-in apps and @eddi/conversation's team
   components share: avatar colours and initials, a duration, a USD amount.
   Moved here from @eddi/app-kit's lib/utils.ts (which re-exports them) in
   phase 6 of the frontend unification plan.
   ────────────────────────────────────────────── */

const AVATAR_COLORS = [
  "bg-blue-500",
  "bg-emerald-500",
  "bg-ochre-500",
  "bg-purple-500",
  "bg-rose-500",
  "bg-cyan-500",
  "bg-indigo-500",
  "bg-orange-500",
  "bg-teal-500",
  "bg-pink-500",
  "bg-lime-500",
  "bg-violet-500",
];

/** Deterministic Tailwind bg-color class from a string hash (for avatars) */
/**
 * Tolerates a missing value on purpose: EDDI writes engine-authored transcript
 * entries (a CONVERGENCE check) with no `speakerAgentId` at all, and reading
 * `.length` of that crashed the whole group transcript.
 */
export function hashColor(str: string | null | undefined): string {
  str = str ?? "";
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash);
  }
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length]!;
}

/** Extract up to 2 initials from a display name */
export function getInitials(name: string | null | undefined): string {
  return (name ?? "")
    .split(/\s+/)
    .map((w) => w[0])
    .filter(Boolean)
    .slice(0, 2)
    .join("")
    .toUpperCase();
}

/** Format a duration in milliseconds to a human-readable string */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 1) return "<1ms";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

/**
 * Format a USD amount for display.
 *
 * Locale-aware on purpose: the app ships 11 locales, and `de`, `fr`, `ar` and
 * `hi` disagree with en-US about both the decimal separator and where the
 * currency symbol goes — so a hardcoded `` `$${n.toFixed(2)}` `` is wrong in
 * eight of them.
 *
 * The locale is read off `<html lang>` rather than by importing the i18next
 * instance. `i18n/config` sets that attribute on init and on every
 * `languageChanged`, so this still follows an in-app language switch — but this
 * module stays a leaf. Importing `@/i18n/config` here would make every consumer
 * of `cn()` run the i18n bootstrap, which touches `document` at module scope,
 * and would invert the dependency direction of the app's most-imported utility.
 *
 * Sub-cent amounts get four digits: model-call costs are routinely $0.0003, and
 * two digits would render every one of them as "$0.00".
 *
 * This is the single formatter for every cost surface — the debugger's cost
 * dashboard, the pipeline trace, the audit page and the group transcript each
 * had their own copy, identical apart from how they handled zero (which is a
 * call-site concern, so it stays at the call sites).
 */
export function formatUsd(value: number): string {
  const digits = value !== 0 && Math.abs(value) < 0.01 ? 4 : 2;
  return new Intl.NumberFormat(activeLocale(), {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(value);
}

/**
 * The app's current language, or `undefined` to let `Intl` fall back to the
 * runtime default. Guarded for a non-DOM environment so this module keeps
 * working outside the browser.
 */
function activeLocale(): string | undefined {
  if (typeof document === "undefined") return undefined;
  return document.documentElement.lang || undefined;
}
