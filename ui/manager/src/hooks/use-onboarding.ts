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
const OFFER_DISMISSED_KEY = "eddi-tour-offers-dismissed";
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

function areOffersDismissed(): boolean {
  try {
    return localStorage.getItem(OFFER_DISMISSED_KEY) === "true";
  } catch {
    return false;
  }
}

function setOffersDismissed(): void {
  try {
    localStorage.setItem(OFFER_DISMISSED_KEY, "true");
  } catch {
    /* noop */
  }
}

function clearOffersDismissed(): void {
  try {
    localStorage.removeItem(OFFER_DISMISSED_KEY);
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

  // Offer bar — subtle prompt for non-dashboard chapters
  offeredChapter: TourChapterId | null;

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

  // Actions — Offer bar
  acceptOffer: () => void;
  dismissOffer: () => void;
  dismissAllOffers: () => void;

  // Queries
  isChapterCompleted: (id: TourChapterId) => boolean;

  // Auto-trigger — called by page components on mount
  // Dashboard auto-starts tour; other pages show a subtle offer bar
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
    offeredChapter: null,
    completedChapters: initial,

    openWelcome: () => set({ showWelcome: true }),

    dismissWelcome: () => {
      setWelcomed();
      set({ showWelcome: false });
    },

    startChapter: (id) => {
      set({ activeChapter: id, currentStep: 0, offeredChapter: null });
    },

    nextStep: () => {
      const { currentStep, activeChapter } = get();
      if (!activeChapter) return;
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
      set({ activeChapter: id, currentStep: 0, offeredChapter: null, completedChapters: next });
    },

    // --- Offer bar actions ---
    acceptOffer: () => {
      const { offeredChapter } = get();
      if (offeredChapter) {
        set({ activeChapter: offeredChapter, currentStep: 0, offeredChapter: null });
      }
    },

    dismissOffer: () => {
      const { offeredChapter, completedChapters } = get();
      if (offeredChapter) {
        // Mark as done so the offer doesn't re-appear on this page
        markChapterDone(offeredChapter);
        const next = new Set(completedChapters);
        next.add(offeredChapter);
        set({ offeredChapter: null, completedChapters: next });
      }
    },

    dismissAllOffers: () => {
      setOffersDismissed();
      set({ offeredChapter: null });
    },

    isChapterCompleted: (id) => get().completedChapters.has(id),

    maybeAutoStart: (chapterId) => {
      const { activeChapter, completedChapters, showWelcome, offeredChapter } = get();
      // Don't show anything if: welcome modal is open, a tour is active,
      // chapter is already done, or an offer is already showing
      if (showWelcome || activeChapter || completedChapters.has(chapterId) || offeredChapter) return;

      // Dashboard auto-starts (it's the primary tour triggered by the welcome modal)
      if (chapterId === "dashboard") {
        set({ activeChapter: chapterId, currentStep: 0 });
        return;
      }

      // All other chapters: show a subtle offer bar instead of auto-starting
      // Unless the user has dismissed all offers
      if (areOffersDismissed()) return;
      set({ offeredChapter: chapterId });
    },

    resetAll: () => {
      clearWelcomed();
      clearOffersDismissed();
      for (const id of ALL_CHAPTERS) clearChapterDone(id);
      set({
        showWelcome: true,
        activeChapter: null,
        currentStep: 0,
        offeredChapter: null,
        completedChapters: new Set(),
      });
    },
  };
});
