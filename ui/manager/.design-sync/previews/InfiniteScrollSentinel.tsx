import { InfiniteScrollSentinel } from "eddi-manager";

// The sentinel is invisible unless it's actively fetching the next page; show
// that loading state (hasMore + isFetchingMore) so the card isn't blank.
export const LoadingMore = () => (
  <div style={{ padding: 16, minWidth: 240 }}>
    <InfiniteScrollSentinel hasMore isFetchingMore onLoadMore={() => {}} />
  </div>
);
