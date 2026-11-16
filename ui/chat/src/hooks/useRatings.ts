/* ──────────────────────────────────────────────
   useRatings — the person's ratings of this conversation
   Restores what was rated when a conversation opens, applies every change
   optimistically (rolled back with a short notice when the server refuses
   it), and decides when the rating card offers itself: on the conversation's
   end and/or after N agent replies, at most once per conversation, never
   again once dismissed. Opening it from the header always works.
   ────────────────────────────────────────────── */

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import {
  CONVERSATION_STEP,
  type RatingClient,
  type RatingInput,
  type RatingProfile,
  type StoredRating,
} from "@eddi/core";
import { ratingErrorMessage } from "@/api/rating-api";
import { agentReplyCount } from "@eddi/core";
import type { ChatMessage, ConversationState } from "@/types";

/** How long a refused rating's notice stays up. */
export const RATING_TOAST_MS = 6_000;

const STORAGE_PREFIX = "eddi-chat.rating.";

interface CardMemory {
  /** The card offered itself once already. */
  prompted?: boolean;
  /** The person closed it. */
  dismissed?: boolean;
}

/** What this browser remembers about a conversation's card. Storage may be unavailable. */
function readMemory(conversationId: string): CardMemory {
  try {
    const raw = window.localStorage.getItem(STORAGE_PREFIX + conversationId);
    return raw ? (JSON.parse(raw) as CardMemory) : {};
  } catch {
    return {};
  }
}

function writeMemory(conversationId: string, memory: CardMemory): void {
  try {
    window.localStorage.setItem(STORAGE_PREFIX + conversationId, JSON.stringify(memory));
  } catch {
    // Private mode or blocked storage: remembered for this page only.
  }
}

export interface UseRatingsArgs {
  client: RatingClient;
  conversationId: string | null;
  profile: RatingProfile | null;
  messages: readonly ChatMessage[];
  conversationState: ConversationState | null;
}

export interface Ratings {
  conversationRating: StoredRating | null;
  stepRatings: Readonly<Record<number, StoredRating>>;
  /** Rate a reply, or withdraw its rating with null. Stable across renders. */
  rateStep: (stepIndex: number, input: RatingInput | null) => Promise<boolean>;
  rateConversation: (input: RatingInput) => Promise<boolean>;
  withdrawConversation: () => Promise<boolean>;
  /** Forget a reply's thumb locally — after a rerun the reply it rated is gone. */
  clearStep: (stepIndex: number) => void;
  cardOpen: boolean;
  /** The person asked for the card (header entry). */
  openCard: () => void;
  /** The person closed the card; it will not offer itself again. */
  closeCard: () => void;
  /** A refused rating's notice, or null. */
  toast: string | null;
  /**
   * The controls to render: those of the version this conversation runs on
   * once the server has said (they decide what a rating write accepts), the
   * profile's until then or when an older server does not say.
   */
  profile: RatingProfile | null;
}

/** One rating's in-flight and confirmed edits since the last restore read began. */
interface Edit {
  pending: number;
  confirmed: boolean;
  rev: number;
}

const newEdit = (): Edit => ({ pending: 0, confirmed: false, rev: 0 });

function editOf(edits: Map<number, Edit>, index: number): Edit {
  let edit = edits.get(index);
  if (!edit) {
    edit = newEdit();
    edits.set(index, edit);
  }
  return edit;
}

