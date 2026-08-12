import { Skeleton } from "@/components/ui/skeleton";

/**
 * Suspense fallback for a code-split route (see `SuspendedOutlet`).
 *
 * The `data-testid` is load-bearing for the routing tests: `animate-pulse` is
 * also worn by live-status dots elsewhere in the shell, so "is the page still
 * loading?" cannot be answered by looking for that class.
 */
export function PageLoader() {
  return (
    <div className="flex flex-col gap-6 p-6" data-testid="page-loader">
      <Skeleton className="h-8 w-64" />
      <div className="flex flex-col gap-4">
        <Skeleton className="h-4 w-full max-w-md" />
        <Skeleton className="h-4 w-full max-w-sm" />
      </div>
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        <Skeleton className="h-32 rounded-xl" />
        <Skeleton className="h-32 rounded-xl" />
        <Skeleton className="h-32 rounded-xl" />
      </div>
    </div>
  );
}
