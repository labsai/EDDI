/* ──────────────────────────────────────────────
   TypingIndicator — Bouncing dots (agent is typing)
   ThinkingIndicator — Pulsing brain (agent is thinking)
   EscalatingIndicator — Model cascade moved to a stronger model
   ────────────────────────────────────────────── */

/** Three bouncing dots shown while the agent is composing a response. */
export function TypingIndicator() {
  return (
    <div className="indicator" data-testid="typing-indicator">
      <div className="indicator__avatar">
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

/** Shown when the agent is in a thinking/reasoning phase (e.g. tool calls, RAG). */
export function ThinkingIndicator() {
  return (
    <div className="indicator" data-testid="thinking-indicator">
      <div className="indicator__avatar">
        E
      </div>
      <div className="indicator__bubble">
        <div className="indicator__thinking">
          <span className="indicator__brain">🧠</span>
          <span>Thinking…</span>
        </div>
      </div>
    </div>
  );
}

/**
 * Shown when the model cascade escalates to a more capable model.
 *
 * Deliberately generic. The escalation event carries the model name, the
 * confidence that triggered it and the elapsed time, but none of that is the
 * end user's business — it is operator detail, and surfacing it would invite
 * questions about which model answered. Reuses ThinkingIndicator's styling so
 * the change reads as the same agent working harder, not a new state.
 */
export function EscalatingIndicator() {
  return (
    <div className="indicator" data-testid="escalating-indicator">
      <div className="indicator__avatar">
        E
      </div>
      <div className="indicator__bubble">
        <div className="indicator__thinking">
          <span className="indicator__brain">⚡</span>
          <span>Thinking harder…</span>
        </div>
      </div>
    </div>
  );
}
