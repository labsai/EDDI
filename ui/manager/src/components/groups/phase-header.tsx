import { useState } from "react";
import { ChevronDown, ChevronRight, Users, Zap } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { PhaseType, TurnOrder } from "@/lib/api/groups";

interface PhaseHeaderProps {
  name: string;
  type: PhaseType;
  turnOrder?: TurnOrder;
  entryCount: number;
  isActive?: boolean;
  defaultExpanded?: boolean;
  children: React.ReactNode;
}

const PHASE_ICONS: Record<PhaseType, string> = {
  OPINION: "💬",
  CRITIQUE: "🔍",
  REVISION: "✏️",
  CHALLENGE: "⚔️",
  DEFENSE: "🛡️",
  ARGUE: "📢",
  REBUTTAL: "↩️",
  SYNTHESIS: "⭐",
  PLAN: "📋",
  EXECUTE: "⚡",
  VERIFY: "✅",
};

export function PhaseHeader({
  name,
  type,
  turnOrder,
  entryCount,
  isActive,
  defaultExpanded = true,
  children,
}: PhaseHeaderProps) {
  const [expanded, setExpanded] = useState(defaultExpanded);
  const icon = PHASE_ICONS[type] || "📋";

  return (
    <div
      className={cn(
        "rounded-xl border",
        type === "SYNTHESIS"
          ? "border-primary/30 bg-primary/5"
          : "border-border bg-card",
        isActive && "ring-2 ring-primary/30"
      )}
      data-testid={`phase-section-${name.replace(/\s+/g, "-").toLowerCase()}`}
    >
      {/* Header */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center gap-3 p-3 text-start transition-colors hover:bg-secondary/30 rounded-t-xl"
      >
        {expanded ? (
          <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
        ) : (
          <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
        )}

        <span className="text-base">{icon}</span>

        <span className="text-sm font-semibold text-foreground flex-1">
          {name}
        </span>

        <div className="flex items-center gap-2">
          {turnOrder === "PARALLEL" && (
            <Badge variant="outline" className="text-[10px] px-1.5 py-0">
              <Zap className="me-0.5 h-2.5 w-2.5" /> Parallel
            </Badge>
          )}
          <Badge variant="secondary" className="text-[10px] px-1.5 py-0">
            <Users className="me-0.5 h-2.5 w-2.5" /> {entryCount}
          </Badge>
        </div>
      </button>

      {/* Entries */}
      {expanded && (
        <div className="border-t border-border p-2 space-y-1">
          {children}
        </div>
      )}
    </div>
  );
}
