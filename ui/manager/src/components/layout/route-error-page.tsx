import { useRouteError } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { AlertTriangle } from "lucide-react";

/**
 * The data router's `errorElement`: what renders when something ABOVE the
 * app's own error boundaries throws — the auth, query or theme providers.
 *
 * Without it React Router shows its built-in "Unexpected Application Error!"
 * page, which is English-only, unstyled and offers no way out. Page errors
 * never get here; `SuspendedOutlet` and `App` catch those inside the shell.
 *
 * Rendered outside `ThemeProvider`, so it uses only tokens that resolve
 * without it (as the auth screens do).
 */
export function RouteErrorPage() {
  const error = useRouteError();
  const { t } = useTranslation();
  const message = error instanceof Error ? error.message : String(error ?? "");

  return (
    <div
      className="flex h-screen items-center justify-center bg-background p-4"
      role="alert"
      data-testid="route-error-page"
    >
      <div className="flex max-w-md flex-col items-center gap-4 text-center">
        <div className="rounded-full bg-destructive/10 p-3">
          <AlertTriangle className="h-6 w-6 text-destructive" aria-hidden="true" />
        </div>
        <h1 className="text-lg font-semibold text-foreground">
          {t("errorBoundary.title", "Something went wrong")}
        </h1>
        {message && <p className="text-sm text-muted-foreground">{message}</p>}
        <button
          type="button"
          onClick={() => window.location.reload()}
          data-testid="route-error-reload"
          className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
        >
          {t("errorBoundary.reload", "Reload")}
        </button>
      </div>
    </div>
  );
}
