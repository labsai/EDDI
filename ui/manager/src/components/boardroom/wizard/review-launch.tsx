import { useState, useEffect, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Check, Clock, Loader2, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { AdvisorAvatar } from "@/components/boardroom/advisor-avatar";
import { STYLE_INFO, type DiscussionStyle } from "@/lib/api/groups";
import type { MemberSlot } from "./team-builder";

// ─── Types ──────────────────────────────────────────────────────────────────

export interface CreationProgressItem {
  id: string;
  name: string;
  status: "pending" | "creating" | "done" | "error";
  error?: string;
}

interface ReviewLaunchProps {
  boardName: string;
  boardDescription: string;
  style: DiscussionStyle | null;
  members: MemberSlot[];
  isCreating: boolean;
  creationProgress: CreationProgressItem[];
  onCreateClick: () => void;
}

// ─── StatusIcon (internal) ──────────────────────────────────────────────────

function StatusIcon({
  status,
}: {
  status: CreationProgressItem["status"];
}) {
  switch (status) {
    case "pending":
      return <Clock className="h-4 w-4 text-slate-400" />;
    case "creating":
      return <Loader2 className="h-4 w-4 animate-spin text-indigo-500" />;
    case "done":
      return <Check className="h-4 w-4 text-emerald-500" />;
    case "error":
      return <X className="h-4 w-4 text-red-500" />;
    default: {
      const _exhaustive: never = status;
      return _exhaustive;
    }
  }
}

function StatusLabel({
  status,
  error,
}: {
  status: CreationProgressItem["status"];
  error?: string;
}) {
  const { t } = useTranslation();

  switch (status) {
    case "pending":
      return (
        <span className="text-xs text-slate-400">
          {t("boardroom.wizard.pending", "Waiting…")}
        </span>
      );
    case "creating":
      return (
        <span className="text-xs text-indigo-500">
          {t("boardroom.wizard.creatingAgent", "Creating…")}
        </span>
      );
    case "done":
      return (
        <span className="text-xs text-emerald-500">
          {t("boardroom.wizard.ready", "Ready")}
        </span>
      );
    case "error":
      return (
        <span className="text-xs text-red-500" title={error}>
          {error || t("boardroom.wizard.error", "Failed")}
        </span>
      );
    default: {
      const _exhaustive: never = status;
      return _exhaustive;
    }
  }
}
// ─── Confetti (internal) ────────────────────────────────────────────────────

function Confetti() {
  const [visible, setVisible] = useState(true);

  const pieces = useMemo(
    () =>
      Array.from({ length: 30 }, (_, i) => ({
        id: i,
        x: Math.random() * 100,
        delay: Math.random() * 500,
        duration: 1000 + Math.random() * 1000,
        color: ["#6366f1", "#f59e0b", "#10b981", "#ec4899", "#8b5cf6"][i % 5],
        size: 6 + Math.random() * 6,
      })),
    [],
  );

  useEffect(() => {
    const timer = setTimeout(() => setVisible(false), 3000);
    return () => clearTimeout(timer);
  }, []);

  if (!visible) return null;

  return (
    <div
      className="fixed inset-0 pointer-events-none z-50 overflow-hidden"
      aria-hidden="true"
    >
      {pieces.map((p) => (
        <div
          key={p.id}
          className="absolute rounded-sm"
          style={{
            insetInlineStart: `${p.x}%`,
            top: "-10px",
            width: p.size,
            height: p.size,
            backgroundColor: p.color,
            animation: `confetti-fall ${p.duration}ms ${p.delay}ms ease-in forwards`,
          }}
        />
      ))}
    </div>
  );
}

// ─── ReviewLaunch (exported) ────────────────────────────────────────────────

function ReviewLaunch({
  boardName,
  boardDescription,
  style,
  members,
  isCreating,
  creationProgress,
  onCreateClick,
}: ReviewLaunchProps) {
  const { t } = useTranslation();

  // ─── Creation progress view ─────────────────────────────────────────────
  const hasError = creationProgress.some((p) => p.status === "error");
  const allDone =
    creationProgress.length > 0 &&
    creationProgress.every((p) => p.status === "done");

  if (isCreating || allDone || (creationProgress.length > 0 && hasError)) {
    return (
      <div className="space-y-6">
        {allDone && <Confetti />}
        <div className="br-surface rounded-xl p-6">
          <h2 className="text-lg font-semibold text-foreground">
            {hasError && !isCreating
              ? t("boardroom.wizard.creationFailed", "Creation failed")
              : t("boardroom.wizard.settingUp", "Setting up your boardroom…")}
          </h2>
          <p className="mt-1 text-sm text-muted-foreground">
            {hasError && !isCreating
              ? t(
                  "boardroom.wizard.creationFailedDesc",
                  "Something went wrong. You can retry and it will pick up where it left off.",
                )
              : t(
                  "boardroom.wizard.settingUpDesc",
                  "Creating advisors and configuring the board. This may take a moment.",
                )}
          </p>
        </div>

        <div className="space-y-2" aria-live="polite">
          {creationProgress.map((item) => (
            <div
              key={item.id}
              className={cn(
                "flex items-center gap-3 rounded-lg border p-3 transition-all",
                item.status === "creating" &&
                  "border-indigo-200 bg-indigo-50/50 dark:border-indigo-800 dark:bg-indigo-500/5",
                item.status === "done" &&
                  "border-emerald-200 bg-emerald-50/50 dark:border-emerald-800 dark:bg-emerald-500/5",
                item.status === "error" &&
                  "border-red-200 bg-red-50/50 dark:border-red-800 dark:bg-red-500/5",
                item.status === "pending" &&
                  "border-slate-200 dark:border-slate-700",
              )}
            >
              <StatusIcon status={item.status} />
              <span className="flex-1 text-sm font-medium text-foreground">
                {item.name}
              </span>
              <StatusLabel status={item.status} error={item.error} />
            </div>
          ))}
        </div>

        {hasError && !isCreating && (
          <Button
            className="w-full bg-indigo-500 text-white hover:bg-indigo-600"
            size="lg"
            onClick={onCreateClick}
          >
            {t("boardroom.wizard.tryAgain", "Try Again")}
          </Button>
        )}
      </div>
    );
  }

  // ─── Summary view ───────────────────────────────────────────────────────
  return (
    <div className="space-y-6">
      {/* Summary card */}
      <div className="br-surface rounded-xl p-6">
        <h2 className="text-xl font-semibold text-foreground">
          {boardName || t("boardroom.wizard.untitled", "Untitled Board")}
        </h2>
        {boardDescription && (
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            {boardDescription}
          </p>
        )}
        {style && STYLE_INFO[style] && (
          <div className="mt-3">
            <Badge variant="secondary">
              <span className="me-1">{STYLE_INFO[style].icon}</span>
              {STYLE_INFO[style].label}
            </Badge>
          </div>
        )}
      </div>

      {/* Team section */}
      <div>
        <h3 className="mb-3 text-sm font-semibold text-foreground">
          {t("boardroom.wizard.team", "Team")}
        </h3>
        <div className="space-y-2">
          {members.map((member) => (
            <div
              key={member.id}
              className="flex items-center gap-3 rounded-lg border border-slate-200 p-3 dark:border-slate-700"
            >
              <AdvisorAvatar
                name={member.displayName || "?"}
                agentId={member.id}
                size="sm"
              />
              <div className="flex-1 min-w-0">
                <span className="block truncate text-sm font-medium text-foreground">
                  {member.displayName || t("boardroom.wizard.unnamed", "Unnamed")}
                </span>
                {member.role && (
                  <span className="block truncate text-xs text-muted-foreground">
                    {member.role}
                  </span>
                )}
              </div>
              {member.mode === "new" ? (
                <Badge variant="default" className="text-[10px]">
                  {t("boardroom.wizard.new", "New")}
                </Badge>
              ) : (
                <Badge variant="secondary" className="max-w-28 truncate text-[10px]">
                  {member.agentId
                    ? member.agentId.slice(0, 12) + "…"
                    : t("boardroom.wizard.existing", "Existing")}
                </Badge>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Create button */}
      <Button
        className="w-full bg-indigo-500 text-white hover:bg-indigo-600"
        size="lg"
        disabled={isCreating}
        onClick={onCreateClick}
      >
        {t("boardroom.wizard.createBoardroom", "Create Boardroom")}
      </Button>
    </div>
  );
}

export { ReviewLaunch };
export type { ReviewLaunchProps };
