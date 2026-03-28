import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { MessageCircle } from "lucide-react";
import { ChatPanel } from "@/components/chat/chat-panel";
import { useChatDrawerStore } from "@/hooks/use-chat-drawer";
import { useOnboarding } from "@/hooks/use-onboarding";

export function ChatPage() {
  const { t } = useTranslation();

  // Close the chat drawer when user navigates to the full chat page
  useEffect(() => {
    useChatDrawerStore.getState().close();
  }, []);

  // Auto-trigger chat onboarding chapter
  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => {
    const timer = setTimeout(() => maybeAutoStart("chat"), 500);
    return () => clearTimeout(timer);
  }, [maybeAutoStart]);

  return (
    <div className="flex h-[calc(100vh-(--spacing(16))-(--spacing(12)))] flex-col gap-4">
      <div>
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <MessageCircle className="h-8 w-8 text-primary" />
          {t("pages.chat.title")}
        </h1>
        <p className="mt-1 text-muted-foreground">
          {t("pages.chat.subtitle")}
        </p>
      </div>

      <div className="min-h-0 flex-1">
        <ChatPanel />
      </div>
    </div>
  );
}
