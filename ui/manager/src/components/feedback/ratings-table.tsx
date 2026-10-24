import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { CheckCircle2, ExternalLink, NotebookPen, RotateCcw } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { RatingScore } from "./rating-score";
import { isRatingChanged, useReviewRating } from "@/hooks/use-ratings";
import { RATING_LIMITS, type RatingRow } from "@/lib/api/ratings";

interface RatingsTableProps {
  rows: RatingRow[];
  /** Offer row selection (the "Needs attention" tab's bulk review). */
  selectable?: boolean;
  selected?: ReadonlySet<string>;
  onSelectedChange?: (next: Set<string>) => void;
}

/**
 * Ratings with their triage controls. Comments are user-typed text and render
 * as plain text — React escapes them; nothing here interprets markup. The user
 * column appears only when the server sent user ids (administrators).
 */
export function RatingsTable({ rows, selectable = false, selected = new Set(), onSelectedChange }: RatingsTableProps) {
  const { t, i18n } = useTranslation();
  const review = useReviewRating();
  const [noteFor, setNoteFor] = useState<RatingRow | null>(null);
  const showUser = rows.some((r) => !!r.userId);

  const triage = async (row: RatingRow, change: { reviewStatus?: "NEW" | "REVIEWED"; reviewNote?: string }) => {
    try {
      await review.mutateAsync({ id: row.id, ...change });
      return true;
    } catch (err) {
      if (isRatingChanged(err)) {
        // The list refetches (the mutation invalidates on settle); say why the row moved.
        toast.info(t("feedback.ratingChanged", "This rating was changed by the person meanwhile — the list has been reloaded."));
      } else {
        toast.error(t("feedback.reviewFailed", "The review could not be saved."));
      }
      return false;
    }
  };

  const toggle = (id: string) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    onSelectedChange?.(next);
  };

  const date = (iso: string | null) =>
    iso ? new Date(iso).toLocaleString(i18n.language, { dateStyle: "medium", timeStyle: "short" }) : "—";

  return (
    <div className="overflow-x-auto rounded-xl border border-border/50" data-testid="ratings-table">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b bg-muted/50 text-muted-foreground">
            {selectable && (
              <th className="w-10 px-4 py-3">
                <input
                  type="checkbox"
                  aria-label={t("feedback.selectAll", "Select all shown")}
                  checked={rows.length > 0 && rows.every((r) => selected.has(r.id))}
                  onChange={(e) => onSelectedChange?.(e.target.checked ? new Set(rows.map((r) => r.id)) : new Set())}
                  className="h-4 w-4 accent-primary"
                  data-testid="ratings-select-all"
                />
              </th>
            )}
            <th className="px-4 py-3 text-start font-medium">{t("feedback.colScore", "Score")}</th>
            <th className="px-4 py-3 text-start font-medium">{t("feedback.colFeedback", "Feedback")}</th>
            <th className="px-4 py-3 text-start font-medium">{t("feedback.colAgent", "Agent")}</th>
            <th className="px-4 py-3 text-start font-medium">{t("feedback.colDate", "Date")}</th>
            {showUser && <th className="px-4 py-3 text-start font-medium">{t("feedback.colUser", "User")}</th>}
            <th className="px-4 py-3 text-start font-medium">{t("feedback.colStatus", "Status")}</th>
            <th className="px-4 py-3 text-end font-medium">{t("feedback.colActions", "Actions")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} className="border-b border-border/30 align-top" data-testid={`rating-row-${row.id}`}>
              {selectable && (
                <td className="px-4 py-3">
                  <input
                    type="checkbox"
                    aria-label={t("feedback.selectRow", "Select this rating")}
                    checked={selected.has(row.id)}
                    onChange={() => toggle(row.id)}
                    className="h-4 w-4 accent-primary"
                    data-testid={`rating-select-${row.id}`}
                  />
                </td>
              )}
              <td className="px-4 py-3">
                <RatingScore scale={row.scale} score={row.score} />
                <div className="mt-1 text-xs text-muted-foreground">
                  {row.kind === "conversation"
                    ? t("feedback.kindConversationShort", "Conversation")
                    : t("feedback.kindReplyStep", { step: row.stepIndex, defaultValue: "Reply (step {{step}})" })}
                </div>
              </td>
              <td className="max-w-md px-4 py-3">
                {row.tags.length > 0 && (
                  <div className="mb-1 flex flex-wrap gap-1">
                    {row.tags.map((tag) => (
                      <Badge key={tag} variant="secondary">
                        {tag}
                      </Badge>
                    ))}
                  </div>
                )}
                {row.comment ? (
                  <p className="whitespace-pre-wrap break-words" data-testid={`rating-comment-${row.id}`}>
                    {row.comment}
                  </p>
                ) : (
                  row.tags.length === 0 && <span className="text-muted-foreground">—</span>
                )}
                {row.reviewNote && (
                  <p className="mt-2 rounded-lg bg-muted/50 px-2 py-1 text-xs text-muted-foreground" data-testid={`rating-note-${row.id}`}>
                    <NotebookPen className="me-1 inline h-3 w-3" aria-hidden="true" />
                    {row.reviewNote}
                  </p>
                )}
              </td>
              <td className="px-4 py-3">
                <span className="font-mono text-xs text-muted-foreground">
                  {row.agentId ?? "—"}
                  {row.agentVersion !== null && ` v${row.agentVersion}`}
                </span>
                {row.channel && <div className="text-xs text-muted-foreground">{row.channel}</div>}
              </td>
              <td className="whitespace-nowrap px-4 py-3 text-xs text-muted-foreground">{date(row.createdAt)}</td>
              {showUser && <td className="px-4 py-3 font-mono text-xs">{row.userId ?? "—"}</td>}
              <td className="px-4 py-3">
                {row.reviewStatus === "REVIEWED" ? (
                  <Badge variant="success" title={row.reviewedBy ?? undefined} data-testid={`rating-status-${row.id}`}>
                    {t("feedback.statusReviewed", "Reviewed")}
                  </Badge>
                ) : (
                  <Badge variant="warning" data-testid={`rating-status-${row.id}`}>
                    {t("feedback.statusNew", "New")}
                  </Badge>
                )}
              </td>
              <td className="px-4 py-3">
                <div className="flex flex-wrap justify-end gap-1">
                  {row.reviewStatus === "REVIEWED" ? (
                    <Button size="sm" variant="ghost" onClick={() => void triage(row, { reviewStatus: "NEW" })} data-testid={`rating-reopen-${row.id}`}>
                      <RotateCcw className="h-4 w-4" />
                      {t("feedback.reopen", "Reopen")}
                    </Button>
                  ) : (
                    <Button size="sm" variant="outline" onClick={() => void triage(row, { reviewStatus: "REVIEWED" })} data-testid={`rating-review-${row.id}`}>
                      <CheckCircle2 className="h-4 w-4" />
                      {t("feedback.markReviewed", "Mark reviewed")}
                    </Button>
                  )}
                  <Button size="sm" variant="ghost" onClick={() => setNoteFor(row)} data-testid={`rating-note-btn-${row.id}`}>
                    <NotebookPen className="h-4 w-4" />
                    {t("feedback.note", "Note")}
                  </Button>
                  {row.canOpenTranscript && (
                    <Button size="sm" variant="ghost" asChild>
                      <Link to={`/manage/conversationview/${encodeURIComponent(row.conversationId)}`} data-testid={`rating-open-${row.id}`}>
                        <ExternalLink className="h-4 w-4" />
                        {t("feedback.openConversation", "Open conversation")}
                      </Link>
                    </Button>
                  )}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {noteFor && (
        <NoteDialog
          row={noteFor}
          pending={review.isPending}
          onClose={() => setNoteFor(null)}
          onSave={async (note) => {
            if (await triage(noteFor, { reviewNote: note })) setNoteFor(null);
          }}
        />
      )}
    </div>
  );
}

