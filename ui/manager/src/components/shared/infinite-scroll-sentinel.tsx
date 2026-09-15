import { useRef, useEffect } from "react";
import { Loader2 } from "lucide-react";

interface InfiniteScrollSentinelProps {
  /** Called when the sentinel enters the viewport */
  onLoadMore: () => void;
  /** Whether more data is currently being fetched */
  isFetchingMore: boolean;
  /** Whether there are more pages to load */
  hasMore: boolean;
}

/**
 * Invisible sentinel element that triggers `onLoadMore` when it scrolls
 * into view via IntersectionObserver. Shows a spinner while loading.
 */
export function InfiniteScrollSentinel({
  onLoadMore,
  isFetchingMore,
  hasMore,
}: InfiniteScrollSentinelProps) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!hasMore || isFetchingMore) return;

    const el = ref.current;
    if (!el) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting) {
          onLoadMore();
        }
      },
      { rootMargin: "200px" } // trigger 200px before entering viewport
    );

    observer.observe(el);
    return () => observer.disconnect();
  }, [hasMore, isFetchingMore, onLoadMore]);

  if (!hasMore) return null;

  return (
    <div ref={ref} className="flex items-center justify-center py-6" aria-live="polite">
      {isFetchingMore && (
        <>
          <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" aria-hidden="true" />
          <span className="sr-only">Loading more items…</span>
        </>
      )}
    </div>
  );
}
