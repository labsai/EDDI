import { renderHook, act } from "@testing-library/react";
import { useOnboarding, ALL_CHAPTERS, type TourChapterId } from "@/hooks/use-onboarding";

describe("useOnboarding", () => {
  beforeEach(() => {
    localStorage.clear();
    // Reset the store entirely by calling resetAll
    act(() => {
      useOnboarding.getState().resetAll();
    });
  });

  it("shows welcome on first visit", () => {
    expect(useOnboarding.getState().showWelcome).toBe(true);
  });

  it("dismissWelcome hides welcome and persists", () => {
    act(() => {
      useOnboarding.getState().dismissWelcome();
    });
    expect(useOnboarding.getState().showWelcome).toBe(false);
    expect(localStorage.getItem("eddi-onboarding-welcomed")).toBe("true");
  });

  it("openWelcome shows welcome modal", () => {
    act(() => {
      useOnboarding.getState().dismissWelcome();
    });
    expect(useOnboarding.getState().showWelcome).toBe(false);
    act(() => {
      useOnboarding.getState().openWelcome();
    });
    expect(useOnboarding.getState().showWelcome).toBe(true);
  });

  it("startChapter activates a chapter at step 0", () => {
    act(() => {
      useOnboarding.getState().startChapter("agents");
    });
    const state = useOnboarding.getState();
    expect(state.activeChapter).toBe("agents");
    expect(state.currentStep).toBe(0);
    expect(state.offeredChapter).toBeNull();
  });

  it("nextStep increments step", () => {
    act(() => {
      useOnboarding.getState().startChapter("agents");
      useOnboarding.getState().nextStep();
    });
    expect(useOnboarding.getState().currentStep).toBe(1);
  });

  it("nextStep does nothing when no chapter active", () => {
    act(() => {
      useOnboarding.getState().nextStep();
    });
    expect(useOnboarding.getState().currentStep).toBe(0);
  });

  it("prevStep decrements step, not below 0", () => {
    act(() => {
      useOnboarding.getState().startChapter("agents");
      useOnboarding.getState().nextStep();
      useOnboarding.getState().nextStep();
      useOnboarding.getState().prevStep();
    });
    expect(useOnboarding.getState().currentStep).toBe(1);

    act(() => {
      useOnboarding.getState().prevStep();
      useOnboarding.getState().prevStep();
    });
    expect(useOnboarding.getState().currentStep).toBe(0);
  });

  it("skipChapter marks chapter as done and resets", () => {
    act(() => {
      useOnboarding.getState().startChapter("chat");
      useOnboarding.getState().skipChapter();
    });
    const state = useOnboarding.getState();
    expect(state.activeChapter).toBeNull();
    expect(state.currentStep).toBe(0);
    expect(state.completedChapters.has("chat")).toBe(true);
    expect(localStorage.getItem("eddi-tour-chat")).toBe("done");
  });

  it("completeChapter marks chapter as done", () => {
    act(() => {
      useOnboarding.getState().startChapter("workflows");
      useOnboarding.getState().completeChapter();
    });
    expect(useOnboarding.getState().completedChapters.has("workflows")).toBe(true);
    expect(useOnboarding.getState().activeChapter).toBeNull();
  });

  it("restartChapter clears completion and starts fresh", () => {
    act(() => {
      useOnboarding.getState().startChapter("logs");
      useOnboarding.getState().completeChapter();
    });
    expect(useOnboarding.getState().completedChapters.has("logs")).toBe(true);

    act(() => {
      useOnboarding.getState().restartChapter("logs");
    });
    expect(useOnboarding.getState().activeChapter).toBe("logs");
    expect(useOnboarding.getState().currentStep).toBe(0);
    expect(useOnboarding.getState().completedChapters.has("logs")).toBe(false);
  });

  it("isChapterCompleted returns correct status", () => {
    expect(useOnboarding.getState().isChapterCompleted("agents")).toBe(false);
    act(() => {
      useOnboarding.getState().startChapter("agents");
      useOnboarding.getState().completeChapter();
    });
    expect(useOnboarding.getState().isChapterCompleted("agents")).toBe(true);
  });

  describe("offer bar", () => {
    it("acceptOffer starts the offered chapter", () => {
      act(() => {
        useOnboarding.getState().dismissWelcome();
        useOnboarding.setState({ offeredChapter: "resources" });
        useOnboarding.getState().acceptOffer();
      });
      expect(useOnboarding.getState().activeChapter).toBe("resources");
      expect(useOnboarding.getState().offeredChapter).toBeNull();
    });

    it("acceptOffer does nothing when no offer", () => {
      act(() => {
        useOnboarding.getState().acceptOffer();
      });
      expect(useOnboarding.getState().activeChapter).toBeNull();
    });

    it("dismissOffer marks chapter done", () => {
      act(() => {
        useOnboarding.setState({ offeredChapter: "secrets" });
        useOnboarding.getState().dismissOffer();
      });
      expect(useOnboarding.getState().offeredChapter).toBeNull();
      expect(useOnboarding.getState().completedChapters.has("secrets")).toBe(true);
    });

    it("dismissOffer does nothing when no offer", () => {
      act(() => {
        useOnboarding.getState().dismissOffer();
      });
      expect(useOnboarding.getState().offeredChapter).toBeNull();
    });

    it("dismissAllOffers sets preference and hides offer", () => {
      act(() => {
        useOnboarding.setState({ offeredChapter: "audit" });
        useOnboarding.getState().dismissAllOffers();
      });
      expect(useOnboarding.getState().offeredChapter).toBeNull();
      expect(localStorage.getItem("eddi-tour-offers-dismissed")).toBe("true");
    });
  });

  describe("maybeAutoStart", () => {
    it("auto-starts dashboard chapter", () => {
      act(() => {
        useOnboarding.getState().dismissWelcome();
        useOnboarding.getState().maybeAutoStart("dashboard");
      });
      expect(useOnboarding.getState().activeChapter).toBe("dashboard");
    });

    it("shows offer bar for non-dashboard chapters", () => {
      act(() => {
        useOnboarding.getState().dismissWelcome();
        useOnboarding.getState().maybeAutoStart("agents");
      });
      expect(useOnboarding.getState().offeredChapter).toBe("agents");
      expect(useOnboarding.getState().activeChapter).toBeNull();
    });

    it("does nothing if welcome is showing", () => {
      act(() => {
        useOnboarding.getState().maybeAutoStart("dashboard");
      });
      // Welcome is showing by default, so activeChapter stays null
      expect(useOnboarding.getState().activeChapter).toBeNull();
    });

    it("does nothing if a chapter is already active", () => {
      act(() => {
        useOnboarding.getState().dismissWelcome();
        useOnboarding.getState().startChapter("agents");
        useOnboarding.getState().maybeAutoStart("dashboard");
      });
      expect(useOnboarding.getState().activeChapter).toBe("agents");
    });

    it("does nothing if chapter already completed", () => {
      act(() => {
        useOnboarding.getState().dismissWelcome();
        useOnboarding.getState().startChapter("agents");
        useOnboarding.getState().completeChapter();
        useOnboarding.getState().maybeAutoStart("agents");
      });
      expect(useOnboarding.getState().offeredChapter).toBeNull();
    });

    it("does nothing if offers are dismissed globally", () => {
      act(() => {
        useOnboarding.getState().dismissWelcome();
        useOnboarding.getState().dismissAllOffers();
        useOnboarding.getState().maybeAutoStart("agents");
      });
      expect(useOnboarding.getState().offeredChapter).toBeNull();
    });

    it("does nothing if another offer is already showing", () => {
      act(() => {
        useOnboarding.getState().dismissWelcome();
        useOnboarding.setState({ offeredChapter: "chat" });
        useOnboarding.getState().maybeAutoStart("agents");
      });
      // Stays on the existing offer
      expect(useOnboarding.getState().offeredChapter).toBe("chat");
    });
  });

  it("resetAll clears everything", () => {
    act(() => {
      useOnboarding.getState().dismissWelcome();
      useOnboarding.getState().startChapter("agents");
      useOnboarding.getState().completeChapter();
      useOnboarding.getState().resetAll();
    });
    const state = useOnboarding.getState();
    expect(state.showWelcome).toBe(true);
    expect(state.activeChapter).toBeNull();
    expect(state.currentStep).toBe(0);
    expect(state.offeredChapter).toBeNull();
    expect(state.completedChapters.size).toBe(0);
  });

  it("ALL_CHAPTERS has expected chapters", () => {
    expect(ALL_CHAPTERS).toContain("dashboard");
    expect(ALL_CHAPTERS).toContain("agents");
    expect(ALL_CHAPTERS).toContain("workflows");
    expect(ALL_CHAPTERS).toContain("chat");
    expect(ALL_CHAPTERS.length).toBe(14);
  });
});
