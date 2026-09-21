import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Clock, AlertTriangle, UserCheck, Send } from "lucide-react";
import { cn } from "@/lib/utils";
import { parseIsoDurationMs, formatDurationMs, formatIsoDuration } from "@/lib/hitl-config";

interface HumanTurnBannerProps {
  /** The pending member's display name. */
  displayName: string;
  /** The phase's rendered prompt — what the human is actually being asked. */
  renderedPrompt: string;
  pausedPhaseName?: string | null;
  /** ISO timestamp the turn became pending (`PendingHumanInput.requestedAt`). */
  requestedAt?: string | null;
  /** The group's `humanMemberConfig.turnTimeout` (ISO-8601 duration), if set. */
  turnTimeout?: string | null;
  onSubmit: (content: string) => void;
  isSubmitting?: boolean;
}

function isValidDate(iso?: string | null): boolean {
  return !!iso && !Number.isNaN(new Date(iso).getTime());
}

/**
 * A HUMAN group member's turn is up (I6) — "you're up", not "approve/reject".
 * Deliberately a separate component from `ApprovalBanner`: that one decides
 * whether someone else's contribution proceeds, this one collects the human's
 * OWN contribution, so the interaction (compose + submit) has nothing in common
 * with approve/reject/amend.
 */
export function HumanTurnBanner({
  displayName,
  renderedPrompt,
  pausedPhaseName,
  requestedAt,
  turnTimeout,
  onSubmit,
  isSubmitting,
}: HumanTurnBannerProps) {
  const { t } = useTranslation();
  const [content, setContent] = useState("");

  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    if (!requestedAt || !turnTimeout) return;
    const id = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(id);
  }, [requestedAt, turnTimeout]);

  const timeRemaining = (() => {
    if (!isValidDate(requestedAt) || !turnTimeout) return null;
    const durationMs = parseIsoDurationMs(turnTimeout);
    if (durationMs == null) return null;
    const deadline = new Date(requestedAt!).getTime() + durationMs;
    const remaining = deadline - nowMs;
    return { overdue: remaining <= 0, ms: Math.max(0, remaining) };
  })();

  const trimmed = content.trim();
  const canSubmit = trimmed.length > 0 && !isSubmitting;

  function handleSubmit() {
    if (!canSubmit) return;
    onSubmit(trimmed);
  }

  return (
    <div
      data-testid="human-turn-banner"
      className="rounded-xl border border-primary/30 bg-primary/5 p-4 backdrop-blur-sm"
    >
      <div className="mb-3 flex items-start gap-3">
        <div className="rounded-lg bg-primary/10 p-2">
          <UserCheck className="h-5 w-5 text-primary" aria-hidden="true" />
        </div>
        <div className="min-w-0 flex-1">
          <h3 className="text-sm font-semibold text-foreground">
            {t("groups.humanTurnTitle", "It's your turn, {{name}}", { name: displayName })}
          </h3>
        </div>
      </div>

      <div className="mb-3 flex flex-wrap gap-2 text-xs">
        {pausedPhaseName && (
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-muted-foreground">
            {t("hitl.phase", "Phase")}: {pausedPhaseName}
          </span>
        )}
        {isValidDate(requestedAt) && (
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-muted-foreground">
            <Clock className="h-3 w-3" aria-hidden="true" />
            {t("groups.humanTurnRequestedAt", "Requested")}:{" "}
            {new Intl.DateTimeFormat(undefined, { dateStyle: "short", timeStyle: "medium" }).format(
              new Date(requestedAt!),
            )}
          </span>
        )}
        {turnTimeout && (
          <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-muted-foreground">
            <AlertTriangle className="h-3 w-3" aria-hidden="true" />
            {t("groups.humanTurnTimeout", "Timeout")}: {formatIsoDuration(turnTimeout)}
          </span>
        )}
        {timeRemaining && (
          <span
            className={cn(
              "inline-flex items-center gap-1 rounded-full px-2.5 py-1 font-medium",
              timeRemaining.overdue
                ? "bg-destructive/10 text-destructive"
                : "bg-primary/10 text-primary",
            )}
          >
            <Clock className="h-3 w-3" aria-hidden="true" />
            {timeRemaining.overdue
              ? t("hitl.overdue", "Overdue")
              : `${t("hitl.timeRemaining", "Remaining")}: ${formatDurationMs(timeRemaining.ms)}`}
          </span>
        )}
      </div>

      {renderedPrompt && (
        <div
          className="mb-3 whitespace-pre-wrap rounded-lg border border-border bg-background/60 p-3 text-sm text-foreground"
          data-testid="human-turn-prompt"
        >
          {renderedPrompt}
        </div>
      )}

      <textarea
        value={content}
        onChange={(e) => setContent(e.target.value)}
        aria-label={t("groups.humanTurnResponsePlaceholder", "Your response")}
        placeholder={t("groups.humanTurnResponsePlaceholder", "Your response")}
        className="mb-3 w-full resize-none rounded-lg border border-border bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
        rows={4}
        disabled={isSubmitting}
        data-testid="human-turn-input"
        onKeyDown={(e) => {
          // Mod+Enter submits — matches the chat input convention elsewhere in the app.
          if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
            e.preventDefault();
            handleSubmit();
          }
        }}
      />

      <button
        type="button"
        disabled={!canSubmit}
        onClick={handleSubmit}
        className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
        data-testid="human-turn-submit"
      >
        <Send className="h-4 w-4" aria-hidden="true" />
        {isSubmitting ? t("groups.humanTurnSubmitting", "Submitting…") : t("groups.humanTurnSubmit", "Submit")}
      </button>
    </div>
  );
}
