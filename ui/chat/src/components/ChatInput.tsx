/* ──────────────────────────────────────────────
   ChatInput — Auto-growing textarea + send button
   With 🔒 secret mode toggle for client-initiated secret input.
   ────────────────────────────────────────────── */

import {
  useState,
  useRef,
  useCallback,
  useEffect,
  useMemo,
  type KeyboardEvent,
} from "react";
import { useChatState, useChatDispatch } from "@/store/chat-store";
import {
  uploadAttachment,
  deleteAttachment,
  MAX_ATTACHMENTS_PER_TURN,
  type AttachmentResult,
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
    // ATTACHMENT_REJECTED is a catch-all: a declared-vs-detected MIME mismatch
    // (there is no type allowlist), the per-conversation file-count and byte
    // quotas, empty files — and on Postgres any wrapped SQLException, so an
    // outage arrives dressed as a rejection too. Only the server's own text
    // separates them, so prefer it to a guess.
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
  /**
   * Files currently uploading, shown as placeholder chips ahead of the staged
   * ones. Without them a large file over a slow link leaves the composer
   * looking untouched for several seconds. Carries a stable id so resolving a
   * middle entry does not remount every chip after it.
   *
   * Declared up here because sending is blocked while it is non-empty.
   */
  const [uploading, setUploading] = useState<{ id: number; name: string }[]>([]);
  const isUploading = uploading.length > 0;
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
    // Sending mid-upload would go out WITHOUT the file: the staged list is
    // still empty, CLEAR_ATTACHMENTS then fires, and the upload lands
    // afterwards as a chip for the NEXT turn — leaving the user looking at an
    // attachment they believe they already sent.
    if ((!trimmed && !hasAttachments) || disabled || isProcessing || isUploading) {
      return;
    }
    onSend(trimmed, isSecretMode);
    setValue("");
    if (isSecretMode) {
      dispatch({ type: "TOGGLE_SECRET_MODE" });
      setSecretVisible(false);
    }
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  }, [
    value,
    disabled,
    isProcessing,
    isUploading,
    isSecretMode,
    onSend,
    dispatch,
    pendingAttachments.length,
  ]);

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
    !isProcessing &&
    !isUploading;

  // ── Attachment upload ──
  const fileInputRef = useRef<HTMLInputElement>(null);
  const attachBtnRef = useRef<HTMLButtonElement>(null);
  const chipsRef = useRef<HTMLDivElement>(null);
  const uploadSeq = useRef(0);
  const atCapacity =
    pendingAttachments.length + uploading.length >= MAX_ATTACHMENTS_PER_TURN;

  /**
   * Assistive-tech announcements.
   *
   * This region is ALWAYS mounted, deliberately. A live region has to exist in
   * the accessibility tree before its content changes — one that appears
   * already populated is not announced — so the conditional chip strip could
   * never announce its own first chip. Upload failures are worse still: they
   * only ever *remove* a placeholder, and the default `aria-relevant` does not
   * cover removals, so nothing at all was being said.
   */
  const [live, setLive] = useState("");
  const announce = useCallback((text: string) => {
    // Re-setting the identical string leaves the DOM untouched, and an
    // unchanged region is not re-announced. Alternate an invisible suffix so
    // the same message twice in a row still speaks.
    setLive((prev) => (prev === text ? `${text}​` : text));
  }, []);

  /** Post a problem into the transcript and speak it. */
  const notify = useCallback(
    (sentence: string) => {
      dispatch({
        type: "ADD_MESSAGE",
        message: {
          id: `error-${Date.now()}-${Math.random()}`,
          role: "agent",
          content: `⚠️ ${sentence}`,
          timestamp: Date.now(),
        },
      });
      announce(sentence);
    },
    [dispatch, announce],
  );

  /**
   * Removing a chip unmounts the focused button. Without this, focus falls to
   * <body> and keyboard/screen-reader users lose their place in the composer.
   */
  const focusIdxRef = useRef<number | null>(null);
  useEffect(() => {
    const idx = focusIdxRef.current;
    if (idx === null) return;
    focusIdxRef.current = null;
    const btns = chipsRef.current?.querySelectorAll<HTMLButtonElement>(
      '[data-testid="attachment-remove"]',
    );
    if (btns && btns.length > 0) btns[Math.min(idx, btns.length - 1)]?.focus();
    else attachBtnRef.current?.focus();
  }, [pendingAttachments]);

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
          `You can attach at most ${MAX_ATTACHMENTS_PER_TURN} files per message.`,
        );
        return;
      }
      const accepted = picked.slice(0, room);
      if (accepted.length < picked.length) {
        // Name the casualties: slice() keeps FileList order, which is
        // OS-determined, so "2 not attached" leaves the user guessing which.
        notify(
          `You can attach at most ${MAX_ATTACHMENTS_PER_TURN} files per message. ` +
            `Not attached: ${picked
              .slice(room)
              .map((f) => f.name)
              .join(", ")}`,
        );
      }

      const staged = accepted.map((file) => ({
        file,
        id: (uploadSeq.current += 1),
      }));
      setUploading((prev) => [
        ...prev,
        ...staged.map(({ id, file }) => ({ id, name: file.name })),
      ]);
      await Promise.all(
        staged.map(async ({ file, id }) => {
          try {
            const result = await uploadAttachment(conversationId, file);
            // Stage it. The ref reaches the agent as an attachment_N context
            // entry when the next message is sent — embedding it in the message
            // text was silently ignored by the backend.
            dispatch({ type: "ADD_ATTACHMENT", attachment: result });
            announce(
              result.forwardableInline === false
                ? `${file.name} attached, but it is too large to send directly — the assistant may not be able to read it.`
                : `${file.name} attached.`,
            );
          } catch (err) {
            notify(describeUploadFailure(err, file.name));
          } finally {
            setUploading((prev) => prev.filter((u) => u.id !== id));
          }
        }),
      );
    },
    [
      conversationId,
      dispatch,
      notify,
      announce,
      pendingAttachments.length,
      uploading.length,
    ],
  );

  /** File names staged more than once, so their remove buttons can disambiguate. */
  const duplicateNames = useMemo(() => {
    const seen = new Set<string>();
    const dupes = new Set<string>();
    for (const a of pendingAttachments) {
      if (seen.has(a.fileName)) dupes.add(a.fileName);
      else seen.add(a.fileName);
    }
    return dupes;
  }, [pendingAttachments]);

  const handleRemoveAttachment = useCallback(
    (a: AttachmentResult, index: number) => {
      const { storageRef, fileName } = a;
      focusIdxRef.current = index;
      // Delete server-side too. Only unsent attachments are removable here, so
      // this can never orphan a blob a sent turn still references — whereas
      // skipping it leaves the file in the store for the life of the
      // conversation, counting against the per-conversation file and byte
      // quotas until the user can no longer attach anything at all.
      // Fire-and-forget: the chip goes regardless of what the server says.
      // Prefer the id the upload reported over whatever is current now.
      const owner = a.conversationId ?? conversationId;
      if (owner) {
        deleteAttachment(owner, storageRef).catch(() => {});
      }
      dispatch({ type: "REMOVE_ATTACHMENT", storageRef });
      announce(`${fileName} removed.`);
    },
    [conversationId, dispatch, announce],
  );

  return (
    <div className="chat-input-wrapper">
      {/* Always mounted — see `announce`. */}
      <div className="chat-sr-only" role="status" aria-live="polite" data-testid="chat-live">
        {live}
      </div>

      {(pendingAttachments.length > 0 || isUploading) && (
        <div className="chat-attachments" data-testid="attachment-chips" ref={chipsRef}>
          {pendingAttachments.map((a, i) => (
            <span
              key={a.storageRef}
              className={
                a.forwardableInline === false
                  ? "chat-attachments__chip chat-attachments__chip--warn"
                  : "chat-attachments__chip"
              }
              data-testid="attachment-chip"
            >
              <span className="chat-attachments__name">
                <span aria-hidden="true">📎</span> {a.fileName}
              </span>
              <button
                type="button"
                className="chat-attachments__remove"
                onClick={() => handleRemoveAttachment(a, i)}
                // Two files can share a name; a bare "Remove report.pdf" twice
                // gives a screen-reader user no way to tell them apart.
                aria-label={
                  duplicateNames.has(a.fileName)
                    ? `Remove ${a.fileName} (${i + 1} of ${pendingAttachments.length})`
                    : `Remove ${a.fileName}`
                }
                aria-describedby={
                  a.forwardableInline === false ? `warn-${a.storageRef}` : undefined
                }
                data-testid="attachment-remove"
              >
                ×
              </button>
              {/* Last, so it takes its own row below the name — see the CSS. */}
              {a.forwardableInline === false && (
                <span
                  id={`warn-${a.storageRef}`}
                  className="chat-attachments__warn"
                  data-testid="attachment-warn"
                >
                  too large to send directly
                </span>
              )}
            </span>
          ))}
          {uploading.map((u) => (
            <span
              key={`uploading-${u.id}`}
              className="chat-attachments__chip chat-attachments__chip--uploading"
              aria-busy="true"
              data-testid="attachment-chip-uploading"
            >
              <span className="chat-attachments__name">
                <span aria-hidden="true">⏳</span> {u.name}
              </span>
              {/* The ⏳ is the only visual difference from a staged chip. */}
              <span className="chat-sr-only">Uploading</span>
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
        ref={attachBtnRef}
        type="button"
        className="chat-input__attach"
        onClick={() => fileInputRef.current?.click()}
        // Gate on the cap, not on "an upload is running" — blocking the whole
        // button while one large file uploads defeats the parallel batch.
        disabled={!conversationId || atCapacity || isProcessing || disabled}
        title={atCapacity ? `Attachment limit (${MAX_ATTACHMENTS_PER_TURN}) reached` : "Attach file"}
        data-testid="chat-attach-btn"
        aria-label={isUploading ? "Attach file (upload in progress)" : "Attach file"}
      >
        <span aria-hidden="true">{isUploading ? "⏳" : "📎"}</span>
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
