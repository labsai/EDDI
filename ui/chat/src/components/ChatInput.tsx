/* ──────────────────────────────────────────────
   ChatInput — Auto-growing textarea + send button
   ────────────────────────────────────────────── */

import { useState, useRef, useCallback, type KeyboardEvent } from "react";
import { useChatState } from "@/store/chat-store";

interface ChatInputProps {
  onSend: (message: string) => void;
  disabled?: boolean;
}

export function ChatInput({ onSend, disabled }: ChatInputProps) {
  const { isProcessing, config } = useChatState();
  const [value, setValue] = useState("");
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const handleSend = useCallback(() => {
    const trimmed = value.trim();
    if (!trimmed || disabled || isProcessing) return;
    onSend(trimmed);
    setValue("");
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  }, [value, disabled, isProcessing, onSend]);

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        handleSend();
      }
    },
    [handleSend],
  );

  const handleInput = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 150)}px`;
  }, []);

  const canSend = value.trim().length > 0 && !disabled && !isProcessing;

  return (
    <div className="chat-input">
      <textarea
        ref={textareaRef}
        data-testid="chat-input"
        value={value}
        onChange={(e) => {
          setValue(e.target.value);
          handleInput();
        }}
        onKeyDown={handleKeyDown}
        placeholder={config.placeholder ?? "Type a message..."}
        disabled={disabled}
        rows={1}
        className="chat-input__textarea"
      />
      <button
        data-testid="chat-send"
        onClick={handleSend}
        disabled={!canSend}
        className={`chat-input__send ${canSend ? "chat-input__send--active" : "chat-input__send--disabled"}`}
        aria-label="Send message"
      >
        {isProcessing ? (
          <span className="chat-input__spinner" />
        ) : (
          "➤"
        )}
      </button>
    </div>
  );
}
