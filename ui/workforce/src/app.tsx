import { Suspense } from "react";
import { Routes, Route, Navigate, useLocation, useParams } from "react-router-dom";
import { PageLoader } from "@eddi/app-kit/components/layout/page-loader";
import { ErrorBoundary } from "@eddi/app-kit/components/ui/error-boundary";
import { NavigationGuardDialog } from "@eddi/app-kit/components/shared/navigation-guard-dialog";
import { NotFoundPage } from "@eddi/app-kit/pages/not-found";
import { lazyPage } from "@eddi/app-kit/lib/lazy-page";
import { WorkforceLayout } from "@/components/workforce/workforce-layout";
import { AuthorRoute } from "@/components/workforce/author-route";

/**
 * Workforce's route table: everything under `/workforce`.
 *
 * Workforce is its own app (frontend unification plan, phase 5): its own shell
 * (`workforce.html`), bundle and router, served by EDDI for every `/workforce/**`
 * path, so a deep link or a reload lands here. The Manager (`/manage`) and the
 * welcome chooser (`/welcome`) are another app: a link to them is a full page
 * navigation (`<a href>`), never a `<Link>`, which this router could not
 * resolve. `src/__tests__/route-integrity.test.ts` holds every in-app link to
 * this table.
 *
 * `WorkforceLayout` is eager — the chrome every page renders into; splitting it
 * would put a chunk fetch in front of the shell's first paint. The pages load on
 * navigation, behind `<PageLoader />`.
 */
const WorkforceDashboard = lazyPage(() => import("@/pages/workforce/workforce-dashboard"), "WorkforceDashboard");
const WorkforceWizard = lazyPage(() => import("@/pages/workforce/workforce-wizard"), "WorkforceWizard");
const WorkforceBoard = lazyPage(() => import("@/pages/workforce/workforce-board"), "WorkforceBoard");
const WorkforceThread = lazyPage(() => import("@/pages/workforce/workforce-thread"), "WorkforceThread");
const WorkforceSettings = lazyPage(() => import("@/pages/workforce/workforce-settings"), "WorkforceSettings");
const WorkforceHistory = lazyPage(() => import("@/pages/workforce/workforce-history"), "WorkforceHistory");
const WorkforceAnalytics = lazyPage(() => import("@/pages/workforce/workforce-analytics"), "WorkforceAnalytics");
const WorkforceChat = lazyPage(() => import("@/pages/workforce/workforce-chat"), "WorkforceChat");

/** Redirect /Workforce/* (capital W) → /workforce/* preserving sub-paths */
function WorkforceRedirect() {
  const { "*": rest } = useParams();
  const sub = rest ? `/${rest}` : "";
  return <Navigate to={`/workforce${sub}`} replace />;
}

export function App() {
  const location = useLocation();
  return (
    <ErrorBoundary resetKey={location.key}>
      <Suspense fallback={<PageLoader />}>
        <Routes>
          <Route path="/workforce" element={<WorkforceLayout />}>
            <Route index element={<WorkforceDashboard />} />
            <Route path="new" element={<AuthorRoute><WorkforceWizard /></AuthorRoute>} />
            <Route path="analytics" element={<AuthorRoute><WorkforceAnalytics /></AuthorRoute>} />
            <Route path="chat" element={<WorkforceChat />} />
            <Route path=":boardId" element={<WorkforceBoard />} />
            <Route path=":boardId/thread/:memberId" element={<WorkforceThread />} />
            <Route path=":boardId/settings" element={<AuthorRoute><WorkforceSettings /></AuthorRoute>} />
            <Route path=":boardId/history" element={<WorkforceHistory />} />
            <Route path="*" element={<NotFoundPage scope="workforce" />} />
          </Route>

          {/* Legacy /Workforce (capital W) redirect → /workforce */}
          <Route path="/Workforce/*" caseSensitive element={<WorkforceRedirect />} />
          <Route path="/Workforce" caseSensitive element={<Navigate to="/workforce" replace />} />

          {/* Only the dev server reaches anything else (EDDI serves this app for
              /workforce/** alone): send it home rather than to a blank page. */}
          <Route path="*" element={<Navigate to="/workforce" replace />} />
        </Routes>
      </Suspense>
      <NavigationGuardDialog />
    </ErrorBoundary>
  );
}
