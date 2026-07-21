/* ──────────────────────────────────────────────
   ChatInput — Auto-growing textarea + send button
   With 🔒 secret mode toggle for client-initiated secret input.
   ────────────────────────────────────────────── */

import { useState, useRef, useCallback, type KeyboardEvent } from "react";
import { useChatState, useChatDispatch } from "@/store/chat-store";
import { uploadAttachment, MAX_ATTACHMENTS_PER_TURN } from "@/api/attachments-api";
import { ApiError } from "@/api/http";

/** Turn an upload failure into copy that names the actual reason. */
function describeUploadFailure(err: unknown, fileName: string): string {
  if (err instanceof ApiError) {
    if (err.status === 413 || err.body.includes("ATTACHMENT_TOO_LARGE")) {
      return `${fileName} is too large to upload.`;
    }
    if (err.body.includes("ATTACHMENT_REJECTED")) {
      return `${fileName} was rejected — that file type is not accepted.`;
    }
    if (err.status === 401 || err.status === 403) {
      return `You are not allowed to attach files to this conversation.`;
    }
  }
  return `Failed to upload ${fileName}.`;
}

interface ChatInputProps {
  onSend: (message: string, isSecret?: boolean) => void;
  disabled?: boolean;
  conversationId?: string | null;
}

export function ChatInput({ onSend, disabled, conversationId }: ChatInputProps) {
  const { isProcessing, config, isSecretMode, pendingAttachments } = useChatState();
  const dispatch = useChatDispatch();
  const [value, setValue] = useState("");
  const [secretVisible, setSecretVisible] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const handleSend = useCallback(() => {
    const trimmed = value.trim();
    // An attachment on its own is a valid turn — the file is the message.
    const hasAttachments = pendingAttachments.length > 0;
    if ((!trimmed && !hasAttachments) || disabled || isProcessing) return;
    onSend(trimmed, isSecretMode);
    setValue("");
    if (isSecretMode) {
      dispatch({ type: "TOGGLE_SECRET_MODE" });
      setSecretVisible(false);
    }
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  }, [value, disabled, isProcessing, isSecretMode, onSend, dispatch, pendingAttachments.length]);

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

  const canSend =
    (value.trim().length > 0 || pendingAttachments.length > 0) &&
    !disabled &&
    !isProcessing;

  // ── Attachment upload ──
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isUploading, setIsUploading] = useState(false);

  const handleAttach = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !conversationId) return;

    if (pendingAttachments.length >= MAX_ATTACHMENTS_PER_TURN) {
      dispatch({
        type: "ADD_MESSAGE",
        message: {
          id: `error-${Date.now()}-${Math.random()}`,
          role: "agent",
          content: `⚠️ You can attach at most ${MAX_ATTACHMENTS_PER_TURN} files per message.`,
          timestamp: Date.now(),
        },
      });
      if (fileInputRef.current) fileInputRef.current.value = "";
      return;
    }

    setIsUploading(true);
    try {
      const result = await uploadAttachment(conversationId, file);
      // Stage it. The ref reaches the agent as an attachment_N context entry
      // when the next message is sent — embedding it in the message text was
      // silently ignored by the backend.
      dispatch({ type: "ADD_ATTACHMENT", attachment: result });
    } catch (err) {
      dispatch({
        type: "ADD_MESSAGE",
        message: {
          id: `error-${Date.now()}-${Math.random()}`,
          role: "agent",
          content: `⚠️ ${describeUploadFailure(err, file.name)}`,
          timestamp: Date.now(),
        },
      });
    } finally {
      setIsUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  }, [conversationId, dispatch, pendingAttachments.length]);

  return (
    <div className="chat-input-wrapper">
      {pendingAttachments.length > 0 && (
        <div className="chat-attachments" data-testid="attachment-chips">
          {pendingAttachments.map((a) => (
            <span
              key={a.storageRef}
              className="chat-attachments__chip"
              data-testid="attachment-chip"
            >
              <span className="chat-attachments__name">📎 {a.fileName}</span>
              <button
                type="button"
                className="chat-attachments__remove"
                onClick={() =>
                  dispatch({ type: "REMOVE_ATTACHMENT", storageRef: a.storageRef })
                }
                aria-label={`Remove ${a.fileName}`}
                data-testid="attachment-remove"
              >
                ×
              </button>
            </span>
          ))}
        </div>
      )}
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
            aria-label={secretVisible ? "Hide secret" : "Show secret"}
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
    </div>
  );
}
