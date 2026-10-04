import { AlertCircle } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { isApiError } from "@/lib/api-client";

interface ErrorStateProps {
  /**
   * The headline. Shown as-is when no `error` is given, and as the fallback
   * for statuses that have no wording of their own (5xx, 400, …).
   */
  message?: string;
  /**
   * The failure itself. A 401/403/404 or a network failure each get their own
   * headline — "Something went wrong" for all of them sends an operator
   * hunting for a bug when the real cause is a missing role or a dead server.
   * The server's own message is shown beneath when it adds something.
   */
  error?: unknown;
  onRetry?: () => void;
  retryLabel?: string;
}

/** What `response.statusText` says when the body carried no message of its own. */
const BARE_STATUS_PHRASES = new Set([
  "bad request",
  "forbidden",
  "not found",
  "conflict",
  "internal server error",
  "bad gateway",
  "service unavailable",
  "gateway timeout",
]);

export function ErrorState({
  message,
  error,
  onRetry,
  retryLabel,
}: ErrorStateProps) {
  const { t } = useTranslation();

  const status = isApiError(error) ? error.status : undefined;
  let headline = message ?? t("common.error", "Something went wrong");
  switch (status) {
    case 0:
      headline = t("common.errors.network", "Can't reach EDDI. Check your connection and try again.");
      break;
    case 401:
      headline = t("common.errors.unauthorized", "Your session has expired. Sign in again.");
      break;
    case 403:
      headline = t("common.errors.forbidden", "You don't have permission to view this.");
      break;
    case 404:
      headline = t("common.errors.notFound", "Not found — it may have been deleted.");
      break;
  }

  // The server's sentence, unless it is just the status text echoed back or
  // the network wrapper already said (status 0 carries "Network error: …").
  const detail =
    isApiError(error) && status !== 0 && status !== 401 && error.message &&
    error.message !== headline &&
    !BARE_STATUS_PHRASES.has(error.message.trim().toLowerCase())
      ? error.message
      : null;

  return (
    <div
      className="flex flex-col items-center justify-center rounded-xl border border-destructive/30 bg-destructive/5 px-6 py-16 text-center"
      data-testid="error-state"
      role="alert"
    >
      <AlertCircle className="h-12 w-12 text-destructive" />
      <p className="mt-4 text-lg font-medium text-destructive">{headline}</p>
      {detail && (
        <p
          className="mt-1 max-w-xl text-sm text-muted-foreground"
          data-testid="error-state-detail"
        >
          {detail}
        </p>
      )}
      {onRetry && (
        <Button
          variant="ghost"
          className="mt-4 text-destructive hover:bg-destructive/10 hover:text-destructive"
          onClick={onRetry}
          data-testid="error-state-retry"
        >
          {retryLabel ?? t("common.retry", "Retry")}
        </Button>
      )}
    </div>
  );
}
