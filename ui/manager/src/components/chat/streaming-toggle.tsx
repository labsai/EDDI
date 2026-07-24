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
        "flex items-center gap-1 rounded-md px-2 py-1 text-[10px] font-medium transition-colors",
        streamingEnabled
          ? "text-muted-foreground hover:text-foreground"
          : "text-muted-foreground/50 hover:text-muted-foreground"
      )}
      title={t("chat.streaming")}
      aria-label={t("chat.streaming")}
      aria-pressed={streamingEnabled}
      data-testid="streaming-toggle"
    >
      {streamingEnabled ? (
        <Zap className="h-3 w-3" />
      ) : (
        <ZapOff className="h-3 w-3" />
      )}
      {t("chat.streaming")}
    </button>
  );
}
