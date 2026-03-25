import { cn, hashColor, getInitials } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import type { TranscriptEntry, TranscriptEntryType } from "@/lib/api/groups";
import { ENTRY_TYPE_INFO } from "@/lib/api/groups";

interface AgentResponseCardProps {
  entry: TranscriptEntry;
  className?: string;
}
function badgeVariant(
  type: TranscriptEntryType
): "default" | "secondary" | "success" | "warning" | "destructive" | "outline" {
  switch (type) {
    case "SYNTHESIS":
      return "default";
    case "ERROR":
      return "destructive";
    case "SKIPPED":
      return "secondary";
    case "CRITIQUE":
    case "CHALLENGE":
      return "warning";
    case "OPINION":
    case "REVISION":
    case "DEFENSE":
      return "success";
    default:
      return "outline";
  }
}

export function AgentResponseCard({ entry, className }: AgentResponseCardProps) {
  const info = ENTRY_TYPE_INFO[entry.type];
  const isUser = entry.speakerAgentId === "user";
  const isSynthesis = entry.type === "SYNTHESIS";
  const isError = entry.type === "ERROR" || entry.type === "SKIPPED";

  return (
    <div
      className={cn(
        "flex gap-3 rounded-lg p-3 transition-colors",
        isSynthesis &&
          "border-2 border-primary/40 bg-primary/5 shadow-sm",
        isError && "opacity-60",
        !isSynthesis && !isError && "hover:bg-secondary/30",
        className
      )}
      data-testid={`transcript-entry-${entry.speakerAgentId}-${entry.phaseIndex}`}
    >
      {/* Avatar */}
      <div
        className={cn(
          "flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white",
          isUser ? "bg-primary" : hashColor(entry.speakerAgentId)
        )}
        title={entry.speakerDisplayName}
      >
        {getInitials(entry.speakerDisplayName)}
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0">
        {/* Header row */}
        <div className="flex items-center gap-2 flex-wrap mb-1">
          <span className="text-sm font-semibold text-foreground">
            {entry.speakerDisplayName}
          </span>
          <Badge variant={badgeVariant(entry.type)} className="text-[10px] px-1.5 py-0">
            {info.label}
          </Badge>
          {entry.targetAgentId && (
            <span className="text-[10px] text-muted-foreground">
              → {entry.targetAgentId.slice(0, 8)}…
            </span>
          )}
          <span className="text-[10px] text-muted-foreground ms-auto">
            {new Date(entry.timestamp).toLocaleTimeString()}
          </span>
        </div>

        {/* Response body */}
        {entry.content ? (
          <div className="text-sm text-foreground/90 whitespace-pre-wrap leading-relaxed">
            {entry.content}
          </div>
        ) : entry.errorReason ? (
          <div className="text-sm text-destructive/80 italic">
            {entry.errorReason}
          </div>
        ) : (
          <div className="text-sm text-muted-foreground italic">
            No response
          </div>
        )}
      </div>
    </div>
  );
}
