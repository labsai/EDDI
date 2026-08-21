import { cn } from "@/lib/utils";

function Skeleton({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      // Marks a *loading placeholder*, as opposed to the live-status dots and
      // streaming badges that also wear `animate-pulse`. `e2e-helpers`'
      // `waitForApp` keys off this: it used to wait on `[class*="animate-pulse"]`,
      // which always matched the never-hiding PlatformStatus dot in the top bar,
      // so the wait timed out on every run and the failure was swallowed.
      // `page-loader.tsx` documents the same hazard for the routing tests.
      data-slot="skeleton"
      className={cn("animate-pulse rounded-md bg-muted", className)}
      {...props}
    />
  );
}

export { Skeleton };
