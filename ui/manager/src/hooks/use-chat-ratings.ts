import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { isApiError } from "@/lib/api-client";
import {
  CONVERSATION_STEP,
  rateConversation,
  rateStep,
  readOwnRatings,
  withdrawConversationRating,
  withdrawStepRating,
  type OwnRating,
  type RatingInput,
  type RatingProfile,
} from "@/lib/api/ratings";
import { agentReplyCount } from "@/lib/chat-rating-steps";
import type { ChatMessage } from "@/lib/api/chat";

/** The card offered for a conversation that was just ended — it outlives the cleared transcript. */
export interface EndedRatingCard {
  conversationId: string;
  profile: RatingProfile;
  current: OwnRating | null;
}

/**
 * The Manager chat's ratings of the open conversation — the same flow the Chat
 * UI offers, so a designer can try it: restored when the conversation opens,
 * every change applied at once and rolled back with a toast when refused, and
 * the rating card offered after `askAfterTurns` replies (or on End, when the
 * agent asks on end) once per conversation.
 */
export function useChatRatings(conversationId: string | null, profile: RatingProfile | null, messages: readonly ChatMessage[]) {
  const { t } = useTranslation();
  const [conversationRating, setConversationRating] = useState<OwnRating | null>(null);
  const [stepRatings, setStepRatings] = useState<Record<number, OwnRating>>({});
  const [restoredFor, setRestoredFor] = useState<string | null>(null);
  const [cardOpen, setCardOpen] = useState(false);
  /** The conversation's own controls; undefined until (or unless) the server says. */
  const [controls, setControls] = useState<RatingProfile | null | undefined>(undefined);
  const promptedRef = useRef<Set<string>>(new Set());
  /** Set by onEnded; kept while the transcript is cleared, dropped when another conversation opens. */
  const [endedCard, setEndedCard] = useState<EndedRatingCard | null>(null);
  const endedCardRef = useRef(endedCard);
  /** What the last open conversation looked like — ending clears it before onEnded runs. */
  const lastOpenRef = useRef<{ conversationId: string; profile: RatingProfile | null; rating: OwnRating | null } | null>(null);
  const conversationRef = useRef(conversationId);
  const stepRatingsRef = useRef(stepRatings);
  const conversationRatingRef = useRef(conversationRating);
  useEffect(() => {
    conversationRef.current = conversationId;
    stepRatingsRef.current = stepRatings;
    conversationRatingRef.current = conversationRating;
    endedCardRef.current = endedCard;
  });

  // Once the server names the controls of the version this conversation runs
  // on, they decide what is shown — a rating write is checked against them.
  const effective = controls !== undefined ? controls : profile;
  useEffect(() => {
    setConversationRating(null);
    setStepRatings({});
    setCardOpen(false);
    setRestoredFor(null);
    setControls(undefined);
    // A newly opened conversation replaces the ended one's card; the cleared
    // transcript that ending leaves behind (no id) keeps it.
    if (conversationId) setEndedCard(null);
    // Read the conversation's own ratings and controls even when the current
    // profile is null: the conversation may run on a version that asks for them.
    if (!conversationId) return;
    let cancelled = false;
    readOwnRatings(conversationId)
      .then((ratings) => {
        if (cancelled) return;
        setConversationRating(ratings.conversation);
        setStepRatings(Object.fromEntries(ratings.steps.map((r) => [r.stepIndex, r])));
        setControls(ratings.controls);
      })
      .catch(() => {
        // Nothing rated, or an older server: start empty.
      })
      .finally(() => {
        if (!cancelled) setRestoredFor(conversationId);
      });
    return () => {
      cancelled = true;
    };
  }, [conversationId]);

  const fail = useCallback(
    (err: unknown) => {
      const code = isApiError(err) ? (err as { code?: string }).code : undefined;
      switch (code) {
        case "RATING_WINDOW_CLOSED":
          toast.error(t("chat.rating.errWindow", "This conversation can no longer be rated."));
          break;
        case "RATING_DISABLED":
          toast.error(t("chat.rating.errDisabled", "This agent version does not ask for ratings."));
          break;
        case "NOT_CONVERSATION_OWNER":
          toast.error(t("chat.rating.errOwner", "Only the person who had this conversation can rate it."));
          break;
        default:
          toast.error(t("chat.rating.errGeneric", "The rating could not be saved."));
      }
    },
    [t],
  );

  const rateReply = useCallback(
    async (stepIndex: number, input: RatingInput | null) => {
      const convId = conversationRef.current;
      if (!convId) return false;
      const previous = stepRatingsRef.current[stepIndex];
      setStepRatings((current) => {
        const next = { ...current };
        if (input) next[stepIndex] = { stepIndex, score: input.score, tags: input.tags ?? [], comment: input.comment ?? null };
        else delete next[stepIndex];
        return next;
      });
      try {
        if (input) await rateStep(convId, stepIndex, input);
        else await withdrawStepRating(convId, stepIndex);
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

  const rateWhole = useCallback(
    async (input: RatingInput | null) => {
      const ended = endedCardRef.current;
      const convId = ended?.conversationId ?? conversationRef.current;
      if (!convId) return false;
      const next = input ? { stepIndex: CONVERSATION_STEP, score: input.score, tags: input.tags ?? [], comment: input.comment ?? null } : null;
      // The ended conversation's card rates that conversation, not the cleared one.
      const previous = ended ? ended.current : conversationRatingRef.current;
      if (ended) setEndedCard({ ...ended, current: next });
      else setConversationRating(next);
      try {
        if (input) await rateConversation(convId, input);
        else await withdrawConversationRating(convId);
        return true;
      } catch (err) {
        if (ended) {
          if (endedCardRef.current?.conversationId === convId) setEndedCard({ ...ended, current: previous });
        } else {
          if (conversationRef.current !== convId) return false;
          setConversationRating(previous);
        }
        fail(err);
        return false;
      }
    },
    [fail],
  );

  const clearStep = useCallback((stepIndex: number) => {
    setStepRatings((current) => {
      if (!(stepIndex in current)) return current;
      const next = { ...current };
      delete next[stepIndex];
      return next;
    });
  }, []);

  const openCard = useCallback(() => {
    if (conversationRef.current) promptedRef.current.add(conversationRef.current);
    setCardOpen(true);
  }, []);
  const closeCard = useCallback(() => {
    setCardOpen(false);
    setEndedCard(null);
  }, []);
  /**
   * Ending the conversation: offer the card once, when the agent asks on end.
   * Takes the ended id from the mutation: by the time it succeeds, ending has
   * already cleared the open conversation, so the ref may hold null.
   */
  const onEnded = useCallback((endedId?: string | null) => {
    const last = lastOpenRef.current;
    const convId = endedId ?? last?.conversationId ?? null;
    if (!convId || !last || last.conversationId !== convId) return;
    const endedProfile = last.profile;
    if (!endedProfile?.conversationRating || !endedProfile.askOnEnd || promptedRef.current.has(convId)) return;
    if (last.rating) return;
    promptedRef.current.add(convId);
    setEndedCard({ conversationId: convId, profile: endedProfile, current: null });
  }, []);

  // Remembered for onEnded, which runs after ending has cleared all of this.
  useEffect(() => {
    if (conversationId) lastOpenRef.current = { conversationId, profile: effective, rating: conversationRating };
  }, [conversationId, effective, conversationRating]);

  const replies = useMemo(() => agentReplyCount(messages), [messages]);
  useEffect(() => {
    if (!effective?.conversationRating || !conversationId || cardOpen || restoredFor !== conversationId) return;
    if (conversationRating || promptedRef.current.has(conversationId)) return;
    if (effective.askAfterTurns > 0 && replies >= effective.askAfterTurns) {
      promptedRef.current.add(conversationId);
      setCardOpen(true);
    }
  }, [effective, conversationId, cardOpen, restoredFor, conversationRating, replies]);

  return {
    conversationRating,
    stepRatings,
    rateReply,
    rateWhole,
    clearStep,
    cardOpen: cardOpen && !!effective?.conversationRating,
    openCard,
    closeCard,
    onEnded,
    /** The card for a conversation that was just ended, or null. */
    endedCard,
    /** The controls to render — the conversation's own once known. */
    profile: effective,
  };
}
