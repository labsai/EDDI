import { useState, useRef, useEffect } from "react";
import { useTranslation } from "react-i18next";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeRaw from "rehype-raw";
import DOMPurify from "dompurify";
import { ChevronDown, ChevronUp, ClipboardList, CheckCircle2 } from "lucide-react";
import { cn, hashColor, getInitials } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import type { TranscriptEntry, TranscriptEntryType, DiscussionStyle } from "@/lib/api/groups";
import { ENTRY_TYPE_INFO } from "@/lib/api/groups";
import { parseTranscriptContent, safeFormatDate } from "./group-utils";

/** Style-aware badge colors for different discussion roles */
const STYLE_BADGE_OVERRIDES: Partial<Record<DiscussionStyle, Partial<Record<TranscriptEntryType, "default" | "secondary" | "success" | "warning" | "destructive" | "outline">>>> = {
  DEBATE: {
    ARGUMENT: "default",
    REBUTTAL: "warning",
  },
  DEVIL_ADVOCATE: {
    CHALLENGE: "destructive",
    DEFENSE: "success",
  },
  TASK_FORCE: {
    PLAN: "default",
    TASK_RESULT: "success",
    VERIFICATION: "warning",
  },
};

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

/** Check if content contains HTML tags */
function hasHtml(content: string): boolean {
  return /<[a-z][\s\S]*>/i.test(content);
}

/** Check if content looks like markdown (headings, bold, lists, links etc.) */
function hasMarkdown(content: string): boolean {
  return /^#{1,6}\s|\*\*|\*[^*]|^\s*[-*+]\s|^\s*\d+\.\s|\[.+\]\(.+\)|^>\s|```/m.test(content);
}

function defaultBadgeVariant(
  type: TranscriptEntryType
): "default" | "secondary" | "success" | "warning" | "destructive" | "outline" {
  switch (type) {
    case "SYNTHESIS":
    case "PLAN":
      return "default";
    case "ERROR":
      return "destructive";
    case "SKIPPED":
      return "secondary";
    case "CRITIQUE":
    case "CHALLENGE":
    case "VERIFICATION":
      return "warning";
    case "OPINION":
    case "REVISION":
    case "DEFENSE":
    case "TASK_RESULT":
      return "success";
    default:
      return "outline";
  }
}

/** Height in px above which we collapse a message (~6 lines of text) */
const COLLAPSE_THRESHOLD = 144;

