import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  listAttachments,
  deleteAttachment,
  deleteAllAttachments,
  type AttachmentMeta,
} from "@/lib/api/attachments";

/** Normalized attachment row for display (backend may send `filename`). */
export interface ConversationAttachment {
  storageRef: string;
  fileName: string;
  mimeType?: string;
  sizeBytes?: number;
}

const attachmentsKey = (conversationId: string) =>
  ["conversation-attachments", conversationId] as const;

/** List the attachments a conversation owns (metadata only). */
export function useConversationAttachments(conversationId: string | undefined) {
  return useQuery({
    queryKey: attachmentsKey(conversationId ?? ""),
    enabled: !!conversationId,
    queryFn: async (): Promise<ConversationAttachment[]> => {
      const raw = await listAttachments(conversationId!);
      return raw.map((a) => {
        const rec = a as AttachmentMeta & { filename?: string };
        return {
          storageRef: rec.storageRef,
          fileName: rec.fileName ?? rec.filename ?? rec.storageRef,
          mimeType: rec.mimeType,
          sizeBytes: rec.sizeBytes,
        };
      });
    },
  });
}

/** Delete a single stored attachment, then refresh the list. */
export function useDeleteAttachment(conversationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (storageRef: string) =>
      deleteAttachment(conversationId, storageRef),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: attachmentsKey(conversationId),
      }),
  });
}

/** Delete every attachment for a conversation (GDPR erasure), then refresh. */
export function useDeleteAllAttachments(conversationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => deleteAllAttachments(conversationId),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: attachmentsKey(conversationId),
      }),
  });
}
