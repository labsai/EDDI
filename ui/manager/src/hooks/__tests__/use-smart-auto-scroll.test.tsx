import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, act } from "@testing-library/react";
import { useSmartAutoScroll } from "@eddi/app-kit/hooks/use-smart-auto-scroll";

/**
 * The reported bug: while a reply is streaming, the user cannot scroll up.
 *
 * The hook always had a "pause when the user scrolls away" branch, and it was
 * unreachable during a stream. Two reasons, both covered here:
 *
 *  1. Intent was inferred from the `scroll` event, which our own `scrollTo`
 *     also fires — so every token overwrote "the user scrolled up" with the
 *     position the auto-scroll had just produced.
 *  2. Each token restarted a *smooth* scroll, leaving the container in
 *     continuous programmatic motion, so there was no still moment in which a
 *     gesture could take effect.
 */
describe("useSmartAutoScroll", () => {
  /**
   * A scroll container jsdom can measure. jsdom reports 0 for every layout
   * property and implements no scrolling, so the geometry is stubbed and
   * `scrollTo` is recorded rather than performed.
   */
  function Harness({ tokens }: { tokens: number }) {
    const { scrollRef, handleScroll, showScrollFab } =
      useSmartAutoScroll<HTMLDivElement>({ deps: [tokens] });

    return (
      <div
        ref={scrollRef}
        onScroll={handleScroll}
        data-testid="scroller"
        data-fab={showScrollFab ? "shown" : "hidden"}
      >
        <div data-testid="content">{tokens}</div>
      </div>
    );
  }

  /**
   * @param overflows
   *   false models a conversation short enough to fit the viewport — nothing to
   *   scroll, so `scrollTop` is pinned at 0 and no gesture produces a `scroll`
   *   event.
   */
  function mountScroller({ overflows = true }: { overflows?: boolean } = {}) {
    const calls: ScrollToOptions[] = [];
    const view = render(<Harness tokens={0} />);
    const el = view.getByTestId("scroller") as HTMLDivElement;

    let scrollHeight = overflows ? 1000 : 200;
    Object.defineProperty(el, "scrollHeight", {
      configurable: true,
      get: () => scrollHeight,
    });
    Object.defineProperty(el, "clientHeight", { value: 200, configurable: true });
    let scrollTop = overflows ? 800 : 0;
    const scrollTopWrites: number[] = [];
    Object.defineProperty(el, "scrollTop", {
      configurable: true,
      get: () => scrollTop,
      set: (v: number) => {
        scrollTopWrites.push(v);
        scrollTop = v;
      },
    });
    el.scrollTo = ((opts: ScrollToOptions) => {
      calls.push(opts);
      scrollTop = overflows ? 800 : 0;
    }) as HTMLElement["scrollTo"];

    return {
      view,
      el,
      calls,
      scrollTopWrites,
      setScrollTop: (v: number) => (scrollTop = v),
      setScrollHeight: (v: number) => (scrollHeight = v),
    };
  }

  beforeEach(() => {
    vi.useRealTimers();
  });

  it("keeps following the bottom while tokens arrive and the user has not moved", () => {
    const { view, calls } = mountScroller();

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    expect(calls.length).toBeGreaterThanOrEqual(2);
    expect(calls[calls.length - 1]?.top).toBe(1000);
  });

  it("uses instant scrolling for rapid updates, so the container is still between tokens", () => {
    const { view, calls } = mountScroller();

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    // A smooth scroll restarted every few ms never settles, and a wheel gesture
    // is cancelled by the next one before it can travel a line.
    expect(calls[calls.length - 1]?.behavior).toBe("auto");
  });

  it("stops following as soon as the user wheels upward mid-stream", () => {
    const { view, el, calls } = mountScroller();

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    const before = calls.length;

    act(() => {
      el.dispatchEvent(new WheelEvent("wheel", { deltaY: -120, bubbles: true }));
    });

    act(() => {
      view.rerender(<Harness tokens={2} />);
    });
    act(() => {
      view.rerender(<Harness tokens={3} />);
    });

    expect(calls.length).toBe(before);
    expect(el.getAttribute("data-fab")).toBe("shown");
  });

  it("ignores a downward wheel — that is following along, not reading history", () => {
    const { view, el, calls } = mountScroller();

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    const before = calls.length;

    act(() => {
      el.dispatchEvent(new WheelEvent("wheel", { deltaY: 120, bubbles: true }));
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    expect(calls.length).toBe(before + 1);
  });

  it("stops following on PageUp", () => {
    const { view, el, calls } = mountScroller();

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    const before = calls.length;

    act(() => {
      el.dispatchEvent(new KeyboardEvent("keydown", { key: "PageUp", bubbles: true }));
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    expect(calls.length).toBe(before);
  });

  it("ignores an upward gesture the container cannot act on", () => {
    // A short conversation that fits the viewport. Wheeling up scrolls nothing,
    // so no `scroll` event follows to correct the state — pausing here would
    // strand the user with a "Scroll to bottom" control while they are already
    // at the bottom, and stop the reply following.
    const { view, el, calls } = mountScroller({ overflows: false });

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    const before = calls.length;

    act(() => {
      el.dispatchEvent(new WheelEvent("wheel", { deltaY: -120, bubbles: true }));
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    expect(calls.length).toBe(before + 1);
    expect(el.getAttribute("data-fab")).toBe("hidden");
  });

  it("ignores PageUp at the very top, where there is nowhere further to go", () => {
    const { view, el, calls } = mountScroller({ overflows: false });

    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    const before = calls.length;

    act(() => {
      el.dispatchEvent(new KeyboardEvent("keydown", { key: "PageUp", bubbles: true }));
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    expect(calls.length).toBe(before + 1);
  });

  it("resumes following once the user scrolls back to the bottom", () => {
    const { view, el, calls, setScrollTop } = mountScroller();

    act(() => {
      el.dispatchEvent(new WheelEvent("wheel", { deltaY: -120, bubbles: true }));
    });
    act(() => {
      view.rerender(<Harness tokens={1} />);
    });
    const paused = calls.length;

    // Back within the bottom threshold, reported through the container's own
    // scroll event exactly as the browser would.
    act(() => {
      setScrollTop(800);
      el.dispatchEvent(new Event("scroll", { bubbles: true }));
    });
    act(() => {
      view.rerender(<Harness tokens={2} />);
    });

    expect(calls.length).toBe(paused + 1);
  });

  /**
   * Content that grows WITHOUT a `deps` change — the throttled trailing render
   * of a streaming reply adds lines after the deps effect has scrolled. jsdom
   * has no ResizeObserver and no layout, so both the observer and the animation
   * frame are fakes the test drives by hand.
   */
  describe("re-pins when the content grows on its own", () => {
    class FakeResizeObserver {
      static instances: FakeResizeObserver[] = [];
      readonly observed = new Set<Element>();
      disconnected = false;
      constructor(private readonly callback: ResizeObserverCallback) {
        FakeResizeObserver.instances.push(this);
      }
      observe(target: Element) {
        this.observed.add(target);
      }
      unobserve(target: Element) {
        this.observed.delete(target);
      }
      disconnect() {
        this.disconnected = true;
        this.observed.clear();
      }
      /** What the browser does after a layout in which an observed box changed size. */
      fire() {
        this.callback([], this as unknown as ResizeObserver);
      }
    }

    let frames: FrameRequestCallback[] = [];
    const flushFrames = () => {
      const pending = frames;
      frames = [];
      pending.forEach((cb) => cb(0));
    };

    beforeEach(() => {
      FakeResizeObserver.instances = [];
      frames = [];
      vi.stubGlobal("ResizeObserver", FakeResizeObserver);
      vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => {
        frames.push(cb);
        return frames.length;
      });
      vi.stubGlobal("cancelAnimationFrame", () => {});
    });

    afterEach(() => {
      vi.unstubAllGlobals();
    });

    function observer(): FakeResizeObserver {
      const live = FakeResizeObserver.instances.filter((o) => !o.disconnected);
      expect(live).toHaveLength(1);
      return live[0]!;
    }

    it("observes the container and its content once, not per render", () => {
      const { view, el } = mountScroller();
      act(() => {
        view.rerender(<Harness tokens={1} />);
      });
      act(() => {
        view.rerender(<Harness tokens={2} />);
      });

      expect(FakeResizeObserver.instances).toHaveLength(1);
      expect(observer().observed.has(el)).toBe(true);
      expect(observer().observed.has(view.getByTestId("content"))).toBe(true);
    });

    it("scrolls to the new bottom while pinned, once per frame however often it grows", () => {
      const { el, setScrollHeight, scrollTopWrites } = mountScroller();
      flushFrames();
      scrollTopWrites.length = 0;

      setScrollHeight(1400);
      act(() => {
        observer().fire();
        observer().fire();
        observer().fire();
      });
      expect(frames).toHaveLength(1);
      act(() => flushFrames());

      expect(scrollTopWrites).toEqual([1400]);
      expect(el.scrollTop).toBe(1400);
    });

    it("does nothing when a notification brings no growth", () => {
      const { scrollTopWrites } = mountScroller();
      flushFrames();
      scrollTopWrites.length = 0;

      act(() => {
        observer().fire();
      });
      act(() => flushFrames());

      expect(scrollTopWrites).toEqual([]);
    });

    it("never pulls down a user who scrolled up", () => {
      const { el, setScrollHeight, scrollTopWrites } = mountScroller();
      flushFrames();
      act(() => {
        el.dispatchEvent(new WheelEvent("wheel", { deltaY: -120, bubbles: true }));
      });
      scrollTopWrites.length = 0;

      setScrollHeight(1400);
      act(() => {
        observer().fire();
      });
      act(() => flushFrames());

      expect(frames).toHaveLength(0);
      expect(scrollTopWrites).toEqual([]);
      expect(el.scrollTop).toBe(800);
    });

    it("does not scroll if the user leaves the bottom before the frame runs", () => {
      const { el, setScrollHeight, scrollTopWrites } = mountScroller();
      flushFrames();
      scrollTopWrites.length = 0;

      setScrollHeight(1400);
      act(() => {
        observer().fire();
      });
      act(() => {
        el.dispatchEvent(new WheelEvent("wheel", { deltaY: -120, bubbles: true }));
      });
      act(() => flushFrames());

      expect(scrollTopWrites).toEqual([]);
    });

    it("disconnects when the hook unmounts", () => {
      const { view } = mountScroller();
      const live = observer();

      view.unmount();

      expect(live.disconnected).toBe(true);
    });
  });
});
