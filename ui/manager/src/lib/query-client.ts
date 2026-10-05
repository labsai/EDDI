import { MutationCache, QueryClient, type Mutation } from "@tanstack/react-query";
import { toast } from "sonner";
import { getErrorMessage, isApiError } from "@/lib/api-client";

/**
 * How long to wait, after a mutation fails, for the code that started it to
 * show its own error before the global fallback decides nobody did.
 *
 * Call-site handlers (`mutate(v, { onError })`, `try { await mutateAsync() }
 * catch { toast.error(...) }`) run in the same turn as the failure or a
 * microtask later, so a macrotask is enough; 50ms leaves slack for a slow
 * render without feeling delayed.
 */
const HANDLED_GRACE_MS = 50;

/**
 * Number of error toasts sonner has shown so far. `getHistory` is part of
 * sonner's public API; the guard keeps a missing/older build from breaking the
 * fallback rather than the other way round.
 */
function errorToastCount(): number {
  try {
    return toast.getHistory().filter((t) => "type" in t && t.type === "error").length;
  } catch {
    return 0;
  }
}

/**
 * Global fallback for a failed mutation nobody reported.
 *
 * ~190 call sites fire mutations; many show their own toast or inline message,
 * some forget — and a forgotten one is a button that does nothing and says
 * nothing. This toasts the failure unless
 *  - the mutation opted out (`meta: { silent: true }`, for ones that render the
 *    error elsewhere),
 *  - the mutation has its own hook-level `onError`, or
 *  - an error toast appeared during the grace window (the caller handled it).
 * A 401 is left to the session-expired banner.
 */
export function notifyUnhandledMutationError(
  error: unknown,
  mutation: Pick<Mutation<unknown, unknown, unknown, unknown>, "meta" | "options">,
): void {
  if (mutation.meta?.silent) return;
  if (mutation.options.onError) return;
  if (isApiError(error) && error.status === 401) return;

  const before = errorToastCount();
  setTimeout(() => {
    if (errorToastCount() > before) return;
    toast.error(getErrorMessage(error));
  }, HANDLED_GRACE_MS);
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) =>
        notifyUnhandledMutationError(error, mutation),
    }),
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: 1,
      },
    },
  });
}

export const queryClient = createQueryClient();
