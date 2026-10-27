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
  ratingErrorMessage,
  type RatingClient,
  type RatingInput,
  type RatingProfile,
  type StoredRating,
} from "@/api/rating-api";
import { agentReplyCount } from "@/rating-steps";
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
  const stepRatingsRef = useRef(stepRatings);
  const conversationRatingRef = useRef(conversationRating);
  // Synced after commit, never during render: a render React discards must
  // not leave its values behind for the handlers and async continuations.
  // Layout effects run before the passive effects that read these refs.
  useLayoutEffect(() => {
    conversationRef.current = conversationId;
    clientRef.current = client;
    stepRatingsRef.current = stepRatings;
    conversationRatingRef.current = conversationRating;
  }, [conversationId, client, stepRatings, conversationRating]);
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
    setConversationRating(null);
    setStepRatings({});
    setCardOpen(false);
    setRestoredFor(null);
    setControls(undefined);
    if (!enabled || !conversationId) return;
    let cancelled = false;
    clientRef.current
      .read(conversationId)
      .then((ratings) => {
        if (cancelled) return;
        setConversationRating(ratings.conversation);
        const byStep: Record<number, StoredRating> = {};
        for (const rating of ratings.steps) byStep[rating.stepIndex] = rating;
        setStepRatings(byStep);
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
      const previous = stepRatingsRef.current[stepIndex];
      setStepRatings((current) => {
        const next = { ...current };
        if (input) {
          next[stepIndex] = { stepIndex, score: input.score, tags: input.tags ?? [], comment: input.comment ?? null };
        } else {
          delete next[stepIndex];
        }
        return next;
      });
      try {
        if (input) await clientRef.current.rateStep(convId, stepIndex, input);
        else await clientRef.current.withdrawStep(convId, stepIndex);
        return true;
      } catch (err) {
        if (conversationRef.current !== convId) return false;
        setStepRatings((current) => {
          const next = { ...current };
          if (previous) next[stepIndex] = previous;
          else delete next[stepIndex];
          return next;
        });
        fail(err);
        return false;
      }
    },
    [fail],
  );

  const rateConversation = useCallback(
    async (input: RatingInput): Promise<boolean> => {
      const convId = conversationRef.current;
      if (!convId) return false;
      const previous = conversationRatingRef.current;
      setConversationRating({
        stepIndex: CONVERSATION_STEP,
        score: input.score,
        tags: input.tags ?? [],
        comment: input.comment ?? null,
      });
      try {
        await clientRef.current.rateConversation(convId, input);
        return true;
      } catch (err) {
        if (conversationRef.current !== convId) return false;
        setConversationRating(previous);
        fail(err);
        return false;
      }
    },
    [fail],
  );

  const withdrawConversation = useCallback(async (): Promise<boolean> => {
    const convId = conversationRef.current;
    if (!convId) return false;
    const previous = conversationRatingRef.current;
    setConversationRating(null);
    try {
      await clientRef.current.withdrawConversation(convId);
      return true;
    } catch (err) {
      if (conversationRef.current !== convId) return false;
      setConversationRating(previous);
      fail(err);
      return false;
    }
  }, [fail]);

  const clearStep = useCallback((stepIndex: number) => {
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
