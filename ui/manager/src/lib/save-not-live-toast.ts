import type { QueryClient } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { deployAgent } from "@/lib/api/agents";

/**
 * One id for every "saved — not yet live" toast, so only the most recent save's
 * Deploy action is ever on screen. Each action closes over the agent version its
 * own save produced: two live toasts meant clicking the older one deployed the
 * older configuration over the newer one.
 */
export const SAVE_NOT_LIVE_TOAST_ID = "resource-save-not-live";

/**
 * Tell the user a save created a new agent version that is NOT what the running
 * agent serves, and offer the one action that closes the gap.
 *
 * The Deploy action is offered only when the save produced a new agent version:
 * deploying any other version would not contain the edit the toast speaks of.
 */
export function showSavedNotLiveToast(opts: {
  t: TFunction;
  queryClient: QueryClient;
  agentId: string;
  /** The agent version the save created, when it created one. */
  newAgentVersion?: number | null;
  /** What was saved, shown in the title. Defaults to a generic "Saved". */
  title?: string;
}) {
  const { t, queryClient, agentId, newAgentVersion } = opts;
  const version = newAgentVersion ?? undefined;
  toast.success(
    opts.title ??
      (version !== undefined
        ? t("editor.savedAsVersion", "Saved as v{{version}} — not live yet", { version })
        : t("editor.savedNotLive", "Saved — not yet live")),
    {
      id: SAVE_NOT_LIVE_TOAST_ID,
      description: t(
        "editor.savedNotLiveDescription",
        "The running agent still serves the deployed version. Deploy to make this change take effect.",
      ),
      action:
        version !== undefined
          ? {
              // The action always targets production; say so.
              label: t("agents.deployToProduction", "Deploy to production"),
              onClick: () => {
                deployAgent("production", agentId, version)
                  .then(() => {
                    queryClient.invalidateQueries({ queryKey: ["agents"] });
                    queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });
                    toast.success(t("editor.deployStarted", "Deployment started"));
                  })
                  .catch((err) => toast.error(getErrorMessage(err)));
              },
            }
          : undefined,
    },
  );
}
