import { useCallback, useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";
import { useChatDrawerStore } from "./use-chat-drawer";
import { useChatStore, useStartConversation } from "./use-chat";
import { runDeployWithGrants } from "./use-deploy-with-grants";
import { deployFailureMessage, reportDeployOutcome } from "@/lib/deploy-outcome";

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

        // Step 2: Deploy — grant-aware and waited. A restricted vault key this
        // agent is not granted is asked about BEFORE the deploy (and again if
        // the deploy is refused for one anyway), so the drawer never stops at a
        // bare "Deployment failed" the user cannot act on.
        drawerStore.setStep("deploying");
        const deployOptions = {
          agentId: opts.agentId,
          agentName: opts.agentName,
          version: newAgentVersion,
          environment: "production",
          signal: controller.signal,
        };
        const outcome = await runDeployWithGrants(deployOptions);
        if (abortRef.current?.signal.aborted) {
          drawerStore.setStep("idle");
          return;
        }

        if (outcome.kind === "cancelled") {
          drawerStore.setStep(
            "error",
            t("grantRequired.cancelled", "Not deployed — the vault key was not granted.")
          );
          return;
        }

        if (outcome.kind === "failed") {
          // The backend's reason when it gave one (`failure.message`), and a
          // Fix action for a grant refusal — reported here rather than thrown,
          // so the toast can carry the action.
          const refresh = () => {
            queryClient.invalidateQueries({ queryKey: ["agents"] });
            queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });
          };
          drawerStore.setStep("error", deployFailureMessage(outcome, t));
          reportDeployOutcome(outcome, deployOptions, t, undefined, refresh);
          refresh();
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
