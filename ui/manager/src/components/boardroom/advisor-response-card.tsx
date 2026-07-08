import { useState, useCallback, useRef, useEffect } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Check, Clipboard, Star } from "lucide-react";
import { cn } from "@/lib/utils";
import { AdvisorAvatar } from "@/components/boardroom/advisor-avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

// ─── Pin Types & Hook ────────────────────────────────────────────

interface PinnedResponse {
  speakerName: string;
  content: string;
  timestamp: number;
  boardId: string;
  sessionId: string;
}

function usePinnedResponses(boardId: string) {
  const key = `boardroom-pins-${boardId}`;

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
  content: string | null;

  boardId: string;
  /** Optional session ID for pin storage */
  sessionId?: string;

  className?: string;
}

// ─── Typing Indicator ────────────────────────────────────────────

function TypingIndicator() {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-1 py-1" role="status" aria-label={t("boardroom.board.loadingResponse", "Loading response")}>
      <span
        className="inline-block h-2 w-2 rounded-full bg-slate-400 animate-bounce"
        style={{ animationDelay: "0ms" }}
      />
      <span
        className="inline-block h-2 w-2 rounded-full bg-slate-400 animate-bounce"
        style={{ animationDelay: "160ms" }}
      />
      <span
        className="inline-block h-2 w-2 rounded-full bg-slate-400 animate-bounce"
        style={{ animationDelay: "320ms" }}
      />
    </div>
  );
}

// ─── Component ───────────────────────────────────────────────────

function AdvisorResponseCard({
  displayName,
  agentId,
  role,
  content,
  boardId,
  sessionId = "",
  className,
}: AdvisorResponseCardProps) {
  const { t } = useTranslation();

  // ── Copy to clipboard ────────────────────────────────────────
  const [copied, setCopied] = useState(false);
  const copyTimerRef = useRef<ReturnType<typeof setTimeout>>();
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

  return (
    <div
      className={cn(
        "group relative rounded-xl border p-4",
        "bg-white border-slate-200",
        "dark:bg-slate-900/50 dark:border-slate-800",
        className,
      )}
      style={{ animation: "br-message-in 250ms ease-out both" }}
    >
      {/* ── Action buttons (top-end, hover-reveal) ──────────── */}
      {content !== null && (
        <div
          className={cn(
            "absolute top-2 end-2 flex items-center gap-0.5",
            "opacity-0 group-hover:opacity-100 focus-within:opacity-100",
            "transition-opacity duration-150",
          )}
        >
          {/* Copy button */}
          <Button
            variant="ghost"
            size="icon"
            className="h-7 w-7 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
            onClick={handleCopy}
            aria-label={
              copied
                ? t("boardroom.board.copied", "Copied!")
                : t("boardroom.board.copyResponse", "Copy response")
            }
          >
            {copied ? (
              <Check className="h-3.5 w-3.5 text-emerald-500" />
            ) : (
              <Clipboard className="h-3.5 w-3.5" />
            )}
          </Button>

          {/* Pin / bookmark button */}
          <Button
            variant="ghost"
            size="icon"
            className="h-7 w-7 text-slate-400 hover:text-amber-500 dark:hover:text-amber-400"
            onClick={handleTogglePin}
            aria-label={
              pinned
                ? t("boardroom.board.unpin", "Unpin response")
                : t("boardroom.board.pin", "Pin response")
            }
          >
            <Star
              className={cn(
                "h-3.5 w-3.5",
                pinned && "fill-amber-400 text-amber-400",
              )}
            />
          </Button>
        </div>
      )}

      {/* Header row */}
      <div className="flex items-center gap-2 mb-2">
        <AdvisorAvatar name={displayName} agentId={agentId} size="sm" />
        <span className="font-medium text-sm text-slate-900 dark:text-slate-100">
          {displayName}
        </span>
        {role && (
          <Badge variant="secondary" className="text-xs">
            {role}
          </Badge>
        )}
      </div>

      {/* Content */}
      <div className="ps-10">
        {content === null ? (
          <TypingIndicator />
        ) : (
          <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap leading-relaxed">
            {content}
          </p>
        )}
      </div>

      {/* Footer — follow-up link */}
      {content !== null && (
        <div className="ps-10 mt-3">
          <Button variant="ghost" size="sm" className="text-indigo-500 p-0 h-auto" asChild>
            <Link
              to={`/boardroom/${boardId}/thread/${agentId}`}
              state={{ fromGroup: true, question: "", response: content }}
            >
              {t("boardroom.board.askMore", "Ask {{name}} more →", { name: displayName })}
            </Link>
          </Button>
        </div>
      )}
    </div>
  );
}

export { AdvisorResponseCard, usePinnedResponses };
export type { AdvisorResponseCardProps, PinnedResponse };
