import { useCallback } from "react";
import { useNavigate, type NavigateOptions } from "react-router-dom";
import { isInThisApp, pageNavigation } from "../lib/app-paths";

/**
 * `navigate()` that works across the two signed-in apps: the router's within
 * this app, a full page navigation to the other (`replace` swaps the history
 * entry there too). Router state does not survive a page load, so it is carried
 * only within the app. `AppLink` is the same rule for a link; lib/app-paths.ts
 * says why a client-side navigation across apps does not arrive.
 */
export function useAppNavigate(): (to: string, options?: NavigateOptions) => void {
  const navigate = useNavigate();
  return useCallback(
    (to: string, options?: NavigateOptions) => {
      if (isInThisApp(to)) {
        navigate(to, options);
      } else if (options?.replace) {
        pageNavigation.replace(to);
      } else {
        pageNavigation.assign(to);
      }
    },
    [navigate],
  );
}
