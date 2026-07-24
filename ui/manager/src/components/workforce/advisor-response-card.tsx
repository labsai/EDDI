import { useState, useCallback, useRef, useEffect, memo } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Check, Clipboard, Star, MessageCircle, AlertCircle, ChevronDown, ChevronUp } from "lucide-react";
import { cn, getInitials } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { truncateContent } from "@/components/groups/group-utils";

// ─── Pin Types & Hook ────────────────────────────────────────────

interface PinnedResponse {
  speakerName: string;
  content: string;
  timestamp: number;
  boardId: string;
  sessionId: string;
}

function usePinnedResponses(boardId: string) {
  const key = `workforce-pins-${boardId}`;

  const [pins, setPins] = useState<PinnedResponse[]>(() => {
    try {
      const raw = JSON.parse(localStorage.getItem(key) || "[]");
      return Array.isArray(raw) ? raw : [];
    } catch {
      return [];
    }
  });

  const isResponsePinned = useCallback(
    (content: string, speaker: string) =>
      pins.some((p) => p.content === content && p.speakerName === speaker),
    [pins],
  );

  const togglePin = useCallback(
    (pin: PinnedResponse) => {
      setPins((current) => {
        const exists = current.some(
          (p) => p.content === pin.content && p.speakerName === pin.speakerName,
        );
        const next = exists
          ? current.filter(
              (p) =>
                !(p.content === pin.content && p.speakerName === pin.speakerName),
            )
          : [...current, pin];
        try {
          localStorage.setItem(key, JSON.stringify(next));
        } catch {
          /* quota exceeded or private browsing */
        }
        return next;
      });
    },
    [key],
  );

  return { pins, togglePin, isResponsePinned };
}

// ─── Types ───────────────────────────────────────────────────────

interface AdvisorResponseCardProps {
  displayName: string;
  agentId: string;
  role?: string | null;
  /** Badge variant for the role label (matches manager group chat style) */
  roleBadgeVariant?: "default" | "secondary" | "success" | "warning" | "destructive" | "outline";
  content: string | null;

  boardId: string;
  /** Optional session ID for pin storage */
  sessionId?: string;
  /** Optional timestamp string to display */
  timestamp?: string;

  className?: string;
}

// ─── Typing Indicator ────────────────────────────────────────────

function TypingIndicator() {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-1.5 py-1" role="status" aria-label={t("Workforce.board.loadingResponse", "Loading response")}>
      <span
        className="inline-block h-1.5 w-1.5 rounded-full bg-muted-foreground/50 animate-bounce"
        style={{ animationDelay: "0ms" }}
      />
      <span
        className="inline-block h-1.5 w-1.5 rounded-full bg-muted-foreground/50 animate-bounce"
        style={{ animationDelay: "160ms" }}
      />
      <span
        className="inline-block h-1.5 w-1.5 rounded-full bg-muted-foreground/50 animate-bounce"
        style={{ animationDelay: "320ms" }}
      />
    </div>
  );
}

// ─── Avatar ──────────────────────────────────────────────────────

function ResponseAvatar({ name }: { name: string; agentId: string }) {
  return (
    <div
      className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-semibold bg-muted text-muted-foreground"
      aria-hidden
    >
      {getInitials(name)}
    </div>
  );
}

// ─── Constants ───────────────────────────────────────────────────

/** Height in px above which we collapse a message (~6 lines) */
const COLLAPSE_THRESHOLD = 144;

// ─── Hooks ──────────────────────────────────────────────────────

/** Shared collapse state for message cards */
function useCollapsibleContent(dep: unknown) {
  const contentRef = useRef<HTMLDivElement>(null);
  const [isCollapsible, setIsCollapsible] = useState(false);
  const [isExpanded, setIsExpanded] = useState(false);

  useEffect(() => {
    if (contentRef.current) {
      setIsCollapsible(contentRef.current.scrollHeight > COLLAPSE_THRESHOLD);
    }
  }, [dep]);

  return { contentRef, isCollapsible, isExpanded, setIsExpanded };
}

// ─── Component ───────────────────────────────────────────────────

