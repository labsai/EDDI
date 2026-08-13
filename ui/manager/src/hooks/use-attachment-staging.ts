/**
 * Shared attachment staging for chat inputs — pick/paste files, upload them to
 * the conversation, track chips through upload → ready/error, and hand the
 * ready set to the send path.
 *
 * Extracted from chat-panel so the operator chat (and any future chat surface)
 * shares one proven implementation instead of re-growing its own: the per-turn
 * cap, StrictMode-safe object-URL lifecycle, conversation-switch reset and
 * unmount cleanup all live here.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";
import {
  AttachmentError,
  MAX_ATTACHMENTS_PER_TURN,
  deleteAttachment,
  isImageMime,
  uploadAttachment,
  type AttachmentResult,
} from "@/lib/api/attachments";

/** A file the user picked, tracked through upload → ready/error. */
export interface PendingAttachment {
  id: string;
  file: File;
  /** Object URL for image previews. */
  previewUrl?: string;
  status: "uploading" | "ready" | "error";
  result?: AttachmentResult;
  error?: string;
}

/** A pending attachment whose upload completed — safe to forward on a turn. */
export type ReadyAttachment = PendingAttachment & { result: AttachmentResult };

/**
 * Files carried by a paste event (screenshots via Ctrl/Cmd+V, files copied
 * from the OS). Empty when the clipboard holds only text — callers must then
 * leave the event alone so normal text paste keeps working.
 */
export function filesFromClipboard(e: React.ClipboardEvent): File[] {
  return Array.from(e.clipboardData?.files ?? []);
}

/** Whether a drag carries files (vs. text/selection drags, which chat drop
 *  zones must ignore so in-page text dragging keeps working). */
function dragHasFiles(e: React.DragEvent): boolean {
  return Array.from(e.dataTransfer?.types ?? []).includes("Files");
}

export interface FileDropZone {
  /** True while a file drag hovers the zone — render the drop overlay. */
  isDragOver: boolean;
  /** Spread onto the drop-zone container element. */
  dropHandlers: {
    onDragEnter?: (e: React.DragEvent) => void;
    onDragOver?: (e: React.DragEvent) => void;
    onDragLeave?: (e: React.DragEvent) => void;
    onDrop?: (e: React.DragEvent) => void;
  };
}

/**
 * Turn a container into a file drop zone feeding {@link AttachmentStaging}'s
 * `stageFiles` (or any file consumer).
 *
 * The enter/leave depth counter exists because dragenter/dragleave fire for
 * every child element crossed — without it the overlay flickers off the moment
 * the cursor moves from the container onto a message bubble. Non-file drags
 * (text selections) pass through untouched.
 */
export function useFileDrop(enabled: boolean, onFiles: (files: File[]) => void): FileDropZone {
  const [isDragOver, setIsDragOver] = useState(false);
  const depth = useRef(0);

  // Disabling mid-drag (e.g. secret mode toggled on) must not strand the
  // overlay in its "on" state.
  useEffect(() => {
    if (!enabled) {
      depth.current = 0;
      setIsDragOver(false);
    }
  }, [enabled]);

  const onDragEnter = useCallback((e: React.DragEvent) => {
    if (!dragHasFiles(e)) return;
    e.preventDefault();
    depth.current += 1;
    setIsDragOver(true);
  }, []);
  const onDragOver = useCallback((e: React.DragEvent) => {
    // preventDefault is what makes the element a legal drop target at all.
    if (dragHasFiles(e)) e.preventDefault();
  }, []);
  const onDragLeave = useCallback((e: React.DragEvent) => {
    if (!dragHasFiles(e)) return;
    depth.current = Math.max(0, depth.current - 1);
    if (depth.current === 0) setIsDragOver(false);
  }, []);
  const onDrop = useCallback(
    (e: React.DragEvent) => {
      if (!dragHasFiles(e)) return;
      e.preventDefault();
      depth.current = 0;
      setIsDragOver(false);
      const files = Array.from(e.dataTransfer.files);
      if (files.length) onFiles(files);
    },
    [onFiles],
  );

  if (!enabled) {
    return { isDragOver: false, dropHandlers: {} };
  }
  return { isDragOver, dropHandlers: { onDragEnter, onDragOver, onDragLeave, onDrop } };
}

