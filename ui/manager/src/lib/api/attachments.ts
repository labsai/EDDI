import { api } from "../api-client";

/*
 * Conversation attachments — multimodal file support.
 *
 * Mirrors the backend attachment pipeline (EDDI v6):
 *   POST   /conversations/{id}/attachments              → upload (multipart)
 *   GET    /conversations/{id}/attachments              → list metadata
 *   GET    /conversations/{id}/attachments/{storageRef} → download bytes
 *   DELETE /conversations/{id}/attachments/{storageRef} → delete one
 *   DELETE /conversations/{id}/attachments              → delete all (GDPR)
 *
 * Uploaded files are forwarded to the (vision-capable) LLM on a later turn by
 * setting an `attachment_*` context key — see {@link buildAttachmentContext}.
 * The backend resolves the authoritative MIME type / size from the stored blob,
 * so the client only needs to send the `storageRef` (+ an optional display
 * `fileName`); it never has to be trusted for the MIME type.
 */

// ==================== Constants ====================

/**
 * Largest file the backend accepts on upload
 * (`eddi.attachments.max-size-bytes`, default 20 MiB). Validated client-side so
 * oversized files fail fast without a round-trip.
 */
export const MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024;

/**
 * Largest file the backend will inline into an LLM message
 * (`eddi.attachments.max-forward-bytes`, default 10 MiB). Files above this are
 * still stored and downloadable, but won't be "seen" by the model inline —
 * `forwardableInline` on the upload result reflects this.
 */
export const MAX_FORWARD_BYTES = 10 * 1024 * 1024;

/**
 * Backend per-turn cap on the number of forwarded attachments
 * (`AttachmentContextExtractor.DEFAULT_MAX_ATTACHMENTS_PER_TURN`). Extra
 * attachments are dropped server-side, so we cap the context we build here.
 */
export const MAX_ATTACHMENTS_PER_TURN = 5;

// ==================== Types ====================

/** Response body of a successful upload (`201`). */
export interface AttachmentResult {
  storageRef: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  conversationId?: string;
  /**
   * `false` when the file exceeds {@link MAX_FORWARD_BYTES} — it is stored and
   * downloadable but too large to inline into an LLM message.
   */
  forwardableInline?: boolean;
}

/**
 * Attachment metadata as returned by the list / download-metadata endpoints.
 * Note the backend record uses lowercase `filename` here (the upload response
 * uses `fileName`); {@link normalizeFileName} papers over the difference.
 */
export interface AttachmentMeta {
  storageRef: string;
  filename?: string;
  mimeType?: string;
  sizeBytes?: number;
  conversationId?: string;
}

/** Minimal reference used to build turn context and render message chips. */
export interface AttachmentRef {
  storageRef: string;
  fileName: string;
  mimeType: string;
  sizeBytes?: number;
  /** `false` when too large to forward inline (surfaced from the upload result). */
  forwardableInline?: boolean;
}

/** A single attachment context entry as consumed by the backend extractor. */
export interface AttachmentContextEntry {
  type: "object";
  value: { storageRef: string; fileName?: string };
}

/** Error thrown by the attachment API, carrying the HTTP status and backend code. */
export class AttachmentError extends Error {
  status: number;
  code?: string;
  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = "AttachmentError";
    this.status = status;
    this.code = code;
  }
}

// ==================== Helpers ====================

/** True for MIME types that can be shown as an inline image preview. */
export function isImageMime(mimeType?: string | null): boolean {
  return !!mimeType && mimeType.startsWith("image/");
}

