import { useMutation } from "@tanstack/react-query";
import {
  deleteUserData,
  exportUserData,
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