export interface AttachmentStaging {
  pendingAttachments: PendingAttachment[];
  isUploading: boolean;
  hasReadyAttachment: boolean;
  /** Stage files from any source (picker, paste, drop) and upload them. */
  stageFiles: (files: File[]) => Promise<void>;
  /** Picker adapter: stages the input's files and clears it for re-selection. */
  handleFileInput: (e: React.ChangeEvent<HTMLInputElement>) => Promise<void>;
  /** Discard one staged chip (frees preview, best-effort deletes the blob). */
  removeAttachment: (id: string) => void;
  /** Discard everything staged (e.g. secret mode switching on). */
  discardAll: () => void;
  /**
   * Drain the ready set for a send: returns the successfully-uploaded entries
   * and clears the staging area. Preview URLs of returned entries stay alive —
   * the sent bubble takes them over; errored chips' previews are freed.
   */
  takeForSend: () => ReadyAttachment[];
}

/**
 * @param conversationId
 *            the conversation uploads are addressed to; staged chips are reset
 *            whenever it changes so a storageRef never crosses conversations.
 * @param ensureConversation
 *            optional lazy-create: surfaces whose conversation starts on first
 *            send (the operator) pass this so attaching a file BEFORE the
 *            first message creates the conversation to upload into. Without it,
 *            staging while `conversationId` is null is a no-op.
 */