export function AgentResponseCard({ entry, isSpeaking, allowHtml, discussionStyle, className }: AgentResponseCardProps) {
  const { t } = useTranslation();
  const info = ENTRY_TYPE_INFO[entry.type];
  const isSynthesis = entry.type === "SYNTHESIS";
  const isError = entry.type === "ERROR" || entry.type === "SKIPPED";
  const isPlan = entry.type === "PLAN";
  const isVerification = entry.type === "VERIFICATION";
  const isTaskResult = entry.type === "TASK_RESULT";

  // Style-aware badge variants
  const badgeVar = (discussionStyle && STYLE_BADGE_OVERRIDES[discussionStyle]?.[entry.type])
    || defaultBadgeVariant(entry.type);

  const parsedContent = entry.content ? parseTranscriptContent(entry.content) : null;
  // Only render as HTML if opt-in is enabled AND content actually contains HTML tags
  const renderAsHtml = allowHtml && parsedContent ? hasHtml(parsedContent) : false;

  // Collapsible long messages
  const contentRef = useRef<HTMLDivElement>(null);
  const [isCollapsible, setIsCollapsible] = useState(false);
  const [isExpanded, setIsExpanded] = useState(false);

  useEffect(() => {
    if (contentRef.current) {
      setIsCollapsible(contentRef.current.scrollHeight > COLLAPSE_THRESHOLD);
    }
  }, [parsedContent]);

  return (
    <div
      className={cn(
        "flex gap-3 rounded-lg p-3 transition-colors",
        isSynthesis &&
          "border-2 border-primary/40 bg-primary/5 shadow-sm",
        isPlan &&
          "border border-sky-500/30 bg-sky-500/5",
        isTaskResult &&
          "border border-emerald-500/20 bg-emerald-500/5",
        isVerification &&
          "border border-amber-500/20 bg-amber-500/5",
        isError && "opacity-60",
        !isSynthesis && !isError && !isPlan && !isTaskResult && !isVerification && "hover:bg-secondary/30",
        className
      )}
      data-testid={`transcript-entry-${entry.speakerAgentId}-${entry.phaseIndex}`}
    >
      {/* Avatar */}
      <div
        className={cn(
          "flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white",
          hashColor(entry.speakerAgentId)
        )}
        title={entry.speakerDisplayName}
      >
        {isPlan ? (
          <ClipboardList className="h-4 w-4" />
        ) : isVerification ? (
          <CheckCircle2 className="h-4 w-4" />
        ) : (
          getInitials(entry.speakerDisplayName)
        )}
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0">
        {/* Header row */}
        <div className="flex items-center gap-2 flex-wrap mb-1">
          <span className="text-sm font-semibold text-foreground">
            {entry.speakerDisplayName}
          </span>
          <Badge variant={badgeVar} className="text-[10px] px-1.5 py-0">
            {info.label}
          </Badge>
          {entry.targetAgentId && (
            <span className="text-[10px] text-muted-foreground">
              → {entry.targetAgentId.slice(0, 8)}…
            </span>
          )}
          <span className="text-[10px] text-muted-foreground ms-auto">
            {safeFormatDate(entry.timestamp, "time")}
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
          <>
            <div
              ref={contentRef}
              className={cn(
                "relative transition-[max-height] duration-300 ease-in-out overflow-hidden",
                isCollapsible && !isExpanded && "max-h-36"
              )}
            >
              {renderAsHtml ? (
                <div
                  className="text-sm text-foreground/90 leading-relaxed [&_ul]:ms-4 [&_ul]:list-disc [&_li]:mb-0.5 [&_strong]:font-semibold"
                  dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(parsedContent) }}
                />
              ) : hasMarkdown(parsedContent) ? (
                <div className="prose prose-sm dark:prose-invert max-w-none text-foreground/90 [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs [&_hr]:border-border">
                  <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeRaw]}>
                    {parsedContent}
                  </ReactMarkdown>
                </div>
              ) : (
                <div className="text-sm text-foreground/90 whitespace-pre-wrap leading-relaxed">
                  {parsedContent}
                </div>
              )}
              {/* Fade-out gradient when collapsed — bg matches card context */}
              {isCollapsible && !isExpanded && (
                <div className={cn(
                  "absolute bottom-0 inset-x-0 h-10 bg-gradient-to-t pointer-events-none",
                  isSynthesis
                    ? "from-primary/5 to-transparent"
                    : "from-card to-transparent"
                )} />
              )}
            </div>
            {isCollapsible && (
              <button
                onClick={() => setIsExpanded((v) => !v)}
                className="flex items-center gap-1 mt-1 text-xs font-medium text-primary hover:text-primary/80 transition-colors"
              >
                {isExpanded ? (
                  <>
                    <ChevronUp className="h-3 w-3" />
                    {t("common.showLess", "Show less")}
                  </>
                ) : (
                  <>
                    <ChevronDown className="h-3 w-3" />
                    {t("common.showMore", "Show more")}
                  </>
                )}
              </button>
            )}
          </>
        ) : entry.errorReason ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground italic">
            <span className="text-[10px] rounded-full bg-muted px-2 py-0.5">
              {entry.type === "SKIPPED"
                ? `⏭️ ${t("groups.skipped", "Skipped")}`
                : `⚠️ ${t("common.error", "Error")}`}
            </span>
            <span className="text-xs">{entry.errorReason}</span>
          </div>
        ) : (
          <div className="text-sm text-muted-foreground italic">
            {t("groups.noResponse", "No response")}
          </div>
        )}
      </div>
    </div>
  );
}
