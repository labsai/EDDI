import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { AdvisorAvatar } from "@/components/boardroom/advisor-avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

// ─── Types ───────────────────────────────────────────────────────

interface AdvisorResponseCardProps {
  displayName: string;
  agentId: string;
  role?: string | null;
  content: string | null;
  phaseType?: string;
  boardId: string;

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
  className,
}: AdvisorResponseCardProps) {
  const { t } = useTranslation();

  return (
    <div
      className={cn(
        "rounded-xl border p-4",
        "bg-white border-slate-200",
        "dark:bg-slate-900/50 dark:border-slate-800",
        className,
      )}
      style={{ animation: "br-message-in 250ms ease-out both" }}
    >
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

export { AdvisorResponseCard };
export type { AdvisorResponseCardProps };