export function useAttachmentStaging(
  conversationId: string | null | undefined,
  ensureConversation?: () => Promise<string>,
): AttachmentStaging {
  const { t } = useTranslation();
  const [pendingAttachments, setPendingAttachments] = useState<PendingAttachment[]>([]);
  const isUploading = pendingAttachments.some((a) => a.status === "uploading");
  const hasReadyAttachment = pendingAttachments.some((a) => a.status === "ready");

  // Mirror the pending list in a ref so stageFiles can read a live count (for
  // the per-turn cap) without being re-created on every change.
  const pendingRef = useRef(pendingAttachments);
  useEffect(() => {
    pendingRef.current = pendingAttachments;
  }, [pendingAttachments]);

  // The id uploads actually target. Kept in a ref so a lazy-created id is
  // visible to discard (blob deletes) before the store re-render propagates.
  const conversationIdRef = useRef<string | null | undefined>(conversationId);
  useEffect(() => {
    conversationIdRef.current = conversationId;
  }, [conversationId]);

  // The id stageFiles itself lazily created, when any. The conversation-switch
  // reset below must NOT treat that id arriving as the prop as a switch — it
  // is the very conversation the staged chips were uploaded to.
  const lazyCreatedIdRef = useRef<string | null>(null);

  // Discard a staged attachment: free its object URL and best-effort delete
  // the uploaded blob server-side. Runs OUTSIDE any state updater (StrictMode
  // double-invokes updaters, which would double-fire the revoke / DELETE).
  const discardPending = useCallback((a: PendingAttachment) => {
    if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
    const targetConversation = a.result?.conversationId ?? conversationIdRef.current;
    if (a.status === "ready" && a.result && targetConversation) {
      deleteAttachment(targetConversation, a.result.storageRef).catch(() => {});
    }
  }, []);

  // Reset pending attachments whenever the conversation changes, so a
  // storageRef uploaded to a previous conversation is never sent to a new one.
  // Full discard, not a bare revoke: ready chips' blobs are best-effort
  // DELETEd server-side too (discardPending targets a.result.conversationId,
  // i.e. the conversation they were uploaded to), matching what removing a
  // chip by hand does — otherwise every conversation switch with staged files
  // orphans blobs on the server.
  useEffect(() => {
    // A lazily-created conversation's id propagating back as the prop is not a
    // switch — the staged chips were uploaded to exactly this conversation and
    // must survive it.
    if (conversationId && conversationId === lazyCreatedIdRef.current) {
      lazyCreatedIdRef.current = null;
      return;
    }
    const previous = pendingRef.current;
    if (previous.length === 0) return;
    pendingRef.current = [];
    previous.forEach(discardPending);
    setPendingAttachments([]);
  }, [conversationId, discardPending]);

  // Free any unsent preview URLs if the surface unmounts (route change / input
  // swap) — those transitions don't change conversationId.
  useEffect(
    () => () => {
      pendingRef.current.forEach((a) => a.previewUrl && URL.revokeObjectURL(a.previewUrl));
    },
    [],
  );

  const stageFiles = useCallback(
    async (files: File[]) => {
      if (!files.length) return;

      let targetId = conversationIdRef.current;
      if (!targetId) {
        if (!ensureConversation) return;
        try {
          targetId = await ensureConversation();
          conversationIdRef.current = targetId;
          lazyCreatedIdRef.current = targetId;
        } catch {
          toast.error(t("chat.attachError", "Failed to upload file"));
          return;
        }
      }

      // Enforce the per-turn cap up front against the live count (errored
      // chips don't get forwarded, so they don't consume a slot).
      const activeCount = pendingRef.current.filter((a) => a.status !== "error").length;
      const room = Math.max(0, MAX_ATTACHMENTS_PER_TURN - activeCount);
      if (files.length > room) {
        toast.error(
          t("chat.attachLimit", "You can attach up to {{max}} files per message.", {
            max: MAX_ATTACHMENTS_PER_TURN,
          }),
        );
      }
      const accepted = files.slice(0, room);
      if (!accepted.length) return;

      // Build entries once — object URLs are created here, never inside a
      // state updater (StrictMode double-invokes updaters and would orphan a
      // blob URL).
      const entries: PendingAttachment[] = accepted.map((file, i) => ({
        id: `att-${Date.now()}-${i}-${Math.random().toString(36).slice(2)}`,
        file,
        previewUrl: isImageMime(file.type) ? URL.createObjectURL(file) : undefined,
        status: "uploading",
      }));
      pendingRef.current = [...pendingRef.current, ...entries];
      setPendingAttachments((prev) => [...prev, ...entries]);

      await Promise.all(
        entries.map(async (entry) => {
          try {
            const result = await uploadAttachment(targetId, entry.file);
            setPendingAttachments((prev) =>
              prev.map((a) => (a.id === entry.id ? { ...a, status: "ready", result } : a)),
            );
            if (result.forwardableInline === false) {
              toast.warning(
                t(
                  "chat.attachTooLargeToForward",
                  "{{name}} is stored but too large to send to the model inline.",
                  { name: result.fileName || entry.file.name },
                ),
              );
            }
          } catch (err) {
            const message =
              err instanceof AttachmentError
                ? err.message
                : t("chat.attachError", "Failed to upload file");
            setPendingAttachments((prev) =>
              prev.map((a) => (a.id === entry.id ? { ...a, status: "error", error: message } : a)),
            );
            toast.error(message);
          }
        }),
      );
    },
    [ensureConversation, t],
  );

  const handleFileInput = useCallback(
    async (e: React.ChangeEvent<HTMLInputElement>) => {
      const files = Array.from(e.target.files ?? []);
      // Clear before the async work so picking the same file again re-fires.
      e.target.value = "";
      await stageFiles(files);
    },
    [stageFiles],
  );

  const removeAttachment = useCallback(
    (id: string) => {
      const target = pendingRef.current.find((a) => a.id === id);
      if (target) discardPending(target);
      pendingRef.current = pendingRef.current.filter((a) => a.id !== id);
      setPendingAttachments((prev) => prev.filter((a) => a.id !== id));
    },
    [discardPending],
  );

  const discardAll = useCallback(() => {
    pendingRef.current.forEach(discardPending);
    pendingRef.current = [];
    setPendingAttachments([]);
  }, [discardPending]);

  const takeForSend = useCallback((): ReadyAttachment[] => {
    const ready = pendingRef.current.filter(
      (a): a is ReadyAttachment => a.status === "ready" && !!a.result,
    );
    // Ready entries hand their preview URL to the sent message (kept alive for
    // the bubble thumbnail); free the URLs of any not-forwarded (errored or
    // still-uploading) chips being dropped here so they don't leak.
    const forwarded = new Set(ready.map((a) => a.previewUrl).filter(Boolean));
    pendingRef.current.forEach((a) => {
      if (a.previewUrl && !forwarded.has(a.previewUrl)) URL.revokeObjectURL(a.previewUrl);
    });
    pendingRef.current = [];
    setPendingAttachments([]);
    return ready;
  }, []);

  return {
    pendingAttachments,
    isUploading,
    hasReadyAttachment,
    stageFiles,
    handleFileInput,
    removeAttachment,
    discardAll,
    takeForSend,
  };
}
