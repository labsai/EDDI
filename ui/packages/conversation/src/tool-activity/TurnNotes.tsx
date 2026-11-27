/* ──────────────────────────────────────────────
   Turn notes (T2.7), rendered: one quiet line per thing the stream said about
   how a turn's answer was reached — a cascade that moved to another model, a
   re-ask of the same model, a guardrail that acted. Nothing is rendered when
   the stream said nothing (see `turn-notes.ts`).
   ────────────────────────────────────────────── */

import { useSyncExternalStore } from "react";
import { useTranslation } from "react-i18next";
import { Info, ShieldAlert } from "lucide-react";
import {
  guardrailNoteKey,
  turnNoteKey,
  type GuardrailNoteInput,
  type TurnNote,
  type TurnNotesLog,
  type TurnNoteTurns,
} from "./turn-notes";

/** The notes a log holds for one conversation, re-read when it changes. */
export function useTurnNotes(log: TurnNotesLog, conversationId: string | null): TurnNoteTurns {
  return useSyncExternalStore(log.subscribe, () => log.turns(conversationId));
}

/** A turn's notes as a short list of plain sentences. */
export function TurnNotes({ notes, className }: { notes: readonly TurnNote[] | undefined; className?: string }) {
  const { t } = useTranslation();
  if (!notes || notes.length === 0) return null;
  return (
    <ul
      className={className ?? "flex flex-col gap-0.5 text-[11px] text-muted-foreground"}
      aria-label={t("turnNotes.region", "How this answer was reached")}
      data-testid="turn-notes"
    >
      {notes.map((note, i) => {
        const [key, fallback] = turnNoteKey(note);
        return (
          <li key={`${note.kind}-${note.reason}-${i}`} className="inline-flex items-start gap-1" data-testid="turn-note">
            <Info className="mt-px h-3 w-3 shrink-0" aria-hidden="true" />
            <span>{t(key, fallback)}</span>
          </li>
        );
      })}
    </ul>
  );
}

/** One guardrail notice in plain words — what a safety check did, naming which one. */
export function GuardrailNote({ notice }: { notice: GuardrailNoteInput }) {
  const { t } = useTranslation();
  const [key, fallback] = guardrailNoteKey(notice);
  return (
    <li className="inline-flex items-start gap-1 text-[10px] text-muted-foreground" data-testid="guardrail-notice">
      <ShieldAlert className="mt-px h-3 w-3 shrink-0" aria-hidden="true" />
      <span>{t(key, fallback, { type: notice.type, phase: notice.phase, action: notice.action })}</span>
    </li>
  );
}
