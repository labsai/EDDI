import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Format a timestamp into a human-friendly relative time string */
export function formatRelativeTime(timestamp: number): string {
  const now = Date.now();
  const diff = now - timestamp;
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
