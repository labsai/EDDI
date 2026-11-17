import { Navigate, useParams } from "react-router-dom";
import { useWorkforceAccess } from "@/hooks/use-workforce-access";

/**
 * Renders an editor/admin-only Workforce screen (the builder wizard, team
 * settings, analytics) only for someone who may author; an `eddi-user` is sent
 * on to the nearest screen they can use instead of a page whose every request
 * answers 403 — to the team's board for its settings, to the dashboard
 * otherwise.
 */
export function AuthorRoute({ children }: { children: React.ReactNode }) {
  const { canAuthor } = useWorkforceAccess();
  const { boardId } = useParams<{ boardId: string }>();
  if (!canAuthor) {
    return <Navigate to={boardId ? `/workforce/${boardId}` : "/workforce"} replace />;
  }
  return <>{children}</>;
}
