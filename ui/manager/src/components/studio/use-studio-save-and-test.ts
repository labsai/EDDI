import { useCallback, useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { deployAgent, getDeploymentStatus } from "@/lib/api/agents";
import { useChatStore, useStartConversation } from "@/hooks/use-chat";

const POLL_ATTEMPTS = 15;
const POLL_INTERVAL_MS = 2000;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Save & Test for the Studio: save, deploy, wait until the deployment is ready,
 * then start a fresh conversation in the chat panel that sits BESIDE the editor.
 *
 * The generic `useSaveAndDeploy` drives the global chat drawer, which the
 * Studio (a full-screen route outside the app layout) does not render — and
 * opening it would leave it open on the next page. The Studio has its own chat
 * panel, so this flow talks to that one.
 */
export function useStudioSaveAndTest() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const startConversation = useStartConversation();
  const startRef = useRef(startConversation);
  useEffect(() => {
    startRef.current = startConversation;
  }, [startConversation]);
  const [isRunning, setIsRunning] = useState(false);
  const runningRef = useRef(false);

  const saveAndTest = useCallback(
    async (opts: {
      agentId: string;
      agentName: string;
      /** Performs the save/cascade and returns the agent version it created. */
      save: () => Promise<{ newAgentVersion: number }>;
    }) => {
      if (runningRef.current) return;
      runningRef.current = true;
      setIsRunning(true);
      try {
        const { newAgentVersion } = await opts.save();
        await deployAgent("production", opts.agentId, newAgentVersion);

        let ready = false;
        for (let attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
          await sleep(POLL_INTERVAL_MS);
          let status: Awaited<ReturnType<typeof getDeploymentStatus>>;
          try {
            status = await getDeploymentStatus("production", opts.agentId, newAgentVersion);
          } catch (err) {
            if (attempt === POLL_ATTEMPTS - 1) throw err;
            continue;
          }
          if (status.status === "READY") {
            ready = true;
            break;
          }
          if (status.status === "ERROR") throw new Error(t("editor.deployFailed", "Deployment failed"));
        }
        if (!ready) throw new Error(t("chatDrawer.timeout", "Deploy timed out"));

        queryClient.invalidateQueries({ queryKey: ["agents"] });
        queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });

        // Bind the chat to THIS agent before starting: the panel follows the
        // global chat store, which may still hold whichever agent was chatted
        // with last.
        const chatStore = useChatStore.getState();
        chatStore.clearMessages();
        chatStore.setSelectedAgent(opts.agentId, opts.agentName);
        await startRef.current.mutateAsync({ agentId: opts.agentId, environment: "production" });
        toast.success(
          t("studio.testReady", "Saved and deployed as v{{version}} — the chat is ready", {
            version: newAgentVersion,
          }),
        );
      } catch (err) {
        toast.error(getErrorMessage(err));
      } finally {
        runningRef.current = false;
        setIsRunning(false);
      }
    },
    [t, queryClient],
  );

  return { saveAndTest, isRunning };
}