export function useRatings({ client, conversationId, profile, messages, conversationState }: UseRatingsArgs): Ratings {
  const [conversationRating, setConversationRating] = useState<StoredRating | null>(null);
  const [stepRatings, setStepRatings] = useState<Record<number, StoredRating>>({});
  const [restoredFor, setRestoredFor] = useState<string | null>(null);
  const [cardOpen, setCardOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  /** The conversation's own controls; undefined until (or unless) the server says. */
  const [controls, setControls] = useState<RatingProfile | null | undefined>(undefined);

  const conversationRef = useRef(conversationId);
  const clientRef = useRef(client);
  // Synced after commit, never during render: a render React discards must
  // not leave its values behind for the handlers and async continuations.
  // Layout effects run before the passive effects that read these refs.
  useLayoutEffect(() => {
    conversationRef.current = conversationId;
    clientRef.current = client;
  }, [conversationId, client]);
  /**
   * What the person has done to one rating since the last restore read began.
   * The read answers for the server as it was when asked, so it must not undo
   * an edit that is still in flight or that the server has confirmed since.
   * `rev` numbers the edits, so a failure only rolls back when no newer edit has
   * started; a failed edit alone protects nothing (the read may still apply).
   */
  const stepEdits = useRef<Map<number, Edit>>(new Map());
  const conversationEdit = useRef<Edit>(newEdit());
  /**
   * What the server holds, as far as this page knows: what the read returned, or
   * what the last confirmed write left. `undefined` until known. A failed edit
   * rolls back to it, never to an empty value.
   */
  const serverConversation = useRef<StoredRating | null | undefined>(undefined);
  const serverSteps = useRef<Map<number, StoredRating | null>>(new Map());
  /** The conversation the restore effect last ran for. */
  const openedFor = useRef<string | null>(null);
  /** Prompted in this page, whatever storage managed to keep. */
  const promptedRef = useRef<Set<string>>(new Set());

  // Reading starts from the profile, so an agent that asks for no ratings costs
  // no request. A conversation pinned to an older version that took ratings
  // while the current one does not therefore shows none — rare, since resuming
  // moves pinned conversations onto the current version.
  const enabled = !!profile && !!conversationId;
  const effective = controls !== undefined ? controls : profile;

  /* Restore on open / switch. */
  useEffect(() => {
    // Everything per-conversation is dropped only when the person moved to
    // ANOTHER conversation. This effect also runs when the profile merely
    // arrives or reloads for the same one: clearing then would throw away an
    // optimistic rating (or its protection) that is still in flight, and a click
    // on the header entry in that gap opened the card only for it to be closed.
    const switched = openedFor.current !== conversationId;
    if (switched) {
      if (openedFor.current !== null) setCardOpen(false);
      setConversationRating(null);
      setStepRatings({});
      stepEdits.current = new Map();
      conversationEdit.current = newEdit();
      serverConversation.current = undefined;
      serverSteps.current = new Map();
    }
    openedFor.current = conversationId;
    setRestoredFor(null);
    setControls(undefined);
    if (!enabled || !conversationId) return;
    // A new authoritative read begins: edits the server has already confirmed no
    // longer need protecting from it; edits still in flight keep theirs.
    if (conversationEdit.current.pending === 0) conversationEdit.current.confirmed = false;
    for (const edit of stepEdits.current.values()) {
      if (edit.pending === 0) edit.confirmed = false;
    }
    let cancelled = false;
    clientRef.current
      .read(conversationId)
      .then((ratings) => {
        if (cancelled) return;
        const conversationEdited = conversationEdit.current.pending > 0 || conversationEdit.current.confirmed;
        if (!conversationEdited) setConversationRating(ratings.conversation);
        if (!conversationEdited || serverConversation.current === undefined) serverConversation.current = ratings.conversation;
        const byStep: Record<number, StoredRating> = {};
        for (const rating of ratings.steps) byStep[rating.stepIndex] = rating;
        for (const index of new Set([...Object.keys(byStep).map(Number), ...serverSteps.current.keys()])) {
          const edit = stepEdits.current.get(index);
          if (!edit || (edit.pending === 0 && !edit.confirmed) || !serverSteps.current.has(index)) {
            serverSteps.current.set(index, byStep[index] ?? null);
          }
        }
        // Keep the person's own edits (still pending, or confirmed since the read
        // began) over the server's older view; everything else is the server's.
        setStepRatings((current) => {
          const next = { ...byStep };
          for (const [index, edit] of stepEdits.current) {
            if (edit.pending > 0 || edit.confirmed) {
              if (index in current) next[index] = current[index];
              else delete next[index];
            }
          }
          return next;
        });
        setControls(ratings.controls);
      })
      .catch(() => {
        // An older server without the endpoint, or nothing rated: start empty.
      })
      .finally(() => {
        if (!cancelled) setRestoredFor(conversationId);
      });
    return () => {
      cancelled = true;
    };
  }, [conversationId, enabled]);

  /* Toast lifetime. */
  useEffect(() => {
    if (!toast) return;
    const id = setTimeout(() => setToast(null), RATING_TOAST_MS);
    return () => clearTimeout(id);
  }, [toast]);

  const fail = useCallback((err: unknown) => setToast(ratingErrorMessage(err)), []);

  const rateStep = useCallback(
    async (stepIndex: number, input: RatingInput | null): Promise<boolean> => {
      const convId = conversationRef.current;
      if (!convId) return false;
      const edit = editOf(stepEdits.current, stepIndex);
      const rev = ++edit.rev;
      edit.pending += 1;
      const written: StoredRating | null = input
        ? { stepIndex, score: input.score, tags: input.tags ?? [], comment: input.comment ?? null }
        : null;
      setStepRatings((current) => {
        const next = { ...current };
        if (written) next[stepIndex] = written;
        else delete next[stepIndex];
        return next;
      });
      try {
        if (input) await clientRef.current.rateStep(convId, stepIndex, input);
        else await clientRef.current.withdrawStep(convId, stepIndex);
        if (conversationRef.current === convId) {
          edit.pending -= 1;
          edit.confirmed = true;
          if (edit.rev === rev) serverSteps.current.set(stepIndex, written);
        }
        return true;
      } catch (err) {
        if (conversationRef.current !== convId) return false;
        edit.pending -= 1;
        // A newer edit owns the displayed value; only the newest one rolls back.
        if (edit.rev === rev) {
          const baseline = serverSteps.current.get(stepIndex);
          // Without a known server value (the read has not answered) show nothing
          // for now and let the read fill it in: a failed edit protects nothing.
          if (baseline === undefined) edit.confirmed = false;
          setStepRatings((current) => {
            const next = { ...current };
            if (baseline) next[stepIndex] = baseline;
            else delete next[stepIndex];
            return next;
          });
        }
        fail(err);
        return false;
      }
    },
    [fail],
  );

  /** Shows `value` for the conversation rating and sends it; on failure shows the server's value again. */
  const writeConversation = useCallback(
    async (value: StoredRating | null, send: (convId: string) => Promise<unknown>): Promise<boolean> => {
      const convId = conversationRef.current;
      if (!convId) return false;
      const edit = conversationEdit.current;
      const rev = ++edit.rev;
      edit.pending += 1;
      setConversationRating(value);
      try {
        await send(convId);
        if (conversationRef.current === convId) {
          edit.pending -= 1;
          edit.confirmed = true;
          if (edit.rev === rev) serverConversation.current = value;
        }
        return true;
      } catch (err) {
        if (conversationRef.current !== convId) return false;
        edit.pending -= 1;
        if (edit.rev === rev) {
          const baseline = serverConversation.current;
          if (baseline === undefined) edit.confirmed = false;
          setConversationRating(baseline ?? null);
        }
        fail(err);
        return false;
      }
    },
    [fail],
  );

  const rateConversation = useCallback(
    (input: RatingInput): Promise<boolean> =>
      writeConversation(
        { stepIndex: CONVERSATION_STEP, score: input.score, tags: input.tags ?? [], comment: input.comment ?? null },
        (convId) => clientRef.current.rateConversation(convId, input),
      ),
    [writeConversation],
  );

  const withdrawConversation = useCallback(
    (): Promise<boolean> => writeConversation(null, (convId) => clientRef.current.withdrawConversation(convId)),
    [writeConversation],
  );

  const clearStep = useCallback((stepIndex: number) => {
    // The step was re-run: its rating no longer applies, whatever the read says.
    const edit = editOf(stepEdits.current, stepIndex);
    edit.rev += 1;
    edit.confirmed = true;
    serverSteps.current.set(stepIndex, null);
    setStepRatings((current) => {
      if (!(stepIndex in current)) return current;
      const next = { ...current };
      delete next[stepIndex];
      return next;
    });
  }, []);

  const markPrompted = useCallback((convId: string, extra: CardMemory = {}) => {
    promptedRef.current.add(convId);
    writeMemory(convId, { ...readMemory(convId), prompted: true, ...extra });
  }, []);

  const openCard = useCallback(() => {
    const convId = conversationRef.current;
    if (convId) markPrompted(convId);
    setCardOpen(true);
  }, [markPrompted]);

  const closeCard = useCallback(() => {
    const convId = conversationRef.current;
    if (convId) markPrompted(convId, { dismissed: true });
    setCardOpen(false);
  }, [markPrompted]);

  /* When the card offers itself. */
  const replies = useMemo(() => agentReplyCount(messages), [messages]);
  useEffect(() => {
    if (!effective?.conversationRating || !conversationId || cardOpen) return;
    // Wait for the restore, so somebody who already rated is not asked again.
    if (restoredFor !== conversationId || conversationRating) return;
    if (promptedRef.current.has(conversationId)) return;
    const memory = readMemory(conversationId);
    if (memory.prompted || memory.dismissed) return;
    const ended = effective.askOnEnd && conversationState === "ENDED";
    const enoughReplies = effective.askAfterTurns > 0 && replies >= effective.askAfterTurns;
    if (ended || enoughReplies) {
      markPrompted(conversationId);
      setCardOpen(true);
    }
  }, [effective, conversationId, cardOpen, restoredFor, conversationRating, conversationState, replies, markPrompted]);

  return {
    conversationRating,
    stepRatings,
    rateStep,
    rateConversation,
    withdrawConversation,
    clearStep,
    cardOpen: cardOpen && !!effective?.conversationRating,
    openCard,
    closeCard,
    toast,
    profile: effective,
  };
}
