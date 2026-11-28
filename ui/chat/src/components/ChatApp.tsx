/* ──────────────────────────────────────────────
   ChatApp — picks the screen for the URL (src/location.ts):
   a managed route renders the widget, a direct route goes through ChatRoute
   (the demo is off under a brand), anything else is replaced with
   /chat/production/default.
   ────────────────────────────────────────────── */

import { useEffect } from "react";

import { ChatRoute } from "@/components/ChatRoute";
import { ChatWidget } from "@/components/ChatWidget";
import { DEFAULT_CHAT_PATH, useChatLocation, useChatRoute } from "@/location";

export function ChatApp() {
  const { params, matched } = useChatRoute();
  const { replacePath } = useChatLocation();

  useEffect(() => {
    if (!matched) replacePath(DEFAULT_CHAT_PATH);
  }, [matched, replacePath]);

  if (!matched) return null;
  return params.intent !== undefined ? <ChatWidget /> : <ChatRoute />;
}
