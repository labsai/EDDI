/* ──────────────────────────────────────────────
   TypingIndicator — Bouncing dots (agent is typing)
   ThinkingIndicator — Pulsing brain; `escalating` for a model-cascade step up,
                       `tool` while a tool call is running
   ────────────────────────────────────────────── */

import { Brain, Wrench } from "lucide-react";

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
export function indicatorStatusText(escalating: boolean, tool: string | null): string {
  if (tool) return `Using ${tool}…`;
  return escalating ? "Taking a closer look…" : "Thinking…";
}

export function ThinkingIndicator({
  escalating = false,
  tool = null,
}: {
  escalating?: boolean;
  tool?: string | null;
}) {
  const mode = tool ? "tool" : escalating ? "escalating" : "thinking";
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
          ) : (
            <Brain className="indicator__brain" size="1em" />
          )}
          <span>{indicatorStatusText(escalating, tool)}</span>
        </div>
      </div>
    </div>
  );
}
