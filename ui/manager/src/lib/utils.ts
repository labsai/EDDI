import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Format a timestamp into a human-friendly relative time string */
export function formatRelativeTime(timestamp: number): string {
  if (!timestamp || !Number.isFinite(timestamp)) return "—";
  const now = Date.now();
  const diff = now - timestamp;
  if (diff < 0 || !Number.isFinite(diff)) return "—";
  const seconds = Math.floor(diff / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);

  if (days > 0) return `${days}d ago`;
  if (hours > 0) return `${hours}h ago`;
  if (minutes > 0) return `${minutes}m ago`;
  return "just now";
}

/** Agent deployment status color configuration */
export const statusConfig: Record<
  string,
  { label: string; color: string; dot: string }
> = {
  READY: {
    label: "Deployed",
    color: "text-emerald-600 dark:text-emerald-400",
    dot: "bg-emerald-500",
  },
  IN_PROGRESS: {
    label: "Deploying",
    color: "text-amber-600 dark:text-amber-400",
    dot: "bg-amber-500",
  },
  ERROR: {
    label: "Error",
    color: "text-destructive",
    dot: "bg-destructive",
  },
  NOT_FOUND: {
    label: "Not deployed",
    color: "text-muted-foreground",
    dot: "bg-muted-foreground/50",
  },
};

const AVATAR_COLORS = [
  "bg-blue-500",
  "bg-emerald-500",
  "bg-amber-500",
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
export function hashColor(str: string): string {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash);
  }
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length]!;
}

/** Extract up to 2 initials from a display name */
export function getInitials(name: string): string {
  return name
    .split(/\s+/)
    .map((w) => w[0])
    .filter(Boolean)
    .slice(0, 2)
    .join("")
    .toUpperCase();
}

/** Lightweight URL validation — catches obvious non-URLs before hitting the server */
export function isValidUrl(s: string): boolean {
  try {
    const u = new URL(s);
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
  }
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
