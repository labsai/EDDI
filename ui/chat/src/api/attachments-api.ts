/* ──────────────────────────────────────────────
   EDDI Chat — Attachments
   Upload, then reference the returned storageRef through an `attachment_N`
   context key. The context key is the ONLY ingestion path the backend reads
   (AttachmentContextExtractor) — a reference embedded in the message text is
   silently ignored.
   ────────────────────────────────────────────── */

import { encodeSegment, request, requestJson } from "./http";
import type { ContextMap } from "@/types";

export interface AttachmentResult {
  storageRef: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  /**
   * The conversation this blob belongs to, as reported by the upload.
   *
   * Kept because it is not necessarily the conversation that is current when
   * the user removes the chip: an upload started before New Conversation lands
   * afterwards and stages into the fresh composer. Deleting such a chip against
   * the current id is refused by the store's owner check and the original blob
   * leaks — exactly the quota this is meant to protect.
   */
  conversationId?: string;
  /**
   * False when the file was stored but is too large to inline to the model.
   *
   * The upload cap and the forward cap are different limits (20 MiB and 10 MiB
   * by default), so a file in between uploads with a 201 and is then skipped at
   * forward time. The model still receives a text note in its place saying the
   * file was not sent and that `readAttachment` can fetch it — so whether the
   * agent can use the content depends on its tool configuration. Either way the
   * bytes are not inlined, which is what the user needs to know up front.
   *
   * Not a complete signal: there is also an aggregate cap
   * (`max-forward-aggregate-bytes`, 20 MiB default) that this flag cannot see,
   * so several individually-forwardable files can still push the last one out.
   * The per-turn skip record lands in `attachments:errors`, which the widget
   * does not read — it asks for `returnDetailed=false`, and the projection's
   * key-prefix allowlist only admits that key when detailed is requested.
   */
  forwardableInline?: boolean;
}

/**
 * Backend cap per turn — AttachmentContextExtractor's
 * DEFAULT_MAX_ATTACHMENTS_PER_TURN. Extra attachments are dropped server-side,
 * so the UI must not pretend they were sent.
 *
 * Caveat, stated plainly because this repo rejected a client-side upload-size
 * pre-check on the same grounds: `eddi.attachments.max-per-turn` is a
 * @ConfigProperty, so this is a default rather than a contract. It is kept
 * client-side anyway because the failure mode differs — a deployment that
 * RAISES the cap merely leaves slots unused here, whereas a stale size limit
 * would refuse uploads the server would have accepted. A deployment that LOWERS
 * it still gets a correct outcome, just reported by the server rather than
 * pre-empted. There is no endpoint exposing the effective value.
 */
export const MAX_ATTACHMENTS_PER_TURN = 5;

/** Context key prefix the extractor scans for. */
const ATTACHMENT_PREFIX = "attachment_";

/**
 * Build the context entries that carry uploaded attachments into a turn.
 *
 * Only `storageRef` (plus an optional `fileName` display hint) is sent: for
 * stored blobs the backend resolves the authoritative MIME type and size from
 * validated store metadata and does not trust client-supplied values.
 *
 * An empty `fileName` is omitted rather than sent through. The extractor only
 * backfills the stored name when the key is absent (`getFileName() == null`),
 * so sending "" suppresses that fallback and the model is handed a file with
 * no name at all.
 */
export function buildAttachmentContext(
  attachments: readonly AttachmentResult[],
): ContextMap {
  const context: ContextMap = {};
  attachments.slice(0, MAX_ATTACHMENTS_PER_TURN).forEach((a, index) => {
    context[`${ATTACHMENT_PREFIX}${index}`] = {
      type: "object",
      value: a.fileName
        ? { storageRef: a.storageRef, fileName: a.fileName }
        : { storageRef: a.storageRef },
    };
  });
  return context;
}

/**
 * Upload a file to a conversation.
 * POST /conversations/{conversationId}/attachments (multipart/form-data)
 *
 * Throws ApiError on rejection so the caller can surface the server's reason
 * (ATTACHMENT_TOO_LARGE, ATTACHMENT_REJECTED, or a bare 400).
 */
export async function uploadAttachment(
  conversationId: string,
  file: File,
): Promise<AttachmentResult> {
  const formData = new FormData();
  formData.append("file", file);

  const result = await requestJson<AttachmentResult>(
    `/conversations/${encodeSegment(conversationId)}/attachments`,
    { method: "POST", body: formData },
    "Attachment upload failed",
  );
  if (!result) throw new Error("uploadAttachment: empty response body");
  return result;
}

/** Remove a previously uploaded attachment. */
export async function deleteAttachment(
  conversationId: string,
  storageRef: string,
): Promise<void> {
  await request(
    `/conversations/${encodeSegment(conversationId)}/attachments/${encodeSegment(storageRef)}`,
    { method: "DELETE" },
    "Failed to remove attachment",
  );
}
