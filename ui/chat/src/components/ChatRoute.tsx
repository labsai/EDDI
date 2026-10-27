/* ──────────────────────────────────────────────
   ChatRoute — the `/chat/:environment/:agentId[/:userId]` routes.
   The demo route `/chat/demo/showcase` plays EDDI's own showcase script
   (demo-api.ts), which is EDDI marketing. Under a deployment brand it would
   advertise EDDI from the brand's domain, so it shows a neutral "not
   available" state instead; unbranded it is unchanged.
   ────────────────────────────────────────────── */

import { MessagesSquare } from "lucide-react";
import { useParams } from "react-router-dom";

import { getBrand } from "@/api/brand";
import { isDemoMode } from "@/api/demo-api";
import { ChatHeader } from "@/components/ChatHeader";
import { ChatWidget } from "@/components/ChatWidget";
import { t } from "@/i18n";

export function ChatRoute() {
  const { environment, agentId } = useParams();
  if (isDemoMode(environment, agentId) && getBrand().branded) {
    return <DemoUnavailable />;
  }
  return <ChatWidget />;
}

function DemoUnavailable() {
  return (
    <div className="chat-root">
      <ChatHeader />
      <div className="chat-messages">
        <div className="chat-empty" role="status" data-testid="demo-unavailable">
          <MessagesSquare className="chat-empty__icon" size="1em" />
          <p className="chat-empty__text">{t("demo.unavailable")}</p>
        </div>
      </div>
    </div>
  );
}
