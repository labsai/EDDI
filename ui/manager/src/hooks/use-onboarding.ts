import { create } from "zustand";

/* ================================================================
   Tour chapter IDs
   ================================================================ */
export type TourChapterId =
  | "dashboard"
  | "agents"
  | "workflows"
  | "chat"
  | "resources";

export const ALL_CHAPTERS: TourChapterId[] = [
  "dashboard",
  "agents",
  "workflows",
  "chat",
  "resources",
];

/* ================================================================
   localStorage keys
   ================================================================ */
const WELCOME_KEY = "eddi-onboarding-welcomed";
const chapterKey = (id: TourChapterId) => `eddi-tour-${id}`;

function isChapterDone(id: TourChapterId): boolean {
  try {
    return localStorage.getItem(chapterKey(id)) === "done";
  } catch {
    return false;
  }
}

function markChapterDone(id: TourChapterId): void {
  try {
    localStorage.setItem(chapterKey(id), "done");
  } catch {
    /* noop — storage full or blocked */
  }
}

function clearChapterDone(id: TourChapterId): void {
  try {
    localStorage.removeItem(chapterKey(id));
  } catch {
    /* noop */
  }
}

function hasBeenWelcomed(): boolean {
  try {
    return localStorage.getItem(WELCOME_KEY) === "true";
  } catch {
    return false;
  }
}

function setWelcomed(): void {
  try {
    localStorage.setItem(WELCOME_KEY, "true");
  } catch {
    /* noop */
  }
}

function clearWelcomed(): void {
  try {
    localStorage.removeItem(WELCOME_KEY);
  } catch {
    /* noop */
  }
}

/* ================================================================
   Store interface
   ================================================================ */
interface OnboardingState {
  // Welcome modal
  showWelcome: boolean;

  // Active tour state
  activeChapter: TourChapterId | null;
  currentStep: number;

  // Per-chapter completion
  completedChapters: Set<TourChapterId>;

  // Actions — Welcome
  openWelcome: () => void;
  dismissWelcome: () => void;

  // Actions — Tour
  startChapter: (id: TourChapterId) => void;
  nextStep: () => void;
  prevStep: () => void;
  skipChapter: () => void;
  completeChapter: () => void;
  restartChapter: (id: TourChapterId) => void;

  // Queries
  isChapterCompleted: (id: TourChapterId) => boolean;

  // Auto-trigger — called by page components on mount
  maybeAutoStart: (chapterId: TourChapterId) => void;

  // Reset all
  resetAll: () => void;
}

/* ================================================================
   Zustand store
   ================================================================ */
export const useOnboarding = create<OnboardingState>((set, get) => {
  // Hydrate completed chapters from localStorage
  const initial = new Set<TourChapterId>();
  for (const id of ALL_CHAPTERS) {
    if (isChapterDone(id)) initial.add(id);
  }

  return {
    showWelcome: !hasBeenWelcomed(),
    activeChapter: null,
    currentStep: 0,
    completedChapters: initial,

    openWelcome: () => set({ showWelcome: true }),

    dismissWelcome: () => {
      setWelcomed();
      set({ showWelcome: false });
    },

    startChapter: (id) => {
      set({ activeChapter: id, currentStep: 0 });
    },

    nextStep: () => {
      const { currentStep, activeChapter } = get();
      if (!activeChapter) return;
      // Import would be circular; use inline lazy import check
      // Guard: don't advance past the last step (handled by GuidedTour's handleNext)
      set({ currentStep: currentStep + 1 });
    },

    prevStep: () => {
      set((s) => ({ currentStep: Math.max(0, s.currentStep - 1) }));
    },

    skipChapter: () => {
      const { activeChapter, completedChapters } = get();
      if (activeChapter) {
        markChapterDone(activeChapter);
        const next = new Set(completedChapters);
        next.add(activeChapter);
        set({ activeChapter: null, currentStep: 0, completedChapters: next });
      }
    },

    completeChapter: () => {
      const { activeChapter, completedChapters } = get();
      if (activeChapter) {
        markChapterDone(activeChapter);
        const next = new Set(completedChapters);
        next.add(activeChapter);
        set({ activeChapter: null, currentStep: 0, completedChapters: next });
      }
    },

    restartChapter: (id) => {
      clearChapterDone(id);
      const next = new Set(get().completedChapters);
      next.delete(id);
      set({ activeChapter: id, currentStep: 0, completedChapters: next });
    },

    isChapterCompleted: (id) => get().completedChapters.has(id),

    maybeAutoStart: (chapterId) => {
      const { activeChapter, completedChapters, showWelcome } = get();
      // Don't auto-start if: welcome modal is open, another tour is active, or chapter is done
      if (showWelcome || activeChapter || completedChapters.has(chapterId)) return;
      set({ activeChapter: chapterId, currentStep: 0 });
    },

    resetAll: () => {
      clearWelcomed();
      for (const id of ALL_CHAPTERS) clearChapterDone(id);
      set({
        showWelcome: true,
        activeChapter: null,
        currentStep: 0,
        completedChapters: new Set(),
      });
    },
  };
});
