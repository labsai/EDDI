import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ChevronLeft } from "lucide-react";
import { ChatPanel } from "@/components/chat/chat-panel";

/**
 * Workforce-native chat page.
 * Renders ChatPanel inside BoardroomLayout so users never leave the Workforce shell.
 * agentId is read from search params by ChatPanel itself.
 */
export function WorkforceChat() {
  const { t } = useTranslation();

  return (
    <div className="flex h-full flex-col">
      {/* Header bar */}
      <div className="flex items-center gap-2 border-b border-border ps-4 pe-4 py-2">
        <Link
          to="/workforce"
          className="flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
          aria-label={t("boardroom.back", "Back")}
        >
          <ChevronLeft className="h-4 w-4" />
        </Link>
        <h2 className="text-sm font-semibold text-foreground">
          {t("boardroom.chat.title", "Chat")}
        </h2>
      </div>

      {/* Chat panel — fills remaining space */}
      <div className="flex-1 min-h-0">
        <ChatPanel />
      </div>
    </div>
  );
}
