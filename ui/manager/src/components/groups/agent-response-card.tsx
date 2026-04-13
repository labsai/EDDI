import { useTranslation } from "react-i18next";
import DOMPurify from "dompurify";
import { cn, hashColor, getInitials } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import type { TranscriptEntry, TranscriptEntryType, DiscussionStyle } from "@/lib/api/groups";
import { ENTRY_TYPE_INFO } from "@/lib/api/groups";

interface AgentResponseCardProps {
  entry: TranscriptEntry;
  /** Show typing indicator instead of content */
  isSpeaking?: boolean;
  /** When true, render HTML content (sanitized via DOMPurify). Off by default for safety. */
  allowHtml?: boolean;
  /** Discussion style for style-aware badge colors */
  discussionStyle?: DiscussionStyle;
  className?: string;
}

/**
 * Parse transcript entry content, which may be:
 * 1. JSON from backend `extractResponse()` — e.g. `{"output":[{"type":"text","text":"..."}],...}`
 * 2. Plain text (already extracted, or from fixed backend)
 * Returns the cleaned text string.
 */
function parseTranscriptContent(content: string): string {
  if (!content) return "";

  // Quick check: does it look like JSON?
  const trimmed = content.trim();
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      const parsed = JSON.parse(trimmed);

      // Format 1: { "output": [{ "type": "text", "text": "..." }], ... }
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        const texts: string[] = [];

        // Check nested "output" array
        if (Array.isArray(parsed.output)) {
          for (const item of parsed.output) {
            if (typeof item === "string") texts.push(item);
            else if (item?.text) texts.push(String(item.text));
          }
        }

        // Check flat "output:text:*" keys
        if (texts.length === 0) {
          for (const [key, val] of Object.entries(parsed)) {
            if (!key.startsWith("output:text:")) continue;
            if (typeof val === "string") texts.push(val);
            else if (Array.isArray(val)) {
              for (const item of val) {
                if (typeof item === "string") texts.push(item);
                else if (item?.text) texts.push(String(item.text));
              }
            } else if (val && typeof val === "object" && (val as Record<string, unknown>).text) {
              texts.push(String((val as Record<string, unknown>).text));
            }
          }
        }

        if (texts.length > 0) return texts.join("\n");
      }
    } catch {
      // Not valid JSON — treat as plain text
    }
  }

  return content;
}

/** Check if content contains HTML tags */
function hasHtml(content: string): boolean {
  return /<[a-z][\s\S]*>/i.test(content);
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

export function AgentResponseCard({ entry, isSpeaking, allowHtml, className }: AgentResponseCardProps) {
  const { t } = useTranslation();
  const info = ENTRY_TYPE_INFO[entry.type];
  const isUser = entry.speakerAgentId === "user";
  const isSynthesis = entry.type === "SYNTHESIS";
  const isError = entry.type === "ERROR" || entry.type === "SKIPPED";

  const parsedContent = entry.content ? parseTranscriptContent(entry.content) : null;
  // Only render as HTML if opt-in is enabled AND content actually contains HTML tags
  const renderAsHtml = allowHtml && parsedContent ? hasHtml(parsedContent) : false;

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
        {isSpeaking ? (
          <div className="flex items-center gap-2 py-1">
            <span className="h-1.5 w-1.5 rounded-full bg-primary animate-bounce [animation-delay:0ms]" />
            <span className="h-1.5 w-1.5 rounded-full bg-primary animate-bounce [animation-delay:150ms]" />
            <span className="h-1.5 w-1.5 rounded-full bg-primary animate-bounce [animation-delay:300ms]" />
            <span className="text-xs text-muted-foreground ms-1">{t("groups.responding", "responding…")}</span>
          </div>
        ) : parsedContent ? (
          renderAsHtml ? (
            <div
              className="text-sm text-foreground/90 leading-relaxed [&_ul]:ms-4 [&_ul]:list-disc [&_li]:mb-0.5 [&_strong]:font-semibold"
              dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(parsedContent) }}
            />
          ) : (
            <div className="text-sm text-foreground/90 whitespace-pre-wrap leading-relaxed">
              {parsedContent}
            </div>
          )
        ) : entry.errorReason ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground italic">
            <span className="text-[10px] rounded-full bg-muted px-2 py-0.5">
              {entry.type === "SKIPPED" ? "⏭️ Skipped" : "⚠️ Error"}
            </span>
            <span className="text-xs">{entry.errorReason}</span>
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
