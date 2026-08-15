import { create } from "zustand";
import type { Environment } from "@/lib/constants";

export type ChatDrawerStep =
  | "idle"
  | "saving"
  | "deploying"
  | "starting"
  | "ready"
  | "error";

interface ChatDrawerState {
  isOpen: boolean;
  agentId: string | null;
  agentName: string | null;
  /**
   * The environment this drawer's conversation belongs to.
   *
   * Carried on the store rather than re-derived, because the drawer has no
   * deployment query of its own: whoever opened it (an agent card, the detail
   * page, save-and-deploy) knows which environment they meant, and "New
   * conversation" must land in the SAME one. Without this it silently restarted
   * in production — which is how a test-only agent ended up unreachable.
   */
  environment: Environment;
  step: ChatDrawerStep;
  errorMessage: string | null;

  open(agentId: string, agentName?: string, environment?: Environment): void;
  close(): void;
  setStep(step: ChatDrawerStep, error?: string): void;
}

export const useChatDrawerStore = create<ChatDrawerState>((set) => ({
  isOpen: false,
  agentId: null,
  agentName: null,
  environment: "production",
  step: "idle",
  errorMessage: null,

  open: (agentId, agentName, environment = "production") =>
    set({
      isOpen: true,
      agentId,
      agentName: agentName ?? "Agent",
      environment,
      step: "idle",
      errorMessage: null,
    }),

  close: () =>
    set({
      isOpen: false,
      agentId: null,
      agentName: null,
      environment: "production",
      step: "idle",
      errorMessage: null,
    }),

  setStep: (step, error) =>
    set({
      step,
      errorMessage: error ?? null,
    }),
}));
