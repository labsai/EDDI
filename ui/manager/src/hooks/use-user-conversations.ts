import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getUserConversation,
  createUserConversation,
  deleteUserConversation,
  type UserConversation,
} from "@/lib/api/user-conversations";

const KEY = ["userConversations"] as const;

/**
 * Lookup a user conversation by intent + userId.
 * Only fetches when both values are non-empty.
 */
export function useUserConversation(intent: string, userId: string) {
  return useQuery({
    queryKey: [...KEY, intent, userId],
    queryFn: () => getUserConversation(intent, userId),
    enabled: !!intent.trim() && !!userId.trim(),
    retry: false,
  });
}

/** Create a user conversation binding. */
export function useCreateUserConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      intent,
      userId,
      data,
    }: {
      intent: string;
      userId: string;
      data: UserConversation;
    }) => createUserConversation(intent, userId, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

/** Delete a user conversation binding. */
export function useDeleteUserConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ intent, userId }: { intent: string; userId: string }) =>
      deleteUserConversation(intent, userId),
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}
