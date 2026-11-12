import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getAllMemories,
  deleteMemory,
  deleteAllForUser,
  type UserMemoryEntry,
} from "../lib/api/user-memory";

/** The cache key prefix of every user's memory list. */
export const USER_MEMORIES_KEY = "user-memories";

export function useUserMemories(userId: string) {
  return useQuery<UserMemoryEntry[], Error>({
    queryKey: [USER_MEMORIES_KEY, userId],
    queryFn: () => getAllMemories(userId),
    enabled: !!userId.trim(),
  });
}

export function useDeleteMemory() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: (entryId) => deleteMemory(entryId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [USER_MEMORIES_KEY] });
    },
  });
}

export function useDeleteAllMemories() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: (userId) => deleteAllForUser(userId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [USER_MEMORIES_KEY] });
    },
  });
}
