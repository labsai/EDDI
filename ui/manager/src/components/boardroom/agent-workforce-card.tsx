import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { hashColor, getInitials } from "@/lib/utils";
import { Bot } from "lucide-react";

// ─── Types ───────────────────────────────────────────────────────

export interface AgentWorkforceCardProps {
  /** Agent display name */
  name: string;
  /** Agent ID (for avatar color hashing) */
  agentId: string;
  /** Agent description / role */
  description?: string | null;
  /** Click handler — navigates to 1:1 chat or placeholder */
  onClick?: () => void;
  className?: string;
}

// ─── Component ───────────────────────────────────────────────────

export function AgentWorkforceCard({
  name,
  agentId,
  description,
  onClick,
  className,
}: AgentWorkforceCardProps) {
  const { t } = useTranslation();

  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "flex flex-col items-center gap-2 rounded-xl border border-border p-4 min-w-[140px]",
        "bg-card hover:bg-muted/50 transition-all duration-150",
        "hover:shadow-md hover:-translate-y-0.5",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        "text-center",
        className,
      )}
      title={description ?? name}
    >
      {/* Avatar */}
      <div
        className={cn(
          "flex h-12 w-12 items-center justify-center rounded-full text-sm font-semibold text-white",
          hashColor(agentId),
        )}
        aria-hidden
      >
        {getInitials(name)}
      </div>

      {/* Name */}
      <div className="min-w-0 w-full">
        <p className="text-sm font-medium text-foreground truncate">{name}</p>
        {description ? (
          <p className="text-xs text-muted-foreground mt-0.5 line-clamp-1">
            {description}
          </p>
        ) : (
          <p className="text-xs text-muted-foreground/50 mt-0.5 flex items-center justify-center gap-1">
            <Bot className="h-3 w-3" />
            {t("workforce.agent", "Digital Expert")}
          </p>
        )}
      </div>

      {/* Status dot */}
      <span className="flex items-center gap-1 text-[10px] text-emerald-600 dark:text-emerald-400">
        <span className="inline-block h-1.5 w-1.5 rounded-full bg-emerald-500" />
        {t("workforce.ready", "Ready")}
      </span>
    </button>
  );
}

// ─── "Add Agent" Placeholder Card ────────────────────────────────

export interface AddAgentCardProps {
  onClick?: () => void;
  className?: string;
}

export function AddAgentCard({ onClick, className }: AddAgentCardProps) {
  const { t } = useTranslation();

  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "flex flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed border-border p-4 min-w-[140px]",
        "text-muted-foreground hover:text-primary hover:border-primary/50 hover:bg-primary/5",
        "transition-all duration-150",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        className,
      )}
    >
      <div className="flex h-12 w-12 items-center justify-center rounded-full border-2 border-dashed border-current">
        <span className="text-xl">+</span>
      </div>
      <p className="text-xs font-medium">
        {t("workforce.addAgent", "Deploy Agent")}
      </p>
    </button>
  );
}
