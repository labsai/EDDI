/**
 * Which app serves a path, and whether that is the app running now.
 *
 * The Manager (`/manage/**`, and the `/welcome` chooser) and Workforce
 * (`/workforce/**`) are two apps with two routers (frontend unification plan,
 * phase 5). A `<Link>` or `navigate()` only moves within the router that renders
 * it: pointed at the other app's path it matches nothing there, and the user
 * lands on a not-found page or a redirect instead of where the link said. So a
 * path the other app serves is a full page navigation, which `AppLink`,
 * `AppNavigate` (components/shared/app-link.tsx) and `useAppNavigate` do.
 */

export type AppName = "manager" | "workforce";

/**
 * The app this bundle is. Each app's vite.config.ts defines
 * `import.meta.env.VITE_EDDI_APP`, so code here that both apps render knows
 * which it is in — in the build, the dev server and Vitest alike.
 */
export const CURRENT_APP: AppName = import.meta.env.VITE_EDDI_APP === "workforce" ? "workforce" : "manager";

/** The app that serves `path`, or null for a path neither app routes (an API path, an external URL). */
export function appOfPath(path: string): AppName | null {
  if (/^\/workforce(?=$|[/?#])/.test(path)) return "workforce";
  if (/^\/(manage|welcome)(?=$|[/?#])/.test(path)) return "manager";
  return null;
}

/** Whether `path` is routed by the app running now, so a client-side navigation reaches it. */
export function isInThisApp(path: string, current: AppName = CURRENT_APP): boolean {
  const app = appOfPath(path);
  return app === null || app === current;
}

/**
 * The full page navigation to the other app. An object, so a test can spy on it
 * (`vi.spyOn(pageNavigation, "replace")`): jsdom implements no navigation, and
 * `window.location` cannot be redefined.
 */
export const pageNavigation = {
  assign(url: string): void {
    window.location.assign(url);
  },
  replace(url: string): void {
    window.location.replace(url);
  },
};
