import { useMutation, useQuery } from "@tanstack/react-query";
import {
  deleteUserData,
  exportUserData,
  restrictProcessing,
  unrestrictProcessing,
  isProcessingRestricted,
  type GdprDeletionResult,
  type UserDataExport,
} from "@/lib/api/gdpr";

/** Mutation: delete all data for a user (GDPR Art. 17) */
export function useDeleteUserData() {
  return useMutation<GdprDeletionResult, Error, string>({
    mutationFn: (userId) => deleteUserData(userId),
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
    queryKey: ["gdpr", "restricted", userId],
    queryFn: () => isProcessingRestricted(userId),
    enabled: !!userId.trim(),
  });
}
