import { useSyncExternalStore } from "react";
import { useInfiniteQuery, useMutation, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { isApiError } from "@eddi/app-kit/lib/api-client";
import { getEmailLog, lookupEmailLog, type EmailLogFilters } from "@/lib/api/email-log";

/** Every cached page of one integration's mail log, under any filter. */
export function emailLogKey(id: string) {
  return ["channels", id, "email-log"] as const;
}

/** Where a channel's mail-log denial is kept (outside `["channels"]`, which saves invalidate). */
function denialKey(id: string) {
  return ["email-log-denial", id] as const;
}

/** A 403 from the integration's mail log: the last request sent before it, and the error to show. */
export interface EmailLogDenial {
  /** The request sequence number current when the denial was recorded; only a later request can lift it. */
  afterRequest: number;
  error: unknown;
}

/**
 * Orders the mail-log requests this page sends. A sequence, not a clock: two
 * requests in one millisecond still have distinct numbers, so a response to a
 * request sent before a denial can never pass for one sent after it.
 */
let requestSequence = 0;

/**
 * Records a 403 for the integration's mail log and drops every cached page of
 * it, under every filter. It runs where the response arrives (the query and the
 * mutation), not in a component, so it also applies when the mail log is no
 * longer on screen. In-flight requests are cancelled too: an answer to a request
 * sent before the denial must not repopulate the cache.
 */
export function markEmailLogDenied(queryClient: QueryClient, id: string, error: unknown) {
  queryClient.setQueryData<EmailLogDenial>(denialKey(id), { afterRequest: requestSequence, error });
  // After the failing query has settled, so it is dropped with the rest.
  setTimeout(() => {
    void queryClient.cancelQueries({ queryKey: emailLogKey(id) }).then(() => queryClient.removeQueries({ queryKey: emailLogKey(id) }));
  }, 0);
}

/** The integration's current mail-log denial, or null; follows the cache. */
export function useEmailLogDenial(id: string): EmailLogDenial | null {
  const queryClient = useQueryClient();
  return useSyncExternalStore(
    (onChange) => queryClient.getQueryCache().subscribe(onChange),
    () => queryClient.getQueryData<EmailLogDenial | null>(denialKey(id)) ?? null,
  );
}

/**
 * An email integration's mail log, a page at a time (newest first, keyset
 * cursor). A filter change starts a new list. Not retried on 400/401/403/404:
 * a bad filter, "no edit access" and "not an email integration" stay so.
 * A 403 denies the whole log ({@link markEmailLogDenied}); only a successful
 * read SENT after the denial lifts it.
 */
export function useEmailLog(id: string, filters: EmailLogFilters, enabled = true) {
  const queryClient = useQueryClient();
  return useInfiniteQuery({
    queryKey: [...emailLogKey(id), filters] as const,
    queryFn: async ({ pageParam }) => {
      const sent = ++requestSequence;
      try {
        const page = await getEmailLog(id, filters, pageParam);
        const denial = queryClient.getQueryData<EmailLogDenial | null>(denialKey(id));
        if (denial && sent > denial.afterRequest) queryClient.setQueryData(denialKey(id), null);
        return page;
      } catch (error) {
        if (isApiError(error) && error.status === 403) markEmailLogDenied(queryClient, id, error);
        throw error;
      }
    },
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: enabled && !!id,
    retry: (failureCount, error) => !(isApiError(error) && [400, 401, 403, 404].includes(error.status)) && failureCount < 2,
  });
}

/**
 * Looks one Message-ID up in an integration's mail log. Silent: the component
 * shows a miss and any other failure inline, so no global error toast. Its 403
 * denies the whole log, handled here so it applies even when the lookup is no
 * longer mounted.
 */
export function useEmailLogLookup(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (messageId: string) => lookupEmailLog(id, messageId),
    meta: { silent: true },
    onError: (error) => {
      if (isApiError(error) && error.status === 403) markEmailLogDenied(queryClient, id, error);
    },
  });
}
