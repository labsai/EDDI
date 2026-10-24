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
  const promptedRef = useRef<Set<string>>(new Set());
  const conversationRef = useRef(conversationId);
  const stepRatingsRef = useRef(stepRatings);
  const conversationRatingRef = useRef(conversationRating);
  useEffect(() => {
    conversationRef.current = conversationId;
    stepRatingsRef.current = stepRatings;
    conversationRatingRef.current = conversationRating;
  });

  // Keyed on whether ratings are on, not on the profile object, so a new but
  // equal profile never wipes what was restored.
  const enabled = !!profile;
  useEffect(() => {
    setConversationRating(null);
    setStepRatings({});
    setCardOpen(false);
    setRestoredFor(null);
    if (!enabled || !conversationId) return;
    let cancelled = false;
    readOwnRatings(conversationId)
      .then((ratings) => {
        if (cancelled) return;
        setConversationRating(ratings.conversation);
        setStepRatings(Object.fromEntries(ratings.steps.map((r) => [r.stepIndex, r])));
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
  }, [conversationId, enabled]);

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
      const convId = conversationRef.current;
      if (!convId) return false;
      const previous = conversationRatingRef.current;
      setConversationRating(input ? { stepIndex: CONVERSATION_STEP, score: input.score, tags: input.tags ?? [], comment: input.comment ?? null } : null);
      try {
        if (input) await rateConversation(convId, input);
        else await withdrawConversationRating(convId);
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
  const closeCard = useCallback(() => setCardOpen(false), []);
  /** Ending the conversation: offer the card once, when the agent asks on end. */
  const onEnded = useCallback(() => {
    const convId = conversationRef.current;
    if (!profile?.conversationRating || !profile.askOnEnd || !convId || promptedRef.current.has(convId)) return;
    if (conversationRatingRef.current) return;
    promptedRef.current.add(convId);
    setCardOpen(true);
  }, [profile]);

  const replies = useMemo(() => agentReplyCount(messages), [messages]);
  useEffect(() => {
    if (!profile?.conversationRating || !conversationId || cardOpen || restoredFor !== conversationId) return;
    if (conversationRating || promptedRef.current.has(conversationId)) return;
    if (profile.askAfterTurns > 0 && replies >= profile.askAfterTurns) {
      promptedRef.current.add(conversationId);
      setCardOpen(true);
    }
  }, [profile, conversationId, cardOpen, restoredFor, conversationRating, replies]);

  return {
    conversationRating,
    stepRatings,
    rateReply,
    rateWhole,
    clearStep,
    cardOpen: cardOpen && !!profile?.conversationRating,
    openCard,
    closeCard,
    onEnded,
  };
}
