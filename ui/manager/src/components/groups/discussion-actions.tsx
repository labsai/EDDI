import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  MessageCircleReply,
  Loader2,
  Send,
  Lock,
  X,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { AlertDialog } from "@/components/ui/alert-dialog";
import type { GroupConversationAction, GroupMember } from "@/lib/api/groups";

interface DiscussionActionsProps {
  /** Backend-computed available operations. The bar renders EXACTLY these — the
   *  set is never hardcoded or derived from state on the client.
   *  Note: "continue" is handled by the context-aware input, not this bar. */
  availableActions: GroupConversationAction[];
  /** Group members offered in the follow-up picker (agents only). */
  members: Pick<GroupMember, "agentId" | "displayName" | "memberType">[];
  /** True while a followup/close request is in flight. */
  isPending?: boolean;
  onFollowup: (targetAgentId: string, question: string) => void;
  onCloseDiscussion: () => void;
}

type ComposerMode = "none" | "followup";

/**
 * Post-COMPLETED lifecycle action bar for a group discussion.
 *
 * Rendered only when the backend reports a non-empty `availableActions`
 * (COMPLETED → followup/continue/close; FAILED/CANCELLED → close; CLOSED → none,
 * so the bar disappears entirely). "Continue" is handled by the context-aware
 * input field below — this bar provides only "Follow up with a member" and
 * "Close discussion".
 */
export function DiscussionActions({
  availableActions,
  members,
  isPending = false,
  onFollowup,
  onCloseDiscussion,
}: DiscussionActionsProps) {
  const { t } = useTranslation();
  const [mode, setMode] = useState<ComposerMode>("none");
  const [closeOpen, setCloseOpen] = useState(false);
  const [followupQuestion, setFollowupQuestion] = useState("");

  // Only real agents can receive a direct follow-up (a nested GROUP member is not
  // an agent). Default the picker to the first eligible member.
  const eligibleMembers = useMemo(
    () => members.filter((m) => m.memberType !== "GROUP" && m.agentId),
    [members],
  );
  const [followupTarget, setFollowupTarget] = useState<string>(
    () => eligibleMembers[0]?.agentId ?? "",
  );

  const canFollowup =
    availableActions.includes("followup") && eligibleMembers.length > 0;
  const canClose = availableActions.includes("close");

  if (!canFollowup && !canClose) return null;

  function toggle(next: ComposerMode) {
    setMode((prev) => (prev === next ? "none" : next));
  }

  function submitFollowup() {
    const q = followupQuestion.trim();
    const target = followupTarget || eligibleMembers[0]?.agentId || "";
    if (!q || !target || isPending) return;
    onFollowup(target, q);
    setFollowupQuestion("");
    setMode("none");
  }

  return (
    <div
      className="border-t border-border bg-card/60 px-3 py-2.5"
      data-testid="discussion-actions"
    >
      <div className="flex flex-wrap items-center gap-2">
        {canFollowup && (
          <Button
            type="button"
            variant={mode === "followup" ? "secondary" : "outline"}
            size="sm"
            onClick={() => toggle("followup")}
            disabled={isPending}
            data-testid="action-followup"
          >
            <MessageCircleReply className="h-3.5 w-3.5" />
            {t("groups.followupMember", "Follow up with a member")}
          </Button>
        )}
        {canClose && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => setCloseOpen(true)}
            disabled={isPending}
            className="text-destructive hover:bg-destructive/10 hover:text-destructive"
            data-testid="action-close"
          >
            <Lock className="h-3.5 w-3.5" />
            {t("groups.closeDiscussion", "Close discussion")}
          </Button>
        )}
      </div>

      {/* Follow-up composer — one direct question to a single member agent. */}
      {mode === "followup" && canFollowup && (
        <div className="mt-2.5 space-y-1.5" data-testid="followup-composer">
          <p className="text-[11px] text-muted-foreground">
            {t(
              "groups.followupHint",
              "Ask one member a direct follow-up. The agent keeps full context from the discussion; the exchange is added to the transcript.",
            )}
          </p>
          <select
            value={followupTarget}
            onChange={(e) => setFollowupTarget(e.target.value)}
            aria-label={t("groups.selectMember", "Select a member")}
            className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            disabled={isPending}
            data-testid="group-followup-member"
          >
            {eligibleMembers.map((m) => (
              <option key={m.agentId} value={m.agentId}>
                {m.displayName || m.agentId}
              </option>
            ))}
          </select>
          <textarea
            value={followupQuestion}
            onChange={(e) => setFollowupQuestion(e.target.value)}
            placeholder={t(
              "groups.followupPlaceholder",
              "Ask this member a follow-up question…",
            )}
            className="w-full resize-y rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            rows={2}
            disabled={isPending}
            data-testid="group-followup-input"
            onKeyDown={(e) => {
              if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
                e.preventDefault();
                submitFollowup();
              }
            }}
          />
          <div className="flex justify-end gap-2">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => setMode("none")}
            >
              <X className="h-3.5 w-3.5" />
              {t("common.cancel", "Cancel")}
            </Button>
            <Button
              type="button"
              size="sm"
              onClick={submitFollowup}
              disabled={!followupQuestion.trim() || !followupTarget || isPending}
              data-testid="group-followup-submit"
            >
              {isPending ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <Send className="h-3.5 w-3.5" />
              )}
              {t("groups.sendFollowup", "Send follow-up")}
            </Button>
          </div>
        </div>
      )}

      {/* Close is irreversible — always confirm first. */}
      <AlertDialog
        open={closeOpen}
        onOpenChange={(open) => {
          if (!open) setCloseOpen(false);
        }}
        title={t("groups.closeConfirmTitle", "Close this discussion?")}
        description={t(
          "groups.closeConfirmDescription",
          "Closing permanently ends all member conversations and cleans up any ephemeral agents. No further follow-ups or continuations are possible. This cannot be undone.",
        )}
        confirmLabel={t("groups.closeConfirmButton", "Close discussion")}
        cancelLabel={t("groups.closeConfirmDismiss", "Go back")}
        variant="destructive"
        isPending={isPending}
        onConfirm={() => {
          onCloseDiscussion();
          setCloseOpen(false);
        }}
      />
    </div>
  );
}
