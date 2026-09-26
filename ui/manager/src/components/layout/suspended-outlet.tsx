import { Suspense } from "react";
import { Outlet, useLocation } from "react-router-dom";
import { ErrorBoundary } from "@/components/ui/error-boundary";
import { PageLoader } from "./page-loader";

/**
 * The router outlet, with a Suspense boundary and an error boundary of its own.
 *
 * Route components are code-split (`lazyPage` in `app.tsx`), so rendering a page
 * the user has not visited suspends while its chunk downloads. The boundary sits
 * HERE, inside the layout, rather than around `<Routes>`: a boundary above the
 * layout would unmount the whole shell — sidebar, top bar, chat drawer, and the
 * drawer's in-flight conversation with it — and remount it on every navigation.
 * Placed at the outlet, only the page area swaps.
 *
 * The error boundary is here for the same reason. With only the one around
 * `<Routes>` in `app.tsx`, a single page that threw (an unexpected config shape,
 * a chunk that failed to load) replaced the ENTIRE shell with the fallback: no
 * sidebar to leave by, and the chat drawer's conversation gone with it. Now the
 * fallback fills the page area only, and it resets when the path changes, so
 * clicking anywhere in the sidebar recovers.
 */
export function SuspendedOutlet() {
  const { pathname } = useLocation();
  return (
    <ErrorBoundary resetKey={pathname}>
      <Suspense fallback={<PageLoader />}>
        <Outlet />
      </Suspense>
    </ErrorBoundary>
  );
}
