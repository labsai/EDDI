import { AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";

interface ErrorStateProps {
  message: string;
  onRetry?: () => void;
  retryLabel?: string;
}

export function ErrorState({
  message,
  onRetry,
  retryLabel = "Retry",
}: ErrorStateProps) {
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-destructive/30 bg-destructive/5 py-16">
      <AlertCircle className="h-12 w-12 text-destructive" />
      <p className="mt-4 text-lg font-medium text-destructive">{message}</p>
      {onRetry && (
        <Button
          variant="ghost"
          className="mt-4 text-destructive hover:bg-destructive/10 hover:text-destructive"
          onClick={onRetry}
        >
          {retryLabel}
        </Button>
      )}
    </div>
  );
}
