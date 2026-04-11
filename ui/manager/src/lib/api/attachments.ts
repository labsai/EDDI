import { api } from "../api-client";

// ==================== Types ====================

export interface AttachmentResult {
  storageRef: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
}

// ==================== API Functions ====================

/**
 * Upload a file attachment to a conversation.
 * POST /conversations/{conversationId}/attachments (multipart/form-data)
 * Returns a storage reference that can be used in context.
 */
export async function uploadAttachment(
  conversationId: string,
  file: File,
): Promise<AttachmentResult> {
  const formData = new FormData();
  formData.append("file", file);

  const response = await fetch(
    `${api.getBaseUrl()}/conversations/${conversationId}/attachments`,
    {
      method: "POST",
      headers: api.getAuthHeader(),
      body: formData,
    },
  );

  if (!response.ok) {
    throw new Error(`Attachment upload failed: ${response.statusText}`);
  }

  return response.json();
}
