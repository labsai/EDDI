// One class-name joiner for the Manager and the primitives it shares with the
// Chat UI (@eddi/ui).
// The formatting helpers the team components share live there too.
export { cn, formatDuration, formatUsd, getInitials, hashColor } from "@eddi/ui";

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
    color: "text-warn-600 dark:text-warn-400",
    dot: "bg-warn-500",
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

/** Lightweight URL validation — catches obvious non-URLs before hitting the server */
export function isValidUrl(s: string): boolean {
  try {
    const u = new URL(s);
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
  }
}
