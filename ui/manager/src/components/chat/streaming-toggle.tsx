import { useTranslation } from "react-i18next";
import { useChatStore } from "@/hooks/use-chat";
import { Zap, ZapOff } from "lucide-react";
import { cn } from "@/lib/utils";

export function StreamingToggle() {
  const { t } = useTranslation();
  const streamingEnabled = useChatStore((s) => s.streamingEnabled);
  const toggleStreaming = useChatStore((s) => s.toggleStreaming);

  return (
    <button
      onClick={toggleStreaming}
      className={cn(
        "flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-medium transition-colors",
        streamingEnabled
          ? "border-primary/30 bg-primary/10 text-primary"
          : "border-border bg-muted text-muted-foreground"
      )}
      title={t("chat.streaming")}
      aria-label={t("chat.streaming")}
      aria-pressed={streamingEnabled}
      data-testid="streaming-toggle"
    >
      {streamingEnabled ? (
        <Zap className="h-3.5 w-3.5" />
      ) : (
        <ZapOff className="h-3.5 w-3.5" />
      )}
      {t("chat.streaming")}
    </button>
  );
}
