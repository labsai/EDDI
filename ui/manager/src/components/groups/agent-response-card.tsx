import { useState, useRef, useEffect } from "react";
import { useTranslation } from "react-i18next";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import DOMPurify from "dompurify";
import { ChevronDown, ChevronUp, ClipboardList, CheckCircle2, ListOrdered, User2, XCircle, Fingerprint } from "lucide-react";
import { cn, hashColor, getInitials } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import type { TranscriptEntry, TranscriptEntryType, DiscussionStyle, TaskDefinition } from "@/lib/api/groups";
import { entryTypeInfo, hasEnvelopeData } from "@/lib/api/groups";
import { parseTranscriptContent, formatMarkdownText, parseEmojiVerification, truncateContent, safeFormatDate } from "./group-utils";
import type { StructuredItem } from "./group-utils";

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

/** Check if content contains HTML tags */
function hasHtml(content: string): boolean {
  return /<[a-z][\s\S]*>/i.test(content);
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
    case "DISSENT":
      return "destructive";
    case "ABSTAINED":
      return "secondary";
    default:
      return "outline";
  }
}


/**
 * The envelope detail behind the signature badge. Not a verification result —
 * just what the entry carries, so an operator can correlate it with the
 * backend's own audit trail. The signature is truncated because the full Base64
 * is ~88 characters of noise in a tooltip.
 */
function signatureTooltip(entry: TranscriptEntry): string {
  const parts = [`Signature: ${entry.signature!.slice(0, 16)}…`];
  if (entry.signatureKeyVersion != null) {
    // Version 0 means the entry was signed before key versioning existed, so
    // the legacy single `publicKey` field is its key.
    parts.push(`Key version: ${entry.signatureKeyVersion}`);
  }
  if (entry.signatureTimestampMs != null) {
    parts.push(`Signed at: ${new Date(entry.signatureTimestampMs).toISOString()}`);
  }
  return parts.join("\n");
}

/** Height in px above which we collapse a message (~6 lines of text) */
const COLLAPSE_THRESHOLD = 144;

// StructuredItem is now imported from ./group-utils

/** Validate parsed array has structured items with 'subject' field */
function validateStructuredArray(arr: unknown): StructuredItem[] | null {
  if (!Array.isArray(arr) || arr.length === 0) return null;
  if (typeof (arr[0] as Record<string, unknown>)?.subject !== "string") return null;
  return arr as StructuredItem[];
}

/** Extract a JSON array substring from content (finds first `[` to last `]`) */
function extractJsonArray(content: string): string | null {
  const start = content.indexOf("[");
  const end = content.lastIndexOf("]");
  if (start === -1 || end === -1 || end <= start) return null;
  return content.slice(start, end + 1);
}

/** Try to parse content as a JSON array of structured items.
 *  Handles multiple scenarios:
 *  1. Clean JSON array
 *  2. JSON with unescaped newlines in string values (LLM output)
 *  3. JSON array embedded within wrapper text
 */
function tryParseStructuredItems(content: string | null): StructuredItem[] | null {
  if (!content) return null;

  // 1. Try to extract a JSON array substring from the content
  const jsonStr = extractJsonArray(content);
  if (!jsonStr) return null;

  // 2. Fast path: try standard JSON.parse
  try {
    return validateStructuredArray(JSON.parse(jsonStr));
  } catch { /* continue to fallback */ }

  // 3. Fallback: repair by escaping unescaped newlines within JSON string values
  //    (LLMs sometimes produce unescaped newlines in strings)
  try {
    const repaired = jsonStr.replace(
      /"(?:[^"\\]|\\.)*"/g,
      (match) => match.replace(/\n/g, "\\n").replace(/\r/g, "\\r").replace(/\t/g, "\\t"),
    );
    return validateStructuredArray(JSON.parse(repaired));
  } catch {
    return null;
  }
}

