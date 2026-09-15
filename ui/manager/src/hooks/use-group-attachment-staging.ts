import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { MAX_ATTACHMENT_BYTES } from "@/lib/api/attachments";
import {
  MAX_GROUP_ATTACHMENTS,
  MAX_GROUP_ATTACHMENTS_TOTAL_BYTES,
  type GroupAttachmentRef,
} from "@/lib/api/groups";

/**
 * Staging for the files a group discussion starts with: dedupe, both caps,
 * base64 encoding, and a queue so two rapid picks cannot both reserve the same
 * budget.
 *
 * Extracted from `DiscussionInput` so the Workforce board's composer can share
 * it rather than grow a second, thinner copy. It had one: a single `File` held
 * in state that `workforce-board` never read, so the paperclip staged a file,
 * showed a chip, and dropped it on send with no error.
 */

/** A picked file plus the base64 payload the group endpoint takes. */
export interface PendingGroupAttachment extends GroupAttachmentRef {
  id: string;
  sizeBytes: number;
}

/**
 * Read a File as bare base64 — no `data:` prefix, which is what the backend's
 * `AttachmentRef.data` expects. FileReader yields a data URI, so the header is
 * stripped here rather than in every caller.
 */
function readAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error ?? new Error("read failed"));
    reader.onload = () => {
      const result = String(reader.result ?? "");
      const comma = result.indexOf(",");
      resolve(comma >= 0 ? result.slice(comma + 1) : result);
    };
    reader.readAsDataURL(file);
  });
}

/**
 * Identity of a picked file, for de-duplicating repeat selections. Name + size +
 * mtime is as close as the File API gets without reading the bytes.
 */
function attachmentId(file: File): string {
  return `${file.name}-${file.size}-${file.lastModified}`;
}

