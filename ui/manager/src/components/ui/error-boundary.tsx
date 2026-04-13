/* eslint-disable react-refresh/only-export-components -- class boundary + private fallback */
import { Component, type ErrorInfo, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, RotateCcw } from "lucide-react";

interface ErrorBoundaryProps {
  children: ReactNode;
  /** Optional fallback component — receives error + reset function */
  fallback?: (error: Error, reset: () => void) => ReactNode;
}

interface ErrorBoundaryState {
  error: Error | null;
}

/**
 * Generic React error boundary. Prevents a JS error in one section
 * from crashing the entire application.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    if (import.meta.env.DEV) {
      console.error("[EDDI ErrorBoundary]", error, info.componentStack);
    }
  }

  reset = () => {
    this.setState({ error: null });
  };

  render() {
    if (this.state.error) {
      if (this.props.fallback) {
        return this.props.fallback(this.state.error, this.reset);
      }
      return <DefaultErrorFallback error={this.state.error} onReset={this.reset} />;
    }
    return this.props.children;
  }
}

function DefaultErrorFallback({ error, onReset }: { error: Error; onReset: () => void }) {
  const { t } = useTranslation();

  return (
    <div
      className="flex min-h-[200px] flex-col items-center justify-center gap-4 rounded-xl border border-destructive/30 bg-destructive/5 p-8"
      role="alert"
      data-testid="error-boundary-fallback"
    >
      <div className="rounded-full bg-destructive/10 p-3">
        <AlertTriangle className="h-6 w-6 text-destructive" />
      </div>
      <div className="text-center">
        <h3 className="text-sm font-semibold text-foreground">
          {t("errorBoundary.title", "Something went wrong")}
        </h3>
        <p className="mt-1 max-w-md text-xs text-muted-foreground">
          {error.message}
        </p>
      </div>
      <button
        onClick={onReset}
        className="inline-flex items-center gap-1.5 rounded-lg border border-border px-4 py-2 text-xs font-medium text-foreground transition-colors hover:bg-muted"
      >
        <RotateCcw className="h-3.5 w-3.5" />
        {t("errorBoundary.retry", "Try Again")}
      </button>
    </div>
  );
}
