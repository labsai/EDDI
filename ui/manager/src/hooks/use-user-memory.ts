import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getAllMemories,
  searchMemories,
  deleteMemory,
  deleteAllForUser,
  countMemories,
  type UserMemoryEntry,
} from "@/lib/api/user-memory";

export function useUserMemories(userId: string) {
  return useQuery<UserMemoryEntry[], Error>({
    queryKey: ["user-memories", userId],
    queryFn: () => getAllMemories(userId),
    enabled: !!userId.trim(),
  });
}

export function useSearchMemories(userId: string, query: string) {
  return useQuery<UserMemoryEntry[], Error>({
    queryKey: ["user-memories", userId, "search", query],
    queryFn: () => searchMemories(userId, query),
    enabled: !!userId.trim() && !!query.trim(),
  });
}

export function useCountMemories(userId: string) {
  return useQuery<number, Error>({
    queryKey: ["user-memories", userId, "count"],
    queryFn: () => countMemories(userId),
    enabled: !!userId.trim(),
  });
}

export function useDeleteMemory() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: (entryId) => deleteMemory(entryId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["user-memories"] });
    },
  });
}

export function useDeleteAllMemories() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: (userId) => deleteAllForUser(userId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["user-memories"] });
    },
  });
}
