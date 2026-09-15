import { create } from "zustand";

interface OperatorDrawerState {
  isOpen: boolean;
  open(): void;
  close(): void;
  toggle(): void;
}

export const useOperatorDrawerStore = create<OperatorDrawerState>((set) => ({
  isOpen: false,
  open: () => set({ isOpen: true }),
  close: () => set({ isOpen: false }),
  toggle: () => set((s) => ({ isOpen: !s.isOpen })),
}));