function NoteDialog({
  row,
  pending,
  onClose,
  onSave,
}: {
  row: RatingRow;
  pending: boolean;
  onClose: () => void;
  onSave: (note: string) => void;
}) {
  const { t } = useTranslation();
  const [note, setNote] = useState(row.reviewNote ?? "");
  const tooLong = note.length > RATING_LIMITS.maxReviewNoteLength;
  return (
    <AccessibleDialog open onClose={onClose} title={t("feedback.noteTitle", "Internal note")} testId="rating-note-dialog">
      <div className="space-y-3">
        <p className="text-sm text-muted-foreground">
          {t("feedback.noteHelp", "Seen only by the people running this agent — never by the person who rated.")}
        </p>
        <textarea
          value={note}
          onChange={(e) => setNote(e.target.value)}
          rows={4}
          className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          aria-label={t("feedback.noteTitle", "Internal note")}
          aria-invalid={tooLong || undefined}
          data-testid="rating-note-input"
        />
        <p className={tooLong ? "text-xs text-destructive" : "text-xs text-muted-foreground"}>
          {note.length} / {RATING_LIMITS.maxReviewNoteLength}
        </p>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            {t("common.cancel")}
          </Button>
          <Button onClick={() => onSave(note)} disabled={tooLong || pending} data-testid="rating-note-save">
            {t("common.save")}
          </Button>
        </div>
      </div>
    </AccessibleDialog>
  );
}
