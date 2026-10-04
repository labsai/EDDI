import { Suspense } from "react";
import { Outlet, useLocation } from "react-router-dom";
import { ErrorBoundary } from "@/components/ui/error-boundary";
import { PageLoader } from "./page-loader";

/**
 * The router outlet, with a Suspense boundary of its own.
 *
 * Route components are code-split (`lazyPage` in `app.tsx`), so rendering a page
 * the user has not visited suspends while its chunk downloads. The boundary sits
 * HERE, inside the layout, rather than around `<Routes>`: a boundary above the
 * layout would unmount the whole shell — sidebar, top bar, chat drawer, and the
 * drawer's in-flight conversation with it — and remount it on every navigation.
 * Placed at the outlet, only the page area swaps.
 */
export function SuspendedOutlet() {
  const location = useLocation();
  return (
    <ErrorBoundary resetKey={location.key}>
      <Suspense fallback={<PageLoader />}>
        <Outlet />
      </Suspense>
    </ErrorBoundary>
  );
}