const AdvisorResponseCard = memo(function AdvisorResponseCard({
  displayName,
  agentId,
  role,
  roleBadgeVariant = "secondary",
  content,
  boardId,
  sessionId = "",
  timestamp,
  className,
}: AdvisorResponseCardProps) {
  const { t } = useTranslation();

  // ── Copy to clipboard ────────────────────────────────────────
  const [copied, setCopied] = useState(false);
  const copyTimerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => () => clearTimeout(copyTimerRef.current), []);

  const handleCopy = async () => {
    if (!content) return;
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      copyTimerRef.current = setTimeout(() => setCopied(false), 2000);
    } catch {
      /* non-secure context or permission denied */
    }
  };

  // ── Pin / bookmark ───────────────────────────────────────────
  const { togglePin, isResponsePinned } = usePinnedResponses(boardId);
  const pinned = content ? isResponsePinned(content, displayName) : false;

  const handleTogglePin = () => {
    if (!content) return;
    togglePin({
      speakerName: displayName,
      content,
      timestamp: Date.now(),
      boardId,
      sessionId,
    });
  };

  const isStreaming = content === null;
  const isEmpty = !isStreaming && !content?.trim();
  const { contentRef, isCollapsible, isExpanded, setIsExpanded } = useCollapsibleContent(content);

  return (
    <div
      className={cn(
        "group relative flex gap-3 py-3",
        className,
      )}
      style={{ animation: "br-message-in 300ms ease-out both" }}
    >
      {/* Avatar */}
      <ResponseAvatar name={displayName} agentId={agentId} />

      {/* Content column */}
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        {/* Name + role + actions row */}
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-foreground">
            {displayName}
          </span>
          {role && (
            <Badge
              variant={roleBadgeVariant}
              className="text-[10px] px-1.5 py-0 h-4 font-normal"
            >
              {role}
            </Badge>
          )}
          {timestamp && (
            <span className="text-[10px] text-muted-foreground/60 select-none">
              {timestamp}
            </span>
          )}

          {/* Hover actions */}
          {content !== null && !isEmpty && (
            <div
              className={cn(
                "ms-auto flex items-center gap-0.5",
                "opacity-0 group-hover:opacity-100 focus-within:opacity-100",
                "transition-opacity duration-150",
              )}
            >
              <Button
                variant="ghost"
                size="icon"
                className="h-6 w-6 text-muted-foreground hover:text-foreground/80"
                onClick={handleCopy}
                aria-label={
                  copied
                    ? t("Workforce.board.copied", "Copied!")
                    : t("Workforce.board.copyResponse", "Copy response")
                }
              >
                {copied ? (
                  <Check className="h-3 w-3 text-emerald-500" />
                ) : (
                  <Clipboard className="h-3 w-3" />
                )}
              </Button>

              <Button
                variant="ghost"
                size="icon"
                className="h-6 w-6 text-muted-foreground hover:text-amber-500 dark:hover:text-amber-400"
                onClick={handleTogglePin}
                aria-label={
                  pinned
                    ? t("Workforce.board.unpin", "Unpin response")
                    : t("Workforce.board.pin", "Pin response")
                }
              >
                <Star
                  className={cn(
                    "h-3 w-3",
                    pinned && "fill-amber-400 text-amber-400",
                  )}
                />
              </Button>
            </div>
          )}
        </div>

        {/* Message bubble */}
        <div
          className={cn(
            "rounded-2xl rounded-ss-md px-4 py-2.5 text-sm leading-relaxed",
            "bg-card border border-border text-card-foreground",
            isStreaming && "min-w-[80px]",
          )}
        >
          {isStreaming ? (
            <TypingIndicator />
          ) : isEmpty ? (
            <div className="flex items-center gap-1.5 py-1 text-muted-foreground">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" />
              <span className="text-sm italic">
                {t("Workforce.board.noResponseGenerated", "No response generated")}
              </span>
            </div>
          ) : (
            <>
              <div
                ref={contentRef}
                className={cn(
                  "relative transition-[max-height] duration-300 ease-in-out overflow-hidden",
                  isCollapsible && !isExpanded && "max-h-36",
                )}
              >
                <div className="prose prose-sm dark:prose-invert max-w-none [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs [&_p]:my-1 [&_ul]:my-1 [&_ol]:my-1 [&_p:first-child]:mt-0 [&_p:last-child]:mb-0">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>
                    {truncateContent(content!, t("groups.contentTruncated", "[Content truncated]"))}
                  </ReactMarkdown>
                </div>
                {isCollapsible && !isExpanded && (
                  <div className="absolute bottom-0 inset-x-0 h-10 bg-gradient-to-t from-card to-transparent pointer-events-none" />
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
          )}
        </div>

        {/* Footer — follow-up link */}
        {content !== null && !isEmpty && (
          <div className="flex items-center gap-2 ps-1 mt-0.5">
            <Button
              variant="ghost"
              size="sm"
              className="text-primary hover:text-primary/80 p-0 h-auto text-xs gap-1"
              asChild
            >
              <Link
                to={`/workforce/${boardId}/thread/${agentId}`}
                state={{ fromGroup: true, question: "", response: content }}
              >
                <MessageCircle className="h-3 w-3" />
                {t("Workforce.board.askMore", "Ask {{name}} more →", { name: displayName })}
              </Link>
            </Button>
          </div>
        )}
      </div>
    </div>
  );
});

export { AdvisorResponseCard };
export type { AdvisorResponseCardProps, PinnedResponse };