interface AgentResponseCardProps {
  entry: TranscriptEntry;
  isSpeaking?: boolean;
  allowHtml?: boolean;
  discussionStyle?: DiscussionStyle;
  /** Pre-configured tasks from group config (for TASK_FORCE style PLAN entries) */
  preConfiguredTasks?: TaskDefinition[];
  className?: string;
}

export function AgentResponseCard({ entry, isSpeaking, allowHtml, discussionStyle, preConfiguredTasks, className }: AgentResponseCardProps) {
  const { t } = useTranslation();
  const info = entryTypeInfo(entry.type);
  const isSynthesis = entry.type === "SYNTHESIS";
  const isError = entry.type === "ERROR" || entry.type === "SKIPPED";
  const isPlan = entry.type === "PLAN";
  const isVerification = entry.type === "VERIFICATION";
  const isTaskResult = entry.type === "TASK_RESULT";
  // A minority report (I4) — the one entry type whose whole point is that it
  // contradicts the synthesis directly above it, so it must not read as more
  // ordinary prose.
  const isDissent = entry.type === "DISSENT";
  // Housekeeping the engine records rather than a member's contribution: a
  // convergence judge's score, a facilitator's roster change, a declined turn.
  // Rendered muted so they narrate the discussion without competing with it.
  const isProcedural =
    entry.type === "CONVERGENCE" ||
    entry.type === "FACILITATION" ||
    entry.type === "ABSTAINED";

  // Style-aware badge variants
  const badgeVar = (discussionStyle && STYLE_BADGE_OVERRIDES[discussionStyle]?.[entry.type])
    || defaultBadgeVariant(entry.type);

  const rawParsed = entry.content ? parseTranscriptContent(entry.content) : null;
  // Guard: treat whitespace-only content as empty and auto-format markdown syntax
  const parsedContent = rawParsed?.trim() ? formatMarkdownText(rawParsed) : null;
  // Try parsing as structured JSON array — check both raw and unwrapped content (no type gate)
  let structuredItems = tryParseStructuredItems(entry.content) ?? tryParseStructuredItems(parsedContent);

  // For VERIFICATION entries, also try emoji-based text parsing (✅/❌ format from backend)
  if (!structuredItems && isVerification) {
    structuredItems = (entry.content ? parseEmojiVerification(entry.content) : null)
      ?? (parsedContent ? parseEmojiVerification(parsedContent) : null);
  }

  // For PLAN entries with pre-configured tasks: convert TaskDefinition[] → StructuredItem[]
  if (!structuredItems && isPlan && preConfiguredTasks && preConfiguredTasks.length > 0) {
    structuredItems = preConfiguredTasks.map((task) => ({
      subject: task.subject,
      description: task.description,
      assignedTo: task.assignToRole,
      priority: task.priority,
    }));
  }
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
        isDissent &&
          "border border-red-500/30 bg-red-500/5",
        isError && "opacity-60",
        isProcedural && "opacity-75",
        !isSynthesis && !isError && !isPlan && !isTaskResult && !isVerification && !isDissent && "hover:bg-secondary/30",
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
            {t(`groups.entryType.${entry.type}`, info.label)}
          </Badge>
          {entry.targetAgentId && (
            <span className="text-[10px] text-muted-foreground" title={entry.targetAgentId}>
              → {entry.targetAgentId.slice(0, 8)}…
            </span>
          )}
          {/* Inter-agent signature, when the speaker has `signInterAgentMessages`.
              Same shape and wording as the audit page's badge: shown only when
              present, absence meaning unsigned.

              Says "signed", never "verified" — verification needs the speaker's
              public key at the right version, which only the backend has. A
              badge claiming more than it checked is worse than no badge. */}
          {hasEnvelopeData(entry) && (
            <span
              className="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-1.5 py-0 text-[10px] font-medium text-emerald-600 cursor-help dark:text-emerald-400"
              title={signatureTooltip(entry)}
              data-testid="transcript-signature-badge"
            >
              <Fingerprint className="h-2.5 w-2.5" />
              {t("audit.signed", "Signed")}
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
        ) : structuredItems ? (
          /* Render structured items (plans, verifications, etc.) instead of raw JSON */
          <div className="space-y-2">
            <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground mb-2">
              {isVerification ? (
                <CheckCircle2 className="h-3.5 w-3.5" />
              ) : (
                <ListOrdered className="h-3.5 w-3.5" />
              )}
              {structuredItems.length} {structuredItems.length === 1 ? t("groups.item", "item") : t("groups.items", "items")}
            </div>
            {structuredItems.map((item, i) => {
              const hasVerdict = item.passed !== undefined;
              return (
                <div
                  key={i}
                  className={cn(
                    "flex items-start gap-3 rounded-lg border px-3 py-2.5",
                    hasVerdict && item.passed && "border-emerald-500/30 bg-emerald-500/5",
                    hasVerdict && !item.passed && "border-destructive/30 bg-destructive/5",
                    !hasVerdict && "border-border/50 bg-secondary/30",
                  )}
                >
                  {hasVerdict ? (
                    item.passed ? (
                      <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-500 mt-0.5" />
                    ) : (
                      <XCircle className="h-4 w-4 shrink-0 text-destructive mt-0.5" />
                    )
                  ) : (
                    <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-sky-500/20 text-sky-400 text-[10px] font-bold mt-0.5">
                      {i + 1}
                    </span>
                  )}
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-foreground leading-snug">{item.subject}</p>
                    {item.description && (
                      <ExpandableText text={item.description} className="mt-0.5" />
                    )}
                    {item.feedback && (
                      <ExpandableText text={item.feedback} className="mt-0.5" />
                    )}
                    {item.assignedTo && (
                      <div className="flex items-center gap-1 mt-1.5">
                        <User2 className="h-3 w-3 text-muted-foreground" />
                        <span className="text-[10px] text-muted-foreground font-medium truncate max-w-[200px]" title={item.assignedTo}>
                          {item.assignedTo.length > 12 ? `${item.assignedTo.slice(0, 12)}…` : item.assignedTo}
                        </span>
                      </div>
                    )}
                  </div>
                  {item.priority != null && (
                    <Badge variant="outline" className="text-[9px] px-1.5 py-0 shrink-0">
                      P{item.priority}
                    </Badge>
                  )}
                </div>
              );
            })}
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
              ) : (
                <div className="prose prose-sm dark:prose-invert max-w-none text-foreground/90 [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs [&_hr]:border-border [&_p:first-child]:mt-0 [&_p:last-child]:mb-0">
                  {/* Deliberately NO rehypeRaw: agent output is attacker-influenceable
                      (prompt injection). Raw HTML must stay escaped. The opt-in
                      HTML path above uses DOMPurify for explicit sanitization. */}
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>
                    {truncateContent(parsedContent, t("groups.contentTruncated", "[Content truncated]"))}
                  </ReactMarkdown>
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
        ) : entry.type === "ABSTAINED" ? (
          // An abstention carries null content BY DESIGN — the backend's comment
          // is explicit that "the point of an abstention is that there is no
          // position", so the type is the whole message. Falling through to "No
          // response" below would report a deliberate pass as a failed turn.
          <div className="text-sm italic text-muted-foreground">
            {t("groups.abstainedBody", "Declined to add anything new this round.")}
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

/** Clamped text with show more/less toggle for long content */
function ExpandableText({ text, className }: { text: string; className?: string }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const isLong = text.length > 100;

  return (
    <div className={className}>
      <p className={cn("text-xs text-muted-foreground leading-relaxed", !expanded && isLong && "line-clamp-2")}>
        {text}
      </p>
      {isLong && (
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          className="text-[10px] text-primary/70 hover:text-primary font-medium mt-0.5 transition-colors"
        >
          {expanded
            ? t("common.showLess", "Show less")
            : t("common.showMore", "Show more")}
        </button>
      )}
    </div>
  );
}
