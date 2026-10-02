import { useMutation, useQuery, useQueryClient, type QueryKey } from "@tanstack/react-query";
import { groupKeys } from "@/lib/query-keys";
import {
  deleteUserData,
  exportUserData,
  restrictProcessing,
  unrestrictProcessing,
  isProcessingRestricted,
  type GdprDeletionResult,
  type UserDataExport,
} from "@/lib/api/gdpr";

/** The processing-restriction status of one user (GDPR Art. 18). */
export const gdprRestrictedKey = (userId: string) => ["gdpr", "restricted", userId] as const;

/**
 * Every cached read an erasure can make stale. The cascade deletes memories,
 * properties, conversations (and their user mappings), group conversations and
 * schedules, and pseudonymizes audit entries — so a Manager tab that cached any
 * of them kept showing the erased user's data until the entry went stale. The
 * dashboard's recent-conversations card reads the same conversations.
 * Prefix keys: each one sweeps every query under it, whatever the user id.
 */
export function gdprErasureInvalidations(userId: string): QueryKey[] {
  return [
    ["user-memories"],
    ["user-properties"],
    ["conversations"],
    ["userConversations"],
    groupKeys.conversations,
    ["schedules"],
    ["audit"],
    ["dashboard", "recent-conversations"],
    gdprRestrictedKey(userId),
  ];
}

/** Mutation: delete all data for a user (GDPR Art. 17) */
export function useDeleteUserData() {
  const queryClient = useQueryClient();
  return useMutation<GdprDeletionResult, Error, string>({
    mutationFn: (userId) => deleteUserData(userId),
    // On success only: a 207 (partial) is a success here, and it erased
    // something too. A 4xx/5xx erased nothing the caches could be holding.
    onSuccess: (_data, userId) => {
      for (const queryKey of gdprErasureInvalidations(userId)) {
        void queryClient.invalidateQueries({ queryKey });
      }
    },
  });
}

/** Mutation: export all data for a user (GDPR Art. 15/20) */
export function useExportUserData() {
  return useMutation<UserDataExport, Error, string>({
    mutationFn: (userId) => exportUserData(userId),
  });
}

/** Mutation: restrict processing for a user (GDPR Art. 18) */
export function useRestrictProcessing() {
  return useMutation<void, Error, string>({
    mutationFn: (userId) => restrictProcessing(userId),
  });
}

/** Mutation: remove processing restriction (GDPR Art. 18) */
export function useUnrestrictProcessing() {
  return useMutation<void, Error, string>({
    mutationFn: (userId) => unrestrictProcessing(userId),
  });
}

/** Query: check if processing is restricted (GDPR Art. 18) */
export function useIsProcessingRestricted(userId: string) {
  return useQuery<boolean, Error>({
    queryKey: gdprRestrictedKey(userId),
    queryFn: () => isProcessingRestricted(userId),
    enabled: !!userId.trim(),
  });
}