/** Compact byte size for an attachment chip — the total cap is otherwise invisible. */
export function formatAttachmentBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${Math.round(kb)} KB`;
  return `${(kb / 1024).toFixed(kb / 1024 < 10 ? 1 : 0)} MB`;
}

export interface GroupAttachmentStaging {
  attachments: PendingGroupAttachment[];
  /** True while a selection is being read — disable the picker and submit. */
  isStaging: boolean;
  /** Stage a materialized array of files (not a live `FileList`; see below). */
  addFiles: (files: File[]) => Promise<void>;
  remove: (id: string) => void;
  clear: () => void;
  /** The wire shape, or `null` when nothing is staged. */
  toRefs: () => GroupAttachmentRef[] | null;
}

/**
 * @param enabled whether attaching is allowed at all. A continuation is a 400
 *   server-side — files are shared with member agents only when a discussion
 *   starts — so anything staged is dropped the moment this goes false, rather
 *   than sitting in the composer as a request the backend will refuse.
 */
export function useGroupAttachmentStaging(enabled: boolean): GroupAttachmentStaging {
  const { t } = useTranslation();
  const [attachments, setAttachments] = useState<PendingGroupAttachment[]>([]);
  const [isStaging, setIsStaging] = useState(false);

  /**
   * What is staged, tracked synchronously. `attachments` state is only visible
   * to the NEXT render, so two selections made before the first finishes reading
   * would both compute their dedupe set and byte budget from the same stale
   * array and then merge — exceeding both caps. This ref is updated the moment
   * files are accepted, so a serialized second run sees the first run's work.
   */
  const stagedRef = useRef<PendingGroupAttachment[]>([]);
  /** Tail of the staging queue — see `addFiles`. */
  const queueRef = useRef<Promise<void>>(Promise.resolve());
  /**
   * Bumped whenever staging is cleared. A read already awaiting `readAsBase64`
   * would otherwise append its result afterwards and put chips back on a
   * composer that has since switched to a continuation, where the paperclip is
   * hidden and nothing would be sent.
   */
  const generationRef = useRef(0);

  useEffect(() => {
    if (!enabled) {
      generationRef.current++;
      stagedRef.current = [];
      setAttachments([]);
    }
  }, [enabled]);

  // Removals and a post-submit reset go through state, so mirror it back.
  useEffect(() => {
    stagedRef.current = attachments;
  }, [attachments]);

  /**
   * Stage one selection: dedupe, apply both caps, base64-encode, append.
   *
   * Reads and writes `stagedRef` rather than the `attachments` state so that
   * runs serialized behind each other observe one another's results.
     *
   * @param generation the staging generation at the moment this selection was
   *   PICKED, not the one current when it reaches the front of the queue. Read
   *   here, a selection queued behind a slow read and cleared while it waited
   *   would capture the post-clear value and pass its own guard.
   */
  const stageFiles = useCallback(
    // Takes an ARRAY, not the live FileList. The caller resets `input.value` to
    // re-arm the change event, and that clears `input.files` — so the list has
    // to be materialized synchronously by the caller rather than read across an
    // `await` in here.
    async (files: File[], generation: number) => {
      if (!files.length) return;
      const tooMany = () =>
        t("groups.attachmentLimit", "At most {{max}} attachments per discussion", {
          max: MAX_GROUP_ATTACHMENTS,
        });
      const staged = stagedRef.current;
      const room = MAX_GROUP_ATTACHMENTS - staged.length;
      if (room <= 0) {
        toast.error(tooMany());
        return;
      }
      // Drop duplicates BEFORE the count and size budgets. Charging a file that
      // is then discarded as a duplicate would reject a later legitimate one.
      const seen = new Set(staged.map((a) => a.id));
      const fresh = files.filter((f) => {
        const id = attachmentId(f);
        if (seen.has(id)) return false;
        seen.add(id);
        return true;
      });
      const accepted: PendingGroupAttachment[] = [];
      // Set when a file was turned away because the count was already full,
      // rather than for its own size. Truncating to `room` up front spent a
      // slot on a file the loop below then rejected: pick one oversized file
      // and one legal one with a single slot left, and the legal one was never
      // looked at.
      let overflowed = false;
      // The per-file cap alone is not enough: this endpoint takes the bytes
      // inline, so fifty legal files still become one enormous base64 body.
      let stagedBytes = staged.reduce((sum, a) => sum + a.sizeBytes, 0);
      // One message per selection, however many files miss out.
      let reportedBudget = false;
      for (const file of fresh) {
        if (accepted.length >= room) {
          overflowed = true;
          break;
        }
        if (file.size > MAX_ATTACHMENT_BYTES) {
          toast.error(
            t("groups.attachmentTooLarge", "{{name}} is too large to attach", { name: file.name }),
          );
          continue;
        }
        if (stagedBytes + file.size > MAX_GROUP_ATTACHMENTS_TOTAL_BYTES) {
          // Skip THIS file and keep going: a later, smaller one may still fit
          // inside the remaining budget, and dropping it too would be arbitrary.
          if (!reportedBudget) {
            toast.error(
              t("groups.attachmentTotalTooLarge", "Attachments exceed the total size limit"),
            );
            reportedBudget = true;
          }
          continue;
        }
        try {
          accepted.push({
            id: attachmentId(file),
            fileName: file.name,
            // Browsers leave this empty for types they cannot identify; the
            // backend treats an absent mimeType as "work it out from the bytes".
            mimeType: file.type || null,
            data: await readAsBase64(file),
            sizeBytes: file.size,
          });
          stagedBytes += file.size;
        } catch {
          toast.error(
            t("groups.attachmentReadFailed", "Could not read {{name}}", { name: file.name }),
          );
        }
      }
      if (overflowed) toast.warning(tooMany());
      // Cleared while this read was in flight — the files belong to a composer
      // state that no longer exists.
      if (accepted.length && generationRef.current === generation) {
        // Ref first and synchronously, so a queued run already sees these.
        stagedRef.current = [...stagedRef.current, ...accepted];
        setAttachments(stagedRef.current);
      }
    },
    [t],
  );

  /**
   * Serialized entry point. Each selection is chained onto the previous one, so
   * two rapid picks cannot both reserve the same remaining count and byte budget
   * and then merge past the caps.
   */
  const addFiles = useCallback(
    (files: File[]) => {
      setIsStaging(true);
      const generation = generationRef.current;
      const run: Promise<void> = queueRef.current
        .then(() => stageFiles(files, generation))
        .finally(() => {
          // Only the tail of the chain clears the flag — an earlier run
          // finishing must not re-enable the control while a later one is
          // still reading.
          if (queueRef.current === run) setIsStaging(false);
        });
      queueRef.current = run;
      return run;
    },
    [stageFiles],
  );

  const remove = useCallback((id: string) => {
    setAttachments((prev) => prev.filter((a) => a.id !== id));
  }, []);

  const clear = useCallback(() => {
    generationRef.current++;
    stagedRef.current = [];
    setAttachments([]);
  }, []);

  const toRefs = useCallback((): GroupAttachmentRef[] | null => {
    if (!enabled || attachments.length === 0) return null;
    return attachments.map(({ fileName, mimeType, data }) => ({ fileName, mimeType, data }));
  }, [enabled, attachments]);

  return { attachments, isStaging, addFiles, remove, clear, toRefs };
}
