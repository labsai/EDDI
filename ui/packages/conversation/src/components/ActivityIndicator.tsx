/* ──────────────────────────────────────────────
   TypingIndicator — Bouncing dots (agent is typing)
   ThinkingIndicator — Pulsing brain; `escalating` for a model-cascade step up,
                       `guardrail` while a guardrail that fired is at work,
                       `tool` while a tool call is running
   ────────────────────────────────────────────── */

import { Brain, ShieldCheck, Wrench } from "lucide-react";
import type { ConversationMessage } from "../controller/state";
import { englishText, type Translate } from "../i18n/text";
import { useConversationText } from "../react/hooks";

/** Three bouncing dots shown while the agent is composing a response. */
export function TypingIndicator() {
  return (
    <div className="indicator" data-testid="typing-indicator">
      <div className="indicator__avatar" aria-hidden="true">
        E
      </div>
      <div className="indicator__bubble">
        <div className="indicator__dots">
          <span className="indicator__dot" />
          <span className="indicator__dot" />
          <span className="indicator__dot" />
        </div>
      </div>
    </div>
  );
}

/**
 * Shown when the agent is in a thinking/reasoning phase (e.g. tool calls, RAG).
 *
 * `escalating` switches the copy for the model-cascade case, where a cheaper
 * model was abandoned mid-turn and a stronger one is now running. It is a prop
 * rather than a second component so React reconciles the same element and does
 * not replay the entrance animation when the state flips mid-wait.
 *
 * The wording stays deliberately generic. The escalation event carries the
 * confidence that tripped it, the threshold, a reason code and the elapsed time
 * (no model name — only cascade_step_start has that, and no cost at all); none
 * of it is the end user's business. "Harder" is avoided too — it implies the
 * first attempt was half-hearted — as is ⚡, which reads as *fast* when the
 * whole point of this state is that the answer is taking longer.
 *
 * `tool` names the tool the agent is running right now ("Using calculator…",
 * the Manager's wording). It takes precedence over `escalating`: a tool called
 * after an escalation is the stronger model at work, and the reducer drops the
 * tool when an escalation follows it. The name is shown as sent — it is the
 * identifier the agent designer gave the tool, not operator detail.
 */
/**
 * The words a status indicator shows, for the screen-reader announcement.
 *
 * The visual indicator lives inside the transcript, which is `aria-busy` for
 * the whole turn so a streamed reply is read once rather than token by token.
 * A live region inside a busy one may not be announced at all — and every
 * state here exists only mid-turn — so the widget announces this text from a
 * status region outside the transcript instead.
 */
export function indicatorStatusText(
  escalating: boolean,
  tool: string | null,
  t: Translate = englishText,
  guardrail = false,
  reconnecting = false,
): string {
  if (reconnecting) return t("conversation.indicator.reconnecting");
  if (tool) return t("conversation.indicator.tool", { tool });
  if (guardrail) return t("conversation.indicator.guardrail");
  return escalating ? t("conversation.indicator.escalating") : t("conversation.indicator.thinking");
}

/**
 * Whether a guardrail has fired on the reply still being written — the wait
 * that follows (a re-asked model, a rewrite) is the guardrail at work.
 */
export function guardrailActive(messages: readonly ConversationMessage[], processing: boolean): boolean {
  if (!processing) return false;
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m?.role === "user") return false;
    if (m?.role === "agent" && m.isStreaming) return !!m.guardrails?.length;
  }
  return false;
}

/**
 * `guardrail` says a guardrail fired on the reply being written (see
 * `guardrailActive`): the wait that follows is it re-asking the model or
 * rewriting the answer. A tool call wins over it; it wins over `escalating`.
 */
export function ThinkingIndicator({
  escalating = false,
  tool = null,
  guardrail = false,
  reconnecting = false,
}: {
  escalating?: boolean;
  tool?: string | null;
  guardrail?: boolean;
  /** The stream dropped and the turn is being followed on the server (`reconnecting`). */
  reconnecting?: boolean;
}) {
  const t = useConversationText();
  const mode = reconnecting ? "reconnecting" : tool ? "tool" : guardrail ? "guardrail" : escalating ? "escalating" : "thinking";
  // Hidden from assistive technology: the transcript is aria-busy while this is
  // on screen, so ChatWidget announces indicatorStatusText() from a status
  // region outside it. Announcing here as well would read it twice.
  return (
    <div className="indicator" aria-hidden="true" data-testid={`${mode}-indicator`}>
      <div className="indicator__avatar" aria-hidden="true">
        E
      </div>
      <div className="indicator__bubble">
        <div className="indicator__thinking">
          {mode === "tool" ? (
            <Wrench className="indicator__brain" size="1em" />
          ) : mode === "guardrail" ? (
            <ShieldCheck className="indicator__brain" size="1em" />
          ) : (
            <Brain className="indicator__brain" size="1em" />
          )}
          <span>{indicatorStatusText(escalating, tool, t, guardrail, reconnecting)}</span>
        </div>
      </div>
    </div>
  );
}
