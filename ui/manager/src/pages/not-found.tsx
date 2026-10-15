import { Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Compass } from "lucide-react";

interface NotFoundPageProps {
  /** Which app the unknown path was requested in — decides where "home" is. */
  scope?: "manage" | "workforce";
}

/**
 * Shown for a path no route matches, inside the surrounding shell so the sidebar
 * stays usable. Unknown paths used to redirect to /welcome without a word, which
 * made a mistyped or stale link look like the app had lost its place.
 */
export function NotFoundPage({ scope = "manage" }: NotFoundPageProps) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const home = scope === "workforce" ? "/workforce" : "/manage";

  return (
    <div
      className="flex h-full min-h-[300px] flex-col items-center justify-center gap-4 text-center"
      data-testid="not-found-page"
    >
      <div className="rounded-full bg-muted p-4">
        <Compass className="h-8 w-8 text-muted-foreground" aria-hidden="true" />
      </div>
      <div className="space-y-1">
        <h1 className="text-xl font-semibold text-foreground">
          {t("notFound.title", "Page not found")}
        </h1>
        <p className="max-w-md text-sm text-muted-foreground">
          {t("notFound.description", "There is nothing at this address. It may have moved, or the link may be mistyped.")}
        </p>
        <p className="break-all font-mono text-xs text-muted-foreground/70" dir="ltr">
          {pathname}
        </p>
      </div>
      <div className="flex flex-wrap items-center justify-center gap-2">
        <Link
          to={home}
          className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          data-testid="not-found-home"
        >
          {scope === "workforce"
            ? t("notFound.backToWorkforce", "Back to Workforce")
            : t("notFound.backToDashboard", "Back to Dashboard")}
        </Link>
        <Link
          to="/welcome?choose"
          className="rounded-lg border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted"
        >
          {t("notFound.chooseWorkspace", "Choose a workspace")}
        </Link>
      </div>
    </div>
  );
}
