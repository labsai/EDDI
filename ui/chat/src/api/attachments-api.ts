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
}

/**
 * Backend cap per turn — AttachmentContextExtractor's
 * DEFAULT_MAX_ATTACHMENTS_PER_TURN. Extra attachments are dropped server-side,
 * so the UI must not pretend they were sent.
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
 */
export function buildAttachmentContext(
  attachments: readonly AttachmentResult[],
): ContextMap {
  const context: ContextMap = {};
  attachments.slice(0, MAX_ATTACHMENTS_PER_TURN).forEach((a, index) => {
    context[`${ATTACHMENT_PREFIX}${index}`] = {
      type: "object",
      value: { storageRef: a.storageRef, fileName: a.fileName },
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
