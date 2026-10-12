import { useTranslation } from "react-i18next";
import { cn, getInitials } from "@/lib/utils";

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
        "flex flex-col items-center gap-1.5 rounded-xl border border-border p-3",
        "bg-card hover:bg-muted/40 transition-all duration-200",
        "hover:shadow-lg hover:-translate-y-1 br-card-premium",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        "text-center group",
        className,
      )}
      title={description ?? name}
    >
      {/* Avatar — color-hashed by agentId */}
      <div
        className="flex h-9 w-9 items-center justify-center rounded-full text-xs font-semibold transition-transform duration-200 group-hover:scale-105"
        style={{
          backgroundColor: `hsl(${[...agentId].reduce((a, c) => a + c.charCodeAt(0), 0) % 360}, 40%, 25%)`,
          color: `hsl(${[...agentId].reduce((a, c) => a + c.charCodeAt(0), 0) % 360}, 50%, 75%)`,
        }}
        aria-hidden
      >
        {getInitials(name)}
      </div>

      {/* Name */}
      <p className="w-full text-sm font-medium text-foreground truncate">{name}</p>

      {/* Description or status */}
      {description ? (
        <p className="w-full text-[10px] text-muted-foreground truncate">{description}</p>
      ) : (
        <span className="flex items-center gap-1 text-[10px] text-muted-foreground">
          <span className="inline-block h-1.5 w-1.5 rounded-full bg-primary/60" />
          {t("workforce.ready", "Ready")}
        </span>
      )}
    </button>
  );
}
