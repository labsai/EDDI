import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Inbox, Check, X, Pencil } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@eddi/ui";
import { getErrorMessage } from "@/lib/api-client";
import { useAcceptTeamHandoff, useDeclineTeamHandoff } from "@/hooks/use-team-handoffs";
import { describeHandoffSource, type InboxItem } from "@/lib/api/team-handoffs";
import {
  WORKSPACE_MAX_TASK_SUBJECT_LENGTH,
  WORKSPACE_MAX_TASK_DESCRIPTION_LENGTH,
} from "@/lib/api/group-workspace";

interface HandoffInboxProps {
  groupId: string;
  items: InboxItem[];
  /** agent id → name; groups fall back to their id. */
  nameOf?: (id: string) => string;
  /** Hides the decide buttons for a viewer without EDIT. */
  readOnly?: boolean;
}

/**
 * The receiving team's intake inbox: handoffs filed under a REVIEW accept rule
 * wait here, in no task list, so no cadence can pull them until a person
 * accepts one. Accepting may edit the subject, description or priority (the
 * decision is then recorded as "approved with edits"); declining is remembered
 * so the same handoff is not offered again for a while.
 */
export function HandoffInbox({ groupId, items, nameOf, readOnly }: HandoffInboxProps) {
  const { t } = useTranslation();
  const accept = useAcceptTeamHandoff(groupId);
  const decline = useDeclineTeamHandoff(groupId);
  const [editing, setEditing] = useState<string | null>(null);
  const [declining, setDeclining] = useState<string | null>(null);
  const [subject, setSubject] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState(0);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  if (items.length === 0) return null;

  const inputCls =
    "w-full rounded-lg border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring";

  function startEdit(item: InboxItem) {
    setError(null);
    setDeclining(null);
    setEditing(item.handoffId);
    setSubject(item.subject);
    setDescription(item.description);
    setPriority(item.priority);
  }

  function startDecline(item: InboxItem) {
    setError(null);
    setEditing(null);
    setDeclining(item.handoffId);
    setReason("");
  }

  function doAccept(item: InboxItem, edited: boolean) {
    setError(null);
    accept.mutate(
      edited
        ? { handoffId: item.handoffId, subject: subject.trim(), description, priority }
        : { handoffId: item.handoffId },
      {
        onSuccess: () => {
          toast.success(t("teamHandoff.accepted", "Handoff accepted onto the backlog"));
          setEditing(null);
        },
        onError: (err) => setError(getErrorMessage(err)),
      },
    );
  }

  function doDecline(item: InboxItem) {
    setError(null);
    decline.mutate(
      { handoffId: item.handoffId, reasonCode: reason.trim() || undefined },
      {
        onSuccess: () => {
          toast.success(t("teamHandoff.declined", "Handoff declined"));
          setDeclining(null);
        },
        onError: (err) => setError(getErrorMessage(err)),
      },
    );
  }

  const busy = accept.isPending || decline.isPending;

  return (
    <div className="rounded-xl border border-primary/30 bg-card p-4" data-testid="workspace-handoff-inbox">
      <h3 className="mb-1 flex items-center gap-1.5 text-sm font-semibold text-foreground">
        <Inbox className="h-4 w-4 text-primary" />
        {t("teamHandoff.inboxTitle", "Intake ({{count}})", { count: items.length })}
      </h3>
      <p className="mb-3 text-xs text-muted-foreground">
        {t(
          "teamHandoff.inboxNote",
          "Work another team or agent wants to hand to this team. Nothing here runs until you accept it.",
        )}
      </p>

      <div className="space-y-2">
        {items.map((item) => {
          const origin = item.origin;
          return (
            <div
              key={item.handoffId}
              className="rounded-lg border border-border bg-secondary/20 p-2.5"
              data-testid={`handoff-${item.handoffId}`}
            >
              <div className="flex items-center gap-2">
                <span className="flex-1 text-xs font-medium text-foreground">{item.subject}</span>
                <span className="text-[10px] text-muted-foreground">P{item.priority}</span>
              </div>
              {item.description && (
                <p className="mt-1 whitespace-pre-wrap text-[10px] text-muted-foreground line-clamp-4">
                  {item.description}
                </p>
              )}
              <p className="mt-1 text-[10px] text-muted-foreground" data-testid={`handoff-from-${item.handoffId}`}>
                {t("teamHandoff.from", "From {{kind}} {{source}}", {
                  kind:
                    origin.sourceKind === "AGENT"
                      ? t("teamHandoff.kindAgent", "agent")
                      : t("teamHandoff.kindGroup", "team"),
                  source: describeHandoffSource(origin, nameOf),
                })}
                {" · "}
                {origin.trigger === "DECISION"
                  ? t("teamHandoff.triggerDecision", "decision rule")
                  : t("teamHandoff.triggerTool", "tool call")}
                {" · "}
                {t("teamHandoff.hop", "hop {{n}}", { n: origin.hop })}
              </p>

              {editing === item.handoffId ? (
                <div className="mt-2 space-y-2">
                  <label htmlFor={`handoff-subject-${item.handoffId}`} className="block text-[10px] text-muted-foreground">
                    {t("groupWorkspace.taskSubjectLabel", "Subject")}
                  </label>
                  <input
                    id={`handoff-subject-${item.handoffId}`}
                    value={subject}
                    onChange={(e) => setSubject(e.target.value)}
                    maxLength={WORKSPACE_MAX_TASK_SUBJECT_LENGTH}
                    className={inputCls}
                    data-testid="handoff-edit-subject"
                  />
                  <label htmlFor={`handoff-description-${item.handoffId}`} className="block text-[10px] text-muted-foreground">
                    {t("groupWorkspace.taskDescriptionLabel", "Description")}
                  </label>
                  <textarea
                    id={`handoff-description-${item.handoffId}`}
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    maxLength={WORKSPACE_MAX_TASK_DESCRIPTION_LENGTH}
                    rows={3}
                    className={inputCls}
                    data-testid="handoff-edit-description"
                  />
                  <div className="flex items-center gap-2">
                    <label htmlFor={`handoff-priority-${item.handoffId}`} className="text-xs text-muted-foreground">
                      {t("groupWorkspace.priority", "Priority")}
                    </label>
                    <input
                      id={`handoff-priority-${item.handoffId}`}
                      type="number"
                      min={0}
                      max={10}
                      value={priority}
                      onChange={(e) => setPriority(Number(e.target.value) || 0)}
                      className="w-20 rounded-lg border border-input bg-background px-2 py-1 text-sm"
                      data-testid="handoff-edit-priority"
                    />
                    <div className="ms-auto flex gap-2">
                      <Button size="sm" variant="outline" onClick={() => setEditing(null)} disabled={busy}>
                        {t("common.cancel", "Cancel")}
                      </Button>
                      <Button
                        size="sm"
                        onClick={() => doAccept(item, true)}
                        disabled={busy || !subject.trim()}
                        data-testid="handoff-accept-edited"
                      >
                        <Check className="h-3.5 w-3.5" />
                        {t("teamHandoff.acceptEdited", "Accept with edits")}
                      </Button>
                    </div>
                  </div>
                </div>
              ) : declining === item.handoffId ? (
                <div className="mt-2 flex items-center gap-2">
                  <label htmlFor={`handoff-reason-${item.handoffId}`} className="sr-only">
                    {t("teamHandoff.reasonPlaceholder", "Reason (optional), e.g. out_of_scope")}
                  </label>
                  <input
                    id={`handoff-reason-${item.handoffId}`}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    placeholder={t("teamHandoff.reasonPlaceholder", "Reason (optional), e.g. out_of_scope")}
                    maxLength={64}
                    className={inputCls}
                    data-testid="handoff-decline-reason"
                  />
                  <Button size="sm" variant="outline" onClick={() => setDeclining(null)} disabled={busy}>
                    {t("common.cancel", "Cancel")}
                  </Button>
                  <Button
                    size="sm"
                    variant="destructive"
                    onClick={() => doDecline(item)}
                    disabled={busy}
                    data-testid="handoff-decline-confirm"
                  >
                    {t("teamHandoff.decline", "Decline")}
                  </Button>
                </div>
              ) : (
                !readOnly && (
                  <div className="mt-2 flex gap-2">
                    <Button size="sm" onClick={() => doAccept(item, false)} disabled={busy} data-testid="handoff-accept">
                      <Check className="h-3.5 w-3.5" />
                      {t("teamHandoff.accept", "Accept")}
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => startEdit(item)} disabled={busy} data-testid="handoff-edit">
                      <Pencil className="h-3.5 w-3.5" />
                      {t("common.edit", "Edit")}
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => startDecline(item)} disabled={busy} data-testid="handoff-decline">
                      <X className="h-3.5 w-3.5" />
                      {t("teamHandoff.decline", "Decline")}
                    </Button>
                  </div>
                )
              )}
            </div>
          );
        })}
      </div>
      {error && (
        <p className="mt-2 text-xs text-destructive" data-testid="handoff-error">
          {error}
        </p>
      )}
    </div>
  );
}
