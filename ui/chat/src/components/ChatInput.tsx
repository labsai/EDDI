/* ──────────────────────────────────────────────
   ChatInput — Auto-growing textarea + send button
   With 🔒 secret mode toggle for client-initiated secret input.
   ────────────────────────────────────────────── */

import { useState, useRef, useCallback, useEffect, type KeyboardEvent } from "react";
import { useChatState, useChatDispatch } from "@/store/chat-store";
import {
  uploadAttachment,
  deleteAttachment,
  MAX_ATTACHMENTS_PER_TURN,
} from "@/api/attachments-api";
import { ApiError, errorPayload } from "@/api/http";

/** Turn an upload failure into copy that names the actual reason. */
function describeUploadFailure(err: unknown, fileName: string): string {
  if (err instanceof ApiError) {
    const { code, message } = errorPayload(err);
    // Quarkus enforces its own request-body cap before the attachment layer
    // runs, so an oversize upload can arrive as a bare 413 with no envelope.
    if (err.status === 413 || code === "ATTACHMENT_TOO_LARGE") {
      return `${fileName} is too large to upload.`;
    }
    if (err.status === 401 || err.status === 403) {
      return `You are not allowed to attach files to this conversation.`;
    }
    // ATTACHMENT_REJECTED is a catch-all: an unaccepted MIME type, the
    // per-conversation file-count and byte quotas, and empty files all arrive
    // under it. Only the server's own text separates "that type is not
    // accepted" from "delete some attachments first", so prefer it to a guess.
    if (message) return `${fileName} was rejected — ${message}`;
    if (code) return `${fileName} was rejected.`;
  }
  return `Failed to upload ${fileName}.`;
}

interface ChatInputProps {
  onSend: (message: string, isSecret?: boolean) => void;
  disabled?: boolean;
  conversationId?: string | null;
}

export function ChatInput({ onSend, disabled, conversationId }: ChatInputProps) {
  const { isProcessing, config, isSecretMode, pendingAttachments, restoreDraft } =
    useChatState();
  const dispatch = useChatDispatch();
  const [value, setValue] = useState("");
  const [secretVisible, setSecretVisible] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // A turn the server refused (409) was never consumed — put the text back so
  // the user does not have to retype it.
  useEffect(() => {
    if (restoreDraft === null) return;
    setValue(restoreDraft);
    dispatch({ type: "CLEAR_RESTORE_DRAFT" });
    textareaRef.current?.focus();
  }, [restoreDraft, dispatch]);

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
  /**
   * Names of files currently uploading, shown as placeholder chips ahead of the
   * staged ones. Without them a large file over a slow link leaves the composer
   * looking untouched for several seconds.
   */
  const [uploading, setUploading] = useState<string[]>([]);
  const isUploading = uploading.length > 0;

  const notify = useCallback(
    (content: string) => {
      dispatch({
        type: "ADD_MESSAGE",
        message: {
          id: `error-${Date.now()}-${Math.random()}`,
          role: "agent",
          content,
          timestamp: Date.now(),
        },
      });
    },
    [dispatch],
  );

  const handleAttach = useCallback(
    async (e: React.ChangeEvent<HTMLInputElement>) => {
      const picked = Array.from(e.target.files ?? []);
      // Reset immediately so re-picking the same file still fires a change.
      if (fileInputRef.current) fileInputRef.current.value = "";
      if (!picked.length || !conversationId) return;

      // Room is computed once for the whole batch. Checking one file at a time
      // would let a multi-file pick sail past the cap, and the backend drops
      // the excess server-side — so the UI must not pretend it was sent.
      const room =
        MAX_ATTACHMENTS_PER_TURN - pendingAttachments.length - uploading.length;
      if (room <= 0) {
        notify(
          `⚠️ You can attach at most ${MAX_ATTACHMENTS_PER_TURN} files per message.`,
        );
        return;
      }
      const accepted = picked.slice(0, room);
      if (accepted.length < picked.length) {
        notify(
          `⚠️ You can attach at most ${MAX_ATTACHMENTS_PER_TURN} files per message — ` +
            `${picked.length - accepted.length} not attached.`,
        );
      }

      setUploading((prev) => [...prev, ...accepted.map((f) => f.name)]);
      await Promise.all(
        accepted.map(async (file) => {
          try {
            const result = await uploadAttachment(conversationId, file);
            // Stage it. The ref reaches the agent as an attachment_N context
            // entry when the next message is sent — embedding it in the message
            // text was silently ignored by the backend.
            dispatch({ type: "ADD_ATTACHMENT", attachment: result });
          } catch (err) {
            notify(`⚠️ ${describeUploadFailure(err, file.name)}`);
          } finally {
            // Drop one placeholder by name; duplicates are interchangeable.
            setUploading((prev) => {
              const i = prev.indexOf(file.name);
              return i === -1 ? prev : [...prev.slice(0, i), ...prev.slice(i + 1)];
            });
          }
        }),
      );
    },
    [conversationId, dispatch, notify, pendingAttachments.length, uploading.length],
  );

  const handleRemoveAttachment = useCallback(
    (storageRef: string) => {
      // Delete server-side too. Only unsent attachments are removable here, so
      // this can never orphan a blob a sent turn still references — whereas
      // skipping it leaves the file in the store for the life of the
      // conversation, counting against the per-conversation file and byte
      // quotas until the user can no longer attach anything at all.
      // Fire-and-forget: the chip goes regardless of what the server says.
      if (conversationId) {
        deleteAttachment(conversationId, storageRef).catch(() => {});
      }
      dispatch({ type: "REMOVE_ATTACHMENT", storageRef });
    },
    [conversationId, dispatch],
  );

  return (
    <div className="chat-input-wrapper">
      {(pendingAttachments.length > 0 || isUploading) && (
        /* A live region: an upload that fails posts its reason into the
           transcript, which is not announced, so without this a screen-reader
           user gets no feedback that anything happened at all. */
        <div
          className="chat-attachments"
          data-testid="attachment-chips"
          role="status"
          aria-live="polite"
        >
          {pendingAttachments.map((a) => (
            <span
              key={a.storageRef}
              className={
                a.forwardableInline === false
                  ? "chat-attachments__chip chat-attachments__chip--warn"
                  : "chat-attachments__chip"
              }
              data-testid="attachment-chip"
            >
              <span className="chat-attachments__name">📎 {a.fileName}</span>
              {a.forwardableInline === false && (
                <span
                  className="chat-attachments__warn"
                  data-testid="attachment-warn"
                >
                  too large to send to the model
                </span>
              )}
              <button
                type="button"
                className="chat-attachments__remove"
                onClick={() => handleRemoveAttachment(a.storageRef)}
                aria-label={`Remove ${a.fileName}`}
                data-testid="attachment-remove"
              >
                ×
              </button>
            </span>
          ))}
          {uploading.map((name, i) => (
            <span
              key={`uploading-${i}-${name}`}
              className="chat-attachments__chip chat-attachments__chip--uploading"
              data-testid="attachment-chip-uploading"
            >
              <span className="chat-attachments__name">⏳ {name}</span>
            </span>
          ))}
        </div>
      )}
    <div className="chat-input">
      {/* Hidden file input for attachments */}
      <input
        ref={fileInputRef}
        type="file"
        multiple
        style={{ display: "none" }}
        onChange={handleAttach}
        data-testid="chat-file-input"
      />
      {/* 📎 Attach button */}
      <button
        type="button"
        className="chat-input__attach"
        onClick={() => fileInputRef.current?.click()}
        disabled={!conversationId || isUploading || isProcessing || disabled}
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
