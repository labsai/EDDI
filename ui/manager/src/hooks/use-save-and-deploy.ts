import { useCallback, useEffect, useRef, useState } from "react";
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
  const abortRef = useRef<AbortController | null>(null);
  const startConversation = useStartConversation();
  const startConvRef = useRef(startConversation);
  useEffect(() => { startConvRef.current = startConversation; }, [startConversation]);
  const queryClient = useQueryClient();

  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

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
      const controller = new AbortController();
      abortRef.current = controller;

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
        let deployError = false;
        for (let attempt = 0; attempt < maxAttempts; attempt++) {
          await sleep(2000);
          if (abortRef.current?.signal.aborted) {
            drawerStore.setStep("idle");
            return;
          }
          let status: Awaited<ReturnType<typeof getDeploymentStatus>>;
          try {
            status = await getDeploymentStatus(
              "production",
              opts.agentId,
              newAgentVersion
            );
          } catch (err) {
            // A failed status READ is worth retrying; only the last one counts.
            if (attempt === maxAttempts - 1) throw err;
            continue;
          }
          if (status.status === "READY") {
            deployed = true;
            break;
          }
          /*
           * ERROR is the backend's answer, not a flaky read — stop polling now.
           * The throw used to sit inside the try above, whose catch swallowed
           * it on every attempt but the last, so a failed deployment was
           * polled for the full 30 s and then reported as "Deploy timed out".
           */
          if (status.status === "ERROR") {
            deployError = true;
            break;
          }
        }

        if (deployError) {
          throw new Error(t("editor.deployFailed", "Deployment failed"));
        }

        if (!deployed) {
          drawerStore.setStep(
            "error",
            t("chatDrawer.timeout", "Deploy timed out")
          );
          return;
        }

        // Invalidate immediately after deployment is confirmed so caches
        // are fresh even if the conversation start below fails.
        queryClient.invalidateQueries({ queryKey: ["agents"] });
        queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });

        // Step 4: Start conversation
        drawerStore.setStep("starting");
        chatStore.clearMessages();
        chatStore.setSelectedAgent(opts.agentId, opts.agentName ?? "Agent");
        // Same environment this flow just deployed to (step 3 above), not a
        // default: "save & deploy then chat" must open the thing it deployed.
        await startConvRef.current.mutateAsync({ agentId: opts.agentId, environment: "production" });

        // Step 5: Ready
        drawerStore.setStep("ready");
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
        abortRef.current = null;
      }
    },
    [t, queryClient]
  );

  return { saveAndDeploy, isRunning };
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
