/* ──────────────────────────────────────────────
   ChatInput — Auto-growing textarea + send button
   With 🔒 secret mode toggle for client-initiated secret input.
   ────────────────────────────────────────────── */

import { useState, useRef, useCallback, type KeyboardEvent } from "react";
import { useChatState, useChatDispatch } from "@/store/chat-store";
import { uploadAttachment } from "@/api/chat-api";

interface ChatInputProps {
  onSend: (message: string, isSecret?: boolean) => void;
  disabled?: boolean;
  conversationId?: string | null;
}

export function ChatInput({ onSend, disabled, conversationId }: ChatInputProps) {
  const { isProcessing, config, isSecretMode } = useChatState();
  const dispatch = useChatDispatch();
  const [value, setValue] = useState("");
  const [secretVisible, setSecretVisible] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const handleSend = useCallback(() => {
    const trimmed = value.trim();
    if (!trimmed || disabled || isProcessing) return;
    onSend(trimmed, isSecretMode);
    setValue("");
    if (isSecretMode) {
      dispatch({ type: "TOGGLE_SECRET_MODE" });
      setSecretVisible(false);
    }
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  }, [value, disabled, isProcessing, isSecretMode, onSend, dispatch]);

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLTextAreaElement | HTMLInputElement>) => {
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

  const toggleSecretMode = useCallback(() => {
    dispatch({ type: "TOGGLE_SECRET_MODE" });
    setSecretVisible(false);
  }, [dispatch]);

  const canSend = value.trim().length > 0 && !disabled && !isProcessing;

  // ── Attachment upload ──
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isUploading, setIsUploading] = useState(false);

  const handleAttach = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !conversationId) return;
    setIsUploading(true);
    try {
      const result = await uploadAttachment(conversationId, file);
      onSend(`📎 ${file.name} [ref:${result.storageRef}]`);
    } catch {
      // silently fail — user sees no message sent
    } finally {
      setIsUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  }, [conversationId, onSend]);

  return (
    <div className="chat-input">
      {/* Hidden file input for attachments */}
      <input
        ref={fileInputRef}
        type="file"
        style={{ display: "none" }}
        onChange={handleAttach}
        data-testid="chat-file-input"
      />
      {/* 📎 Attach button */}
      <button
        type="button"
        className="chat-input__attach"
        onClick={() => fileInputRef.current?.click()}
        disabled={!conversationId || isUploading}
        title="Attach file"
        data-testid="chat-attach-btn"
        aria-label="Attach file"
      >
        {isUploading ? "⏳" : "📎"}
      </button>
      {/* 🔒 Secret mode toggle */}
      <button
        type="button"
        className={`chat-input__secret-toggle ${isSecretMode ? "chat-input__secret-toggle--active" : ""}`}
        onClick={toggleSecretMode}
        title={isSecretMode ? "Secret mode ON — input will be encrypted" : "Toggle secret mode"}
        data-testid="chat-secret-toggle"
        aria-label="Toggle secret mode"
      >
        {isSecretMode ? "🔒" : "🔓"}
      </button>

      {isSecretMode ? (
        /* Secret mode: password input with eye toggle */
        <div className="chat-input__secret-wrapper">
          <input
            type={secretVisible ? "text" : "password"}
            data-testid="chat-input"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Enter secret value..."
            disabled={disabled}
            className="chat-input__secret-field"
            autoComplete="off"
          />
          <button
            type="button"
            className="chat-input__eye-toggle"
            onClick={() => setSecretVisible((v) => !v)}
            title={secretVisible ? "Hide" : "Show"}
            data-testid="chat-eye-toggle"
          >
            {secretVisible ? "👁" : "👁‍🗨"}
          </button>
        </div>
      ) : (
        /* Normal mode: auto-growing textarea */
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
      )}

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
