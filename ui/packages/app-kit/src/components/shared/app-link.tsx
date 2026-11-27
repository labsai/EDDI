import { forwardRef, useEffect, type AnchorHTMLAttributes } from "react";
import { Link, Navigate } from "react-router-dom";
import { isInThisApp, pageNavigation } from "../../lib/app-paths";

export interface AppLinkProps extends Omit<AnchorHTMLAttributes<HTMLAnchorElement>, "href"> {
  /** An app path: `/workforce/…`, `/manage/…`, `/welcome`. */
  to: string;
  /** Router state, carried only when the target is in this app. */
  state?: unknown;
  replace?: boolean;
}

/**
 * A link that works across the two signed-in apps: a `<Link>` when the Manager
 * links into the Manager or Workforce into Workforce, a plain `<a href>` (a full
 * page navigation) when it points at the other app. See lib/app-paths.ts for
 * why a `<Link>` across apps does not arrive.
 *
 * Theme, language, pins and the Keycloak session carry over the navigation
 * through same-origin storage, so the other app opens where this one left off.
 */
export const AppLink = forwardRef<HTMLAnchorElement, AppLinkProps>(function AppLink(
  { to, state, replace, ...rest },
  ref,
) {
  if (isInThisApp(to)) return <Link ref={ref} to={to} state={state} replace={replace} {...rest} />;
  return <a ref={ref} href={to} {...rest} />;
});

/**
 * `<Navigate replace>` across the two apps: the router's redirect within this
 * app, a page load (replacing the history entry) to the other. Renders nothing.
 */
export function AppNavigate({ to }: { to: string }) {
  const inApp = isInThisApp(to);
  useEffect(() => {
    if (!inApp) pageNavigation.replace(to);
  }, [inApp, to]);
  return inApp ? <Navigate to={to} replace /> : null;
}
