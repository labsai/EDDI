/* ──────────────────────────────────────────────
   TypingIndicator — Bouncing dots (agent is typing)
   ThinkingIndicator — Pulsing brain (agent is thinking)
   ────────────────────────────────────────────── */

/** Three bouncing dots shown while the agent is composing a response. */
export function TypingIndicator() {
  return (
    <div className="indicator" data-testid="typing-indicator">
      <div className="message__avatar" style={{
        background: "var(--chat-surface-raised)",
        color: "var(--chat-text-accent)",
        border: "1px solid var(--chat-agent-border)",
        width: 32,
        height: 32,
        borderRadius: "50%",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontSize: "0.75rem",
        fontWeight: 600,
        flexShrink: 0,
      }}>
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
      <div className="message__avatar" style={{
        background: "var(--chat-surface-raised)",
        color: "var(--chat-text-accent)",
        border: "1px solid var(--chat-agent-border)",
        width: 32,
        height: 32,
        borderRadius: "50%",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontSize: "0.75rem",
        fontWeight: 600,
        flexShrink: 0,
      }}>
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
