import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Globe, Loader2, CheckCircle, AlertCircle, Eye, EyeOff } from "lucide-react";
import { useListRemoteAgents } from "@/hooks/use-backup";
import type { DocumentDescriptor } from "@/lib/api/backup";
import { Button } from "@/components/ui/button";

interface SyncConfigPanelProps {
  url: string;
  auth: string;
  onUrlChange: (url: string) => void;
  onAuthChange: (auth: string) => void;
  onConnected: (agents: DocumentDescriptor[]) => void;
}

export function SyncConfigPanel({
  url,
  auth,
  onUrlChange,
  onAuthChange,
  onConnected,
}: SyncConfigPanelProps) {
  const { t } = useTranslation();
  const [showAuth, setShowAuth] = useState(false);
  const [connectionStatus, setConnectionStatus] = useState<
    "idle" | "connecting" | "connected" | "error"
  >("idle");
  const [agentCount, setAgentCount] = useState(0);
  const [connectionError, setConnectionError] = useState<string | null>(null);

  const listMutation = useListRemoteAgents();

  function handleConnect() {
    if (!url.trim()) return;
    setConnectionStatus("connecting");
    setConnectionError(null);

    listMutation.mutate(
      { sourceUrl: url.trim(), sourceAuth: auth.trim() },
      {
        onSuccess: (agents) => {
          setConnectionStatus("connected");
          setAgentCount(agents.length);
          onConnected(agents);
        },
        onError: (err) => {
          setConnectionStatus("error");
          setConnectionError(err.message);
        },
      }
    );
  }

  return (
    <form
      className="space-y-3"
      data-testid="sync-config-panel"
      onSubmit={(e) => {
        e.preventDefault();
        handleConnect();
      }}
    >
      <div className="space-y-1.5">
        <label className="text-sm font-medium text-foreground">
          {t("syncPage.sourceUrl", "Source EDDI Instance URL")}
        </label>
        <input
          type="url"
          value={url}
          onChange={(e) => {
            onUrlChange(e.target.value);
            if (connectionStatus !== "idle") setConnectionStatus("idle");
          }}
          placeholder="https://staging.eddi.example.com"
          className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          data-testid="sync-url-input"
        />
      </div>

      <div className="space-y-1.5">
        <label className="text-sm font-medium text-foreground">
          {t("syncPage.authToken", "Authorization Token")}
        </label>
        <div className="relative">
          <input
            type={showAuth ? "text" : "password"}
            value={auth}
            onChange={(e) => onAuthChange(e.target.value)}
            placeholder="Bearer eyJhb..."
            className="w-full rounded-lg border border-input bg-background px-3 py-2 pe-10 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="sync-auth-input"
          />
          <button
            type="button"
            onClick={() => setShowAuth(!showAuth)}
            className="absolute end-2 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:text-foreground"
          >
            {showAuth ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
          </button>
        </div>
        <p className="text-[11px] text-muted-foreground">
          {t("syncPage.authHint", "Optional. Sent as X-Source-Authorization header.")}
        </p>
      </div>

      <div className="flex items-center gap-3">
        <Button
          type="submit"
          disabled={!url.trim() || listMutation.isPending}
          data-testid="sync-connect-btn"
        >
          {listMutation.isPending ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <Globe className="h-4 w-4" />
          )}
          {t("syncPage.connect", "Connect")}
        </Button>

        {/* Status badge */}
        {connectionStatus === "connected" && (
          <span className="inline-flex items-center gap-1.5 text-xs font-medium text-emerald-600 dark:text-emerald-400">
            <CheckCircle className="h-4 w-4" />
            {t("syncPage.connected", "Connected")} — {agentCount}{" "}
            {t("syncPage.agentsFound", "agents")}
          </span>
        )}
        {connectionStatus === "error" && (
          <span className="inline-flex items-center gap-1.5 text-xs font-medium text-destructive">
            <AlertCircle className="h-4 w-4" />
            {connectionError || t("syncPage.connectionFailed", "Connection failed")}
          </span>
        )}
      </div>
    </form>
  );
}
