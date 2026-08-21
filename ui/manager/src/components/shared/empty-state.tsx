import type { LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";

interface EmptyStateProps {
  icon: LucideIcon;
  title: string;
  description?: string;
  actionLabel?: string;
  onAction?: () => void;
}

export function EmptyState({
  icon: Icon,
  title,
  description,
  actionLabel,
  onAction,
}: EmptyStateProps) {
  return (
    // An empty state is a real rendered state, not the absence of one — on the
    // conversation-monitoring screen it is what "the page loaded" *looks like*
    // until an agent is picked. Without a testid there was nothing to assert on
    // there, which is what AGENTS.md §247 asks for.
    <div
      data-testid="empty-state"
      className="flex flex-col items-center justify-center rounded-xl border-2 border-dashed border-border py-16"
    >
      <Icon className="h-12 w-12 text-muted-foreground/50" />
      <p className="mt-4 text-lg font-medium text-muted-foreground">{title}</p>
      {description && (
        <p className="mt-1 text-sm text-muted-foreground/70">{description}</p>
      )}
      {actionLabel && onAction && (
        <Button className="mt-4" size="md" onClick={onAction}>
          {actionLabel}
        </Button>
      )}
    </div>
  );
}
