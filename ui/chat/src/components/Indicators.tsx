/* ──────────────────────────────────────────────
   TypingIndicator — Bouncing dots (agent is typing)
   ThinkingIndicator — Pulsing brain; `escalating` for a model-cascade step up
   ────────────────────────────────────────────── */

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
 */
export function ThinkingIndicator({ escalating = false }: { escalating?: boolean }) {
  return (
    <div
      className="indicator"
      role="status"
      aria-live="polite"
      data-testid={escalating ? "escalating-indicator" : "thinking-indicator"}
    >
      <div className="indicator__avatar" aria-hidden="true">
        E
      </div>
      <div className="indicator__bubble">
        <div className="indicator__thinking">
          <span className="indicator__brain" aria-hidden="true">
            🧠
          </span>
          <span>{escalating ? "Taking a closer look…" : "Thinking…"}</span>
        </div>
      </div>
    </div>
  );
}
