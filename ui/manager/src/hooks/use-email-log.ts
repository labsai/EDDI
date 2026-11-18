import { useInfiniteQuery, useMutation } from "@tanstack/react-query";
import { isApiError } from "@eddi/app-kit/lib/api-client";
import { getEmailLog, lookupEmailLog, type EmailLogFilters } from "@/lib/api/email-log";

/**
 * An email integration's mail log, a page at a time (newest first, keyset
 * cursor). A filter change starts a new list. Not retried on 401/403/404:
 * those answer "no edit access" and "not an email integration".
 */
export function useEmailLog(id: string, filters: EmailLogFilters, enabled = true) {
  return useInfiniteQuery({
    queryKey: ["channels", id, "email-log", filters] as const,
    queryFn: ({ pageParam }) => getEmailLog(id, filters, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: enabled && !!id,
    retry: (failureCount, error) => !(isApiError(error) && [401, 403, 404].includes(error.status)) && failureCount < 2,
  });
}

/** Looks one Message-ID up in an integration's mail log. */
export function useEmailLogLookup(id: string) {
  return useMutation({
    mutationFn: (messageId: string) => lookupEmailLog(id, messageId),
  });
}
