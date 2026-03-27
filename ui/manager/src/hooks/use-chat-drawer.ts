import { create } from "zustand";

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
  step: ChatDrawerStep;
  errorMessage: string | null;

  open(agentId: string, agentName?: string): void;
  close(): void;
  setStep(step: ChatDrawerStep, error?: string): void;
}

export const useChatDrawerStore = create<ChatDrawerState>((set) => ({
  isOpen: false,
  agentId: null,
  agentName: null,
  step: "idle",
  errorMessage: null,

  open: (agentId, agentName) =>
    set({
      isOpen: true,
      agentId,
      agentName: agentName ?? "Agent",
      step: "idle",
      errorMessage: null,
    }),

  close: () =>
    set({
      isOpen: false,
      agentId: null,
      agentName: null,
      step: "idle",
      errorMessage: null,
    }),

  setStep: (step, error) =>
    set({
      step,
      errorMessage: error ?? null,
    }),
}));
