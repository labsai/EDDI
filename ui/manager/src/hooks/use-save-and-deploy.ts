import { useCallback, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";
import { useChatDrawerStore } from "./use-chat-drawer";
import { useChatStore, useStartConversation } from "./use-chat";
import { deployAgent, getDeploymentStatus } from "@/lib/api/agents";

/**
 * Hook that provides a `saveAndDeploy` function.
 * The save logic is passed at call time (not hook configuration time)
 * because the data to save is only known when the button is clicked.
 */
export function useSaveAndDeploy() {
  const { t } = useTranslation();
  const [isRunning, setIsRunning] = useState(false);
  const runningRef = useRef(false);
  const startConversation = useStartConversation();
  const queryClient = useQueryClient();

  const saveAndDeploy = useCallback(
    async (opts: {
      agentId: string;
      agentName?: string;
      /** Should perform the save/cascade and return the new agent version */
      save: () => Promise<{ newAgentVersion: number }>;
    }) => {
      // Use ref for guard — avoids stale closure with useState
      if (runningRef.current) return;
      runningRef.current = true;
      setIsRunning(true);

      const drawerStore = useChatDrawerStore.getState();
      const chatStore = useChatStore.getState();

      try {
        // Step 1: Open drawer + save
        drawerStore.open(opts.agentId, opts.agentName);
        drawerStore.setStep("saving");

        const { newAgentVersion } = await opts.save();
        toast.success(t("editor.saved", "Saved successfully"));

        // Step 2: Deploy
        drawerStore.setStep("deploying");
        await deployAgent("production", opts.agentId, newAgentVersion);

        // Step 3: Poll deployment status (2s interval, 30s timeout)
        const maxAttempts = 15;
        let deployed = false;
        for (let attempt = 0; attempt < maxAttempts; attempt++) {
          await sleep(2000);
          try {
            const status = await getDeploymentStatus(
              "production",
              opts.agentId,
              newAgentVersion
            );
            if (status.status === "READY") {
              deployed = true;
              break;
            }
            if (status.status === "ERROR") {
              throw new Error(t("editor.deployFailed", "Deployment failed"));
            }
          } catch (err) {
            if (attempt === maxAttempts - 1) throw err;
          }
        }

        if (!deployed) {
          drawerStore.setStep(
            "error",
            t("chatDrawer.timeout", "Deploy timed out")
          );
          return;
        }

        // Step 4: Start conversation
        drawerStore.setStep("starting");
        chatStore.clearMessages();
        chatStore.setSelectedAgent(opts.agentId, opts.agentName ?? "Agent");
        await startConversation.mutateAsync({ agentId: opts.agentId });

        // Step 5: Ready
        drawerStore.setStep("ready");
        // Invalidate so agent list and chat reflect new deployment status
        queryClient.invalidateQueries({ queryKey: ["agents"] });
        queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });
      } catch (err) {
        const message =
          err instanceof Error
            ? err.message
            : t("chatDrawer.error", "Something went wrong");
        drawerStore.setStep("error", message);
        toast.error(message);
      } finally {
        runningRef.current = false;
        setIsRunning(false);
      }
    },
    [t, startConversation, queryClient]
  );

  return { saveAndDeploy, isRunning };
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