/** Human-readable byte size, e.g. `1.4 MB`. */
export function formatBytes(bytes?: number): string {
  if (bytes == null || bytes < 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

/** Accept either the upload (`fileName`) or list (`filename`) casing. */
function normalizeFileName(raw: { fileName?: string; filename?: string }): string {
  return raw.fileName ?? raw.filename ?? "";
}

/** Parse a backend error body (`{ error, code }`) into an {@link AttachmentError}. */
async function toAttachmentError(response: Response): Promise<AttachmentError> {
  let message = response.statusText || "Request failed";
  let code: string | undefined;
  try {
    const body = await response.json();
    message = body.error ?? body.message ?? message;
    code = body.code;
  } catch {
    // Non-JSON error body — keep the status text.
  }
  return new AttachmentError(message, response.status, code);
}

// ==================== API functions ====================

/**
 * Upload a file attachment to a conversation.
 * `POST /conversations/{conversationId}/attachments` (multipart/form-data).
 *
 * Rejects oversized files client-side before the request. The returned
 * `storageRef` is later handed to {@link buildAttachmentContext} so the file is
 * forwarded to the LLM on the next turn.
 *
 * @throws {AttachmentError} on validation failure or a non-2xx response.
 */
export async function uploadAttachment(
  conversationId: string,
  file: File,
): Promise<AttachmentResult> {
  if (file.size > MAX_ATTACHMENT_BYTES) {
    throw new AttachmentError(
      `File too large: ${formatBytes(file.size)} (max ${formatBytes(MAX_ATTACHMENT_BYTES)})`,
      400,
      "ATTACHMENT_TOO_LARGE",
    );
  }

  const formData = new FormData();
  formData.append("file", file);

  let response: Response;
  try {
    response = await fetch(
      `${api.getBaseUrl()}/conversations/${encodeURIComponent(conversationId)}/attachments`,
      {
        method: "POST",
        headers: api.getAuthHeader(),
        body: formData,
      },
    );
  } catch (networkError) {
    throw new AttachmentError(
      networkError instanceof Error
        ? `Network error: ${networkError.message}`
        : "Network error: unable to reach server",
      0,
    );
  }

  if (!response.ok) {
    throw await toAttachmentError(response);
  }

  const result = (await response.json()) as AttachmentResult & { filename?: string };
  return { ...result, fileName: normalizeFileName(result) };
}

/**
 * List attachment metadata owned by a conversation.
 * `GET /conversations/{conversationId}/attachments`.
 */
export async function listAttachments(
  conversationId: string,
): Promise<AttachmentMeta[]> {
  const response = await fetch(
    `${api.getBaseUrl()}/conversations/${encodeURIComponent(conversationId)}/attachments`,
    { headers: api.getAuthHeader() },
  );
  if (!response.ok) throw await toAttachmentError(response);
  return response.json();
}

/**
 * Build the authenticated download URL for one attachment.
 * `GET /conversations/{conversationId}/attachments/{storageRef}`.
 *
 * Note: the endpoint requires the bearer token, so this URL is suitable for a
 * programmatic `fetch` (which can send auth headers) — not a bare `<img src>` /
 * `<a href>`, which cannot. For inline previews of freshly-picked files, prefer
 * an object URL from the local `File`.
 */
export function getAttachmentDownloadUrl(
  conversationId: string,
  storageRef: string,
): string {
  return `${api.getBaseUrl()}/conversations/${encodeURIComponent(
    conversationId,
  )}/attachments/${encodeURIComponent(storageRef)}`;
}

/** Fetch one attachment's raw bytes as a Blob (sends auth). */
export async function downloadAttachment(
  conversationId: string,
  storageRef: string,
): Promise<Blob> {
  const response = await fetch(getAttachmentDownloadUrl(conversationId, storageRef), {
    headers: api.getAuthHeader(),
  });
  if (!response.ok) throw await toAttachmentError(response);
  return response.blob();
}

/**
 * Delete a single attachment.
 * `DELETE /conversations/{conversationId}/attachments/{storageRef}`.
 */
export async function deleteAttachment(
  conversationId: string,
  storageRef: string,
): Promise<void> {
  const response = await fetch(getAttachmentDownloadUrl(conversationId, storageRef), {
    method: "DELETE",
    headers: api.getAuthHeader(),
  });
  if (!response.ok) throw await toAttachmentError(response);
}

/**
 * Delete every attachment for a conversation (GDPR erasure).
 * `DELETE /conversations/{conversationId}/attachments`.
 *
 * @returns the number of attachments deleted.
 */
export async function deleteAllAttachments(
  conversationId: string,
): Promise<number> {
  const response = await fetch(
    `${api.getBaseUrl()}/conversations/${encodeURIComponent(conversationId)}/attachments`,
    { method: "DELETE", headers: api.getAuthHeader() },
  );
  if (!response.ok) throw await toAttachmentError(response);
  try {
    const body = await response.json();
    return typeof body?.deletedCount === "number" ? body.deletedCount : 0;
  } catch {
    return 0;
  }
}

/**
 * Build the `attachment_*` context map that forwards uploaded attachments to the
 * LLM on the next turn. Caps at {@link MAX_ATTACHMENTS_PER_TURN} (extras would be
 * dropped server-side). Sending only `{ storageRef, fileName }` lets the backend
 * resolve the trusted MIME type / size from the stored blob.
 *
 * @returns a context map (`{}` when there are no attachments) ready to merge
 *          into the conversation input's `context`.
 */
export function buildAttachmentContext(
  attachments: AttachmentRef[],
): Record<string, AttachmentContextEntry> {
  const context: Record<string, AttachmentContextEntry> = {};
  attachments.slice(0, MAX_ATTACHMENTS_PER_TURN).forEach((att, index) => {
    context[`attachment_${index}`] = {
      type: "object",
      value: att.fileName
        ? { storageRef: att.storageRef, fileName: att.fileName }
        : { storageRef: att.storageRef },
    };
  });
  return context;
}
